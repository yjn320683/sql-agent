"""WebHDFS、YARN ResourceManager 和 MapReduce JobHistory 适配器。"""

from __future__ import annotations

import hashlib
import re
from collections import deque
from html.parser import HTMLParser
from typing import Any
from urllib.parse import quote, urlparse

from sql_agent_mcp_server.common.errors import McpDomainError, McpErrorCode
from sql_agent_mcp_server.common.http import ReadonlyJsonHttpClient

APPLICATION_ID_PATTERN = re.compile(r"^application_(\d+)_(\d+)$")
JOB_ID_PATTERN = re.compile(r"^job_(\d+)_(\d+)$")
SAFE_LOG_TYPE_PATTERN = re.compile(r"^[A-Za-z0-9._-]{1,64}$")
SECRET_PATTERN = re.compile(
    r"(?i)(password|passwd|token|secret|access[_-]?key|private[_-]?key)\s*[=:]\s*[^\s;,]+"
)
URI_USERINFO_PATTERN = re.compile(r"(?i)([a-z][a-z0-9+.-]*://)[^/@\s]+@")
BEARER_PATTERN = re.compile(r"(?i)(authorization\s*[=:]\s*bearer\s+)[A-Za-z0-9._~+/=-]+")
SAFE_JOB_CONF_KEYS = {
    "hive.query.id",
    "hive.exec.query.id",
    "hive.execution.engine",
    "hive.current.database",
    "mapreduce.job.name",
    "mapreduce.job.queuename",
    "mapreduce.job.user.name",
    "mapreduce.job.inputformat.class",
    "mapreduce.job.outputformat.class",
    "mapreduce.map.memory.mb",
    "mapreduce.reduce.memory.mb",
    "mapreduce.job.reduces",
}
FINGERPRINT_JOB_CONF_KEYS = {
    "hive.query.string",
    "mapreduce.input.fileinputformat.inputdir",
    "mapred.input.dir",
}


def application_to_job_id(application_id: str) -> str:
    match = APPLICATION_ID_PATTERN.fullmatch(application_id)
    if not match:
        raise McpDomainError(
            McpErrorCode.INVALID_REQUEST,
            "YARN application ID is invalid.",
            details={"applicationId": application_id},
        )
    return f"job_{match.group(1)}_{match.group(2)}"


def job_to_application_id(job_id: str) -> str:
    match = JOB_ID_PATTERN.fullmatch(job_id)
    if not match:
        raise McpDomainError(
            McpErrorCode.INVALID_REQUEST,
            "MapReduce job ID is invalid.",
            details={"jobId": job_id},
        )
    return f"application_{match.group(1)}_{match.group(2)}"


class WebHdfsAdapter:
    source = "webhdfs"

    def __init__(self, urls: tuple[str, ...], *, timeout_seconds: int, http_user: str | None) -> None:
        normalized = tuple(url if url.rstrip("/").endswith("/webhdfs/v1") else url.rstrip("/") + "/webhdfs/v1" for url in urls)
        self.client = ReadonlyJsonHttpClient(
            normalized,
            dependency_name="WEBHDFS_URLS",
            timeout_seconds=timeout_seconds,
            http_user=http_user,
        )

    def health(self) -> dict[str, Any]:
        return self.client.get_json("/", params={"op": "GETFILESTATUS"}).get("FileStatus", {})

    def inspect_paths(self, locations: list[str], *, max_files: int) -> tuple[list[dict], list[int], bool, list[str]]:
        path_summaries: list[dict] = []
        file_sizes: list[int] = []
        complete = True
        missing_reasons: list[str] = []
        remaining = max_files
        for location in locations:
            path = _hdfs_path(location)
            summary_payload = self.client.get_json(
                _webhdfs_path(path),
                params={"op": "GETCONTENTSUMMARY"},
                not_found_message="Hive storage path was not found in HDFS.",
            )
            summary = summary_payload.get("ContentSummary", {})
            path_summaries.append({"path": path, **summary})
            if remaining <= 0:
                complete = False
                continue
            sampled, listing_complete = self._sample_file_sizes(path, remaining)
            file_sizes.extend(sampled)
            remaining -= len(sampled)
            if not listing_complete:
                complete = False
        total_files = sum(int(item.get("fileCount") or 0) for item in path_summaries)
        if len(file_sizes) < total_files:
            complete = False
            missing_reasons.append("file_distribution_sampled")
        return path_summaries, file_sizes, complete, missing_reasons

    def get_path_status(self, location: str) -> dict[str, Any]:
        path = _hdfs_path(location)
        payload = self.client.get_json(
            _webhdfs_path(path),
            params={"op": "GETFILESTATUS"},
            not_found_message="Hive storage path was not found in HDFS.",
        )
        status = payload.get("FileStatus", {})
        allowed = (
            "type", "length", "modificationTime", "accessTime", "blockSize",
            "replication", "owner", "group", "permission",
        )
        return {"path": path, **{key: status.get(key) for key in allowed if status.get(key) is not None}}

    def _sample_file_sizes(self, root_path: str, limit: int) -> tuple[list[int], bool]:
        sizes: list[int] = []
        directories = deque([root_path])
        visited_directories = 0
        while directories and len(sizes) < limit and visited_directories < 200:
            directory = directories.popleft()
            visited_directories += 1
            payload = self.client.get_json(
                _webhdfs_path(directory),
                params={"op": "LISTSTATUS"},
                not_found_message="Hive storage directory was not found in HDFS.",
            )
            statuses = payload.get("FileStatuses", {}).get("FileStatus", []) or []
            for status in statuses:
                suffix = str(status.get("pathSuffix") or "")
                child = directory.rstrip("/") + "/" + suffix
                if status.get("type") == "DIRECTORY":
                    directories.append(child)
                elif status.get("type") == "FILE":
                    sizes.append(int(status.get("length") or 0))
                    if len(sizes) >= limit:
                        break
        return sizes, not directories and visited_directories < 200


class YarnResourceManagerAdapter:
    source = "yarn-resource-manager"

    def __init__(self, urls: tuple[str, ...], *, timeout_seconds: int, http_user: str | None) -> None:
        self.client = ReadonlyJsonHttpClient(
            urls,
            dependency_name="YARN_RESOURCE_MANAGER_URLS",
            timeout_seconds=timeout_seconds,
            http_user=http_user,
        )

    def health(self) -> dict[str, Any]:
        return self.client.get_json("/ws/v1/cluster/info").get("clusterInfo", {})

    def get_application(self, application_id: str, *, include_attempts: bool, include_containers: bool) -> dict[str, Any]:
        application_to_job_id(application_id)
        app = self.client.get_json(
            f"/ws/v1/cluster/apps/{quote(application_id)}",
            not_found_message="YARN application was not found or has expired from ResourceManager history.",
        ).get("app", {})
        attempts: list[dict] = []
        if include_attempts:
            attempts = self.client.get_json(
                f"/ws/v1/cluster/apps/{quote(application_id)}/appattempts",
                not_found_message="YARN application attempts were not found.",
            ).get("appAttempts", {}).get("appAttempt", []) or []
        normalized_attempts: list[dict] = []
        for attempt in attempts:
            attempt_data = _pick(attempt, ("id", "startTime", "finishedTime", "containerId", "nodeId", "nodeHttpAddress"))
            containers: list[dict] = []
            if include_containers:
                attempt_id = _application_attempt_id(application_id, attempt.get("id"))
                try:
                    raw_containers = self.client.get_json(
                        f"/ws/v1/cluster/apps/{quote(application_id)}/appattempts/{quote(attempt_id)}/containers",
                        not_found_message="YARN application containers were not found.",
                    ).get("containers", {}).get("container", []) or []
                    containers = [
                        _pick(item, (
                            "containerId", "allocatedMB", "allocatedVCores", "assignedNodeId", "startedTime",
                            "finishedTime", "elapsedTime", "containerExitStatus", "containerState",
                        ))
                        for item in raw_containers[:200]
                    ]
                except McpDomainError as exc:
                    if exc.code != McpErrorCode.NOT_FOUND:
                        raise
                    attempt_data["containerWarning"] = "containers_not_retained"
            attempt_data["containers"] = containers
            normalized_attempts.append(attempt_data)
        return _normalize_application(app, normalized_attempts)


class JobHistoryAdapter:
    source = "mapreduce-job-history"

    def __init__(self, urls: tuple[str, ...], *, timeout_seconds: int, http_user: str | None) -> None:
        self.client = ReadonlyJsonHttpClient(
            urls,
            dependency_name="MAPREDUCE_JOB_HISTORY_URLS",
            timeout_seconds=timeout_seconds,
            http_user=http_user,
        )

    def health(self) -> dict[str, Any]:
        return self.client.get_json("/ws/v1/history/info").get("historyInfo", {})

    def get_job(self, job_id: str) -> dict[str, Any]:
        job_to_application_id(job_id)
        return self.client.get_json(
            f"/ws/v1/history/mapreduce/jobs/{quote(job_id)}",
            not_found_message="MapReduce job was not found or has expired from JobHistory.",
        ).get("job", {})

    def get_job_attempts(self, job_id: str) -> list[dict[str, Any]]:
        job_to_application_id(job_id)
        payload = self.client.get_json(
            f"/ws/v1/history/mapreduce/jobs/{quote(job_id)}/jobattempts",
            not_found_message="MapReduce job attempts were not found or have expired from JobHistory.",
        )
        return payload.get("jobAttempts", {}).get("jobAttempt", []) or []

    def get_aggregated_log(
        self,
        logs_link: str,
        *,
        log_type: str,
        max_chars: int,
    ) -> tuple[str, bool]:
        if not SAFE_LOG_TYPE_PATTERN.fullmatch(log_type):
            raise McpDomainError(
                McpErrorCode.INVALID_REQUEST,
                "YARN log type is invalid.",
                details={"logType": log_type},
            )
        parsed = urlparse(logs_link)
        return self._get_aggregated_log_path(
            parsed.path,
            log_type=log_type,
            max_chars=max_chars,
        )

    def get_aggregated_log_for_container(
        self,
        *,
        job_id: str,
        node_id: str,
        container_id: str,
        owner: str,
        log_type: str,
        max_chars: int,
    ) -> tuple[str, bool]:
        job_to_application_id(job_id)
        coordinates = (node_id, container_id, owner)
        if any(not value or "/" in value or "\\" in value for value in coordinates):
            raise McpDomainError(
                McpErrorCode.INVALID_REQUEST,
                "YARN aggregated log coordinates are invalid.",
            )
        path = "/jobhistory/logs/{}/{}/{}/{}".format(
            quote(node_id, safe=":._-"),
            quote(container_id, safe="._-"),
            quote(job_id, safe="._-"),
            quote(owner, safe="@._-"),
        )
        return self._get_aggregated_log_path(path, log_type=log_type, max_chars=max_chars)

    def _get_aggregated_log_path(
        self,
        path: str,
        *,
        log_type: str,
        max_chars: int,
    ) -> tuple[str, bool]:
        segments = path.split("/")
        if not path.startswith("/jobhistory/logs/") or any(segment in {".", ".."} for segment in segments):
            raise McpDomainError(
                McpErrorCode.INVALID_REQUEST,
                "JobHistory returned an unsupported aggregated log path.",
            )
        text, response_truncated = self.client.get_text(
            path.rstrip("/") + "/" + quote(log_type),
            params={"start": -max_chars * 4},
            not_found_message="YARN aggregated log was not found or has expired.",
            max_response_bytes=max_chars * 4,
        )
        content = _extract_log_text(text)
        content_truncated = len(content) > max_chars
        return _redact_log_text(content[-max_chars:]), response_truncated or content_truncated

    def search_jobs(self, params: dict[str, Any]) -> list[dict[str, Any]]:
        payload = self.client.get_json("/ws/v1/history/mapreduce/jobs", params=params)
        return payload.get("jobs", {}).get("job", []) or []

    def get_counters(self, job_id: str) -> dict[str, int]:
        payload = self.client.get_json(f"/ws/v1/history/mapreduce/jobs/{quote(job_id)}/counters")
        groups = payload.get("jobCounters", {}).get("counterGroup", []) or []
        result: dict[str, int] = {}
        for group in groups:
            group_name = str(group.get("counterGroupName") or "")
            for counter in group.get("counter", []) or []:
                name = str(counter.get("name") or "")
                result[f"{group_name}.{name}"] = int(counter.get("totalCounterValue") or 0)
        return result

    def get_tasks(self, job_id: str) -> list[dict[str, Any]]:
        payload = self.client.get_json(f"/ws/v1/history/mapreduce/jobs/{quote(job_id)}/tasks")
        return payload.get("tasks", {}).get("task", []) or []

    def get_task_attempts(self, job_id: str, task_id: str) -> list[dict[str, Any]]:
        payload = self.client.get_json(
            f"/ws/v1/history/mapreduce/jobs/{quote(job_id)}/tasks/{quote(task_id)}/attempts"
        )
        return payload.get("taskAttempts", {}).get("taskAttempt", []) or []

    def get_safe_configuration(self, job_id: str) -> dict[str, Any]:
        payload = self.client.get_json(f"/ws/v1/history/mapreduce/jobs/{quote(job_id)}/conf")
        properties = payload.get("conf", {}).get("property", []) or []
        result: dict[str, Any] = {}
        fingerprints: dict[str, str] = {}
        for item in properties:
            name = str(item.get("name") or "")
            value = str(item.get("value") or "")
            if name in SAFE_JOB_CONF_KEYS:
                result[name] = value[:2000]
            elif name in FINGERPRINT_JOB_CONF_KEYS:
                fingerprints[name] = hashlib.sha256(value.encode("utf-8")).hexdigest()
        if fingerprints:
            result["fingerprints"] = fingerprints
        return result


def _normalize_application(app: dict[str, Any], attempts: list[dict[str, Any]]) -> dict[str, Any]:
    started = _int_or_none(app.get("startedTime"))
    launched = _int_or_none(app.get("launchTime"))
    finished = _int_or_none(app.get("finishedTime"))
    result = _pick(app, (
        "id", "name", "user", "queue", "state", "finalStatus", "progress", "applicationType",
        "diagnostics", "submittedTime", "startedTime", "launchTime", "finishedTime", "elapsedTime",
        "memorySeconds", "vcoreSeconds", "preemptedResourceMB", "numNonAMContainerPreempted",
        "numAMContainerPreempted",
    ))
    result["queueWaitMs"] = launched - started if launched is not None and started is not None else None
    result["runDurationMs"] = finished - launched if launched is not None and finished is not None else None
    result["attempts"] = attempts
    return result


def _hdfs_path(location: str) -> str:
    parsed = urlparse(location)
    path = parsed.path if parsed.scheme else location
    if not path.startswith("/"):
        raise McpDomainError(McpErrorCode.INVALID_REQUEST, "Hive storage location is invalid.")
    return path


def _webhdfs_path(path: str) -> str:
    return quote(path, safe="/")


def _application_attempt_id(application_id: str, attempt_number: Any) -> str:
    match = APPLICATION_ID_PATTERN.fullmatch(application_id)
    if not match:
        return str(attempt_number)
    return f"appattempt_{match.group(1)}_{match.group(2)}_{int(attempt_number):06d}"


def _pick(source: dict[str, Any], keys: tuple[str, ...]) -> dict[str, Any]:
    return {key: source.get(key) for key in keys if key in source}


def _int_or_none(value: Any) -> int | None:
    try:
        return int(value) if value is not None else None
    except (TypeError, ValueError):
        return None


class _PreformattedTextParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.depth = 0
        self.parts: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag.casefold() == "pre":
            self.depth += 1

    def handle_endtag(self, tag: str) -> None:
        if tag.casefold() == "pre" and self.depth:
            self.depth -= 1

    def handle_data(self, data: str) -> None:
        if self.depth:
            self.parts.append(data)


def _extract_log_text(payload: str) -> str:
    if "<pre" not in payload.casefold():
        return payload
    parser = _PreformattedTextParser()
    parser.feed(payload)
    return "\n".join(part.strip("\n") for part in parser.parts if part.strip("\n"))


def _redact_log_text(payload: str) -> str:
    value = payload.replace("\x00", "")
    value = SECRET_PATTERN.sub(lambda match: f"{match.group(1)}=<redacted>", value)
    value = URI_USERINFO_PATTERN.sub(r"\1<redacted>@", value)
    return BEARER_PATTERN.sub(r"\1<redacted>", value)
