"""Hive on MapReduce 运行事实聚合服务。"""

from __future__ import annotations

import re
import time
from collections import Counter
from typing import Any

from sql_agent_mcp_server.common.errors import McpDomainError, McpErrorCode
from sql_agent_mcp_server.common.metrics import distribution, safe_delta
from sql_agent_mcp_server.domains.hadoop_runtime.adapters import (
    JobHistoryAdapter,
    WebHdfsAdapter,
    YarnResourceManagerAdapter,
    application_to_job_id,
    job_to_application_id,
)
from sql_agent_mcp_server.domains.hadoop_runtime.schemas import (
    AggregatedLogsRequest,
    AggregatedLogsResponse,
    DependencyHealthResponse,
    JobCompareRequest,
    JobCompareResponse,
    JobDiagnosticsRequest,
    JobDiagnosticsResponse,
    JobSearchRequest,
    JobSearchResponse,
    StorageLayoutRequest,
    StorageLayoutResponse,
    TableFreshnessRequest,
    TableFreshnessResponse,
    YarnDiagnosticsRequest,
    YarnDiagnosticsResponse,
)
from sql_agent_mcp_server.domains.hive_execution.service import HiveExecutionService
from sql_agent_mcp_server.domains.hive_metadata.service import HiveMetadataService
from sql_agent_mcp_server.settings import Settings

COUNTER_SUFFIXES = (
    "HDFS_BYTES_READ", "HDFS_BYTES_WRITTEN", "FILE_BYTES_READ", "FILE_BYTES_WRITTEN",
    "MAP_INPUT_RECORDS", "MAP_OUTPUT_RECORDS", "REDUCE_INPUT_RECORDS", "REDUCE_OUTPUT_RECORDS",
    "REDUCE_SHUFFLE_BYTES", "SPILLED_RECORDS", "CPU_MILLISECONDS", "GC_TIME_MILLIS",
    "PHYSICAL_MEMORY_BYTES", "VIRTUAL_MEMORY_BYTES", "COMMITTED_HEAP_BYTES",
    "FAILED_SHUFFLE", "MERGED_MAP_OUTPUTS",
)
LOG_HIGHLIGHT_PATTERN = re.compile(
    r"(?i)(error|fatal|exception|caused by|outofmemory|container.*(?:killed|failed)|fetchfailed|"
    r"disk.*(?:full|error)|permission denied|timed? out|connection refused)"
)
MAX_AGGREGATED_LOG_RESPONSE_CHARS = 200_000


class HadoopRuntimeService:
    def __init__(
        self,
        webhdfs: WebHdfsAdapter,
        yarn: YarnResourceManagerAdapter,
        job_history: JobHistoryAdapter,
        metadata: HiveMetadataService,
        hive_execution: HiveExecutionService,
    ) -> None:
        self.webhdfs = webhdfs
        self.yarn = yarn
        self.job_history = job_history
        self.metadata = metadata
        self.hive_execution = hive_execution

    @classmethod
    def from_settings(
        cls,
        settings: Settings,
        metadata: HiveMetadataService,
        hive_execution: HiveExecutionService,
    ) -> "HadoopRuntimeService":
        return cls(
            WebHdfsAdapter(
                settings.webhdfs_urls,
                timeout_seconds=settings.mcp_tool_timeout_seconds,
                http_user=settings.hadoop_http_user,
            ),
            YarnResourceManagerAdapter(
                settings.yarn_resource_manager_urls,
                timeout_seconds=settings.mcp_tool_timeout_seconds,
                http_user=settings.hadoop_http_user,
            ),
            JobHistoryAdapter(
                settings.mapreduce_job_history_urls,
                timeout_seconds=settings.mcp_tool_timeout_seconds,
                http_user=settings.hadoop_http_user,
            ),
            metadata,
            hive_execution,
        )

    def dependency_health(self) -> DependencyHealthResponse:
        dependencies: list[dict] = []
        checks = (
            ("hiveServer2", lambda: {"compilationMs": self.hive_execution.adapter.health()}),
            ("hiveMetastore", lambda: {"databaseCount": len(self.metadata.adapter.list_databases(catalog="hive"))}),
            ("webHdfs", self.webhdfs.health),
            ("yarnResourceManager", self.yarn.health),
            ("mapReduceJobHistory", self.job_history.health),
        )
        for name, check in checks:
            started_at = time.perf_counter()
            try:
                details = check()
                dependencies.append(
                    {"name": name, "configured": True, "reachable": True,
                     "latencyMs": int((time.perf_counter() - started_at) * 1000), "details": details}
                )
            except McpDomainError as exc:
                dependencies.append(
                    {"name": name, "configured": exc.details.get("required") is None,
                     "reachable": False, "errorCode": exc.code, "message": exc.message}
                )
        incomplete = [item["name"] for item in dependencies if not item["reachable"]]
        return DependencyHealthResponse(
            source="sql-agent-platform",
            dependencies=dependencies,
            complete=not incomplete,
            missingReasons=[f"{name}_unavailable" for name in incomplete],
        )

    def storage_layout(self, request: StorageLayoutRequest) -> StorageLayoutResponse:
        table, locations = self.metadata.adapter.get_storage_locations(
            catalog=request.catalog,
            db=request.db,
            table=request.table,
            partitions=request.partitions,
        )
        paths, sizes, complete, missing_reasons = self.webhdfs.inspect_paths(
            locations, max_files=request.max_files
        )
        summary = {
            "lengthBytes": sum(int(item.get("length") or 0) for item in paths),
            "spaceConsumedBytes": sum(int(item.get("spaceConsumed") or 0) for item in paths),
            "fileCount": sum(int(item.get("fileCount") or 0) for item in paths),
            "directoryCount": sum(int(item.get("directoryCount") or 0) for item in paths),
            "sampledFileCount": len(sizes),
        }
        warnings = [] if complete else ["文件大小分布基于有上限的目录遍历，汇总大小和文件数仍来自 HDFS ContentSummary。"]
        return StorageLayoutResponse(
            source=self.webhdfs.source,
            table={
                "catalog": request.catalog,
                "db": request.db,
                "table": request.table,
                "inputFormat": table.input_format,
                "outputFormat": table.output_format,
                "serdeClass": table.serde_class,
            },
            paths=paths,
            summary=summary,
            fileSizeDistribution=distribution(sizes),
            complete=complete,
            missingReasons=missing_reasons,
            warnings=warnings,
        )

    def table_freshness(self, request: TableFreshnessRequest) -> TableFreshnessResponse:
        table = self.metadata.adapter.get_table(
            catalog=request.catalog, db=request.db, table=request.table
        )
        partition_keys = [column.name for column in table.columns or [] if column.partition_key]
        inspected_partitions = []
        total_partitions = 0
        scan_truncated = False

        if partition_keys:
            first_page, total_partitions = self.metadata.adapter.get_partitions(
                catalog=request.catalog,
                db=request.db,
                table=request.table,
                limit=request.partition_scan_limit,
                offset=0,
            )
            inspected_partitions = first_page
            if total_partitions > request.partition_scan_limit:
                scan_truncated = True

        ordered_partitions = sorted(
            inspected_partitions,
            key=lambda item: tuple(str(item.values.get(key, "")) for key in partition_keys),
            reverse=True,
        )
        sampled_partitions = ordered_partitions[: request.path_sample_limit]
        locations = [item.location for item in sampled_partitions if item.location]
        if not partition_keys and table.location:
            locations = [table.location]

        statuses: list[dict[str, Any]] = []
        missing_reasons: list[str] = []
        unavailable_path_count = 0
        for location in locations:
            try:
                statuses.append(self.webhdfs.get_path_status(location))
            except McpDomainError:
                unavailable_path_count += 1
        if unavailable_path_count:
            missing_reasons.append(f"storage_paths_unavailable:{unavailable_path_count}")

        latest_mtime = max(
            (int(item["modificationTime"]) for item in statuses if item.get("modificationTime") is not None),
            default=None,
        )
        now_ms = int(time.time() * 1000)
        metadata_ddl_time = (table.tbl_properties or {}).get("transient_lastDdlTime")
        warnings: list[str] = [
            "HDFS modificationTime 是存储路径时间，不等同于业务数据时间或 SLA 达标结论。"
        ]
        if partition_keys:
            warnings.append("候选最新分区按 Metastore 返回范围内的分区值字典序选取，请结合分区语义判断。")
        if scan_truncated:
            missing_reasons.append("partition_scan_truncated")
            warnings.append("分区数量超过扫描上限，仅检查 Metastore 返回的首个有界分区范围。")
        if unavailable_path_count:
            warnings.append(f"{unavailable_path_count} 个 Metastore 分区路径在 WebHDFS 中不可用。")
        if not locations:
            missing_reasons.append("storage_location_missing")

        return TableFreshnessResponse(
            source=f"{self.metadata.adapter.source}+{self.webhdfs.source}",
            table={
                "catalog": request.catalog,
                "db": request.db,
                "table": request.table,
                "partitioned": bool(partition_keys),
                "partitionKeys": partition_keys,
                "metadataLastDdlEpochSeconds": int(metadata_ddl_time) if str(metadata_ddl_time or "").isdigit() else None,
            },
            partitionSummary={
                "totalPartitions": total_partitions,
                "scannedPartitionCount": len(inspected_partitions),
                "scanLimit": request.partition_scan_limit,
                "candidatePathCount": len(locations),
                "pathSampleCount": len(statuses),
                "unavailablePathCount": unavailable_path_count,
                "candidateLatestPartition": sampled_partitions[0].model_dump(by_alias=True) if sampled_partitions else None,
            },
            storagePaths=statuses,
            latestStorageModificationTime=latest_mtime,
            storageAgeSeconds=max(0, (now_ms - latest_mtime) // 1000) if latest_mtime is not None else None,
            complete=not missing_reasons,
            missingReasons=missing_reasons,
            warnings=warnings,
        )

    def yarn_diagnostics(self, request: YarnDiagnosticsRequest) -> YarnDiagnosticsResponse:
        applications = [
            self.yarn.get_application(
                application_id,
                include_attempts=request.include_attempts,
                include_containers=request.include_containers,
            )
            for application_id in request.application_ids
        ]
        retained = all(
            not any("containerWarning" in attempt for attempt in app.get("attempts", []))
            for app in applications
        )
        return YarnDiagnosticsResponse(
            source=self.yarn.source,
            applications=applications,
            complete=retained,
            missingReasons=[] if retained else ["containers_not_retained"],
            warnings=[] if retained else ["部分已完成 Application 的 Container 明细已从 ResourceManager 过期。"],
        )

    def aggregated_logs(self, request: AggregatedLogsRequest) -> AggregatedLogsResponse:
        jobs: list[dict[str, Any]] = []
        highlights: list[str] = []
        missing_reasons: list[str] = []
        remaining_chars = MAX_AGGREGATED_LOG_RESPONSE_CHARS
        for job_id in request.job_ids:
            application_id = job_to_application_id(job_id)
            job: dict[str, Any] = {"id": job_id}
            try:
                job = self.job_history.get_job(job_id)
            except McpDomainError as exc:
                if exc.code not in {McpErrorCode.NOT_FOUND, McpErrorCode.DEPENDENCY_UNAVAILABLE}:
                    raise
                missing_reasons.append(f"{job_id}:job_detail_unavailable")
            attempts: list[dict[str, Any]] = []
            try:
                attempts = self.job_history.get_job_attempts(job_id)
            except McpDomainError as exc:
                if exc.code not in {McpErrorCode.NOT_FOUND, McpErrorCode.DEPENDENCY_UNAVAILABLE}:
                    raise
            owner = str(job.get("user") or "")
            if not attempts:
                try:
                    application = self.yarn.get_application(
                        application_id, include_attempts=True, include_containers=False
                    )
                    owner = owner or str(application.get("user") or "")
                    attempts = application.get("attempts", []) or []
                except McpDomainError as exc:
                    if exc.code not in {McpErrorCode.NOT_FOUND, McpErrorCode.DEPENDENCY_UNAVAILABLE}:
                        raise
            attempt_logs: list[dict[str, Any]] = []
            if not attempts:
                missing_reasons.append(f"{job_id}:job_attempts_unavailable")
            for attempt in attempts[:3]:
                logs_link = str(attempt.get("logsLink") or "")
                entries: list[dict[str, Any]] = []
                for log_type in request.log_types:
                    if remaining_chars <= 0:
                        missing_reasons.append("aggregated_log_response_budget_reached")
                        break
                    try:
                        max_chars = min(request.max_chars_per_log, remaining_chars)
                        if logs_link:
                            content, truncated = self.job_history.get_aggregated_log(
                                logs_link,
                                log_type=log_type,
                                max_chars=max_chars,
                            )
                        else:
                            node_id = str(attempt.get("nodeId") or attempt.get("assignedNodeId") or "")
                            container_id = str(attempt.get("containerId") or "")
                            if not node_id or not container_id or not owner:
                                missing_reasons.append(f"{job_id}:aggregated_log_coordinates_unavailable")
                                continue
                            content, truncated = self.job_history.get_aggregated_log_for_container(
                                job_id=job_id,
                                node_id=node_id,
                                container_id=container_id,
                                owner=owner,
                                log_type=log_type,
                                max_chars=max_chars,
                            )
                    except McpDomainError as exc:
                        if exc.code not in {McpErrorCode.NOT_FOUND, McpErrorCode.DEPENDENCY_UNAVAILABLE}:
                            raise
                        missing_reasons.append(f"{job_id}:{log_type}_unavailable")
                        continue
                    tail = _tail_lines(content, request.tail_lines)
                    remaining_chars -= len(tail)
                    entries.append({
                        "logType": log_type,
                        "content": tail,
                        "lineCount": len(tail.splitlines()),
                        "truncated": truncated or tail != content,
                    })
                    highlights.extend(_log_highlights(tail))
                attempt_logs.append({
                    "attemptId": attempt.get("id"),
                    "containerId": attempt.get("containerId"),
                    "startTime": attempt.get("startTime"),
                    "logs": entries,
                })
            jobs.append({
                "jobId": job_id,
                "state": job.get("state"),
                "attempts": attempt_logs,
            })
        unique_missing = list(dict.fromkeys(missing_reasons))
        unique_highlights = list(dict.fromkeys(highlights))[:30]
        return AggregatedLogsResponse(
            source=self.job_history.source,
            jobs=jobs,
            highlights=unique_highlights,
            complete=not unique_missing,
            missingReasons=unique_missing,
            warnings=[] if not unique_missing else ["部分聚合日志未生成、已过期或未被 JobHistory 保留。"],
        )

    def search_jobs(self, request: JobSearchRequest) -> JobSearchResponse:
        if request.job_id or request.application_id:
            job_id = request.job_id or application_to_job_id(request.application_id or "")
            job = self.job_history.get_job(job_id)
            item = self._job_search_item(job, self.job_history.get_safe_configuration(job_id))
            return JobSearchResponse(source=self.job_history.source, items=[item], total=1)

        params = {
            "user": request.user,
            "queue": request.queue,
            "state": request.state,
            "startedTimeBegin": request.started_after,
            "startedTimeEnd": request.started_before,
            "finishedTimeBegin": request.finished_after,
            "finishedTimeEnd": request.finished_before,
            "limit": 50 if request.query_id else request.limit,
        }
        jobs = self.job_history.search_jobs(params)
        if request.name:
            needle = request.name.casefold()
            jobs = [job for job in jobs if needle in str(job.get("name") or "").casefold()]
        items: list[dict] = []
        for job in jobs:
            job_id = str(job.get("id") or "")
            configuration = self.job_history.get_safe_configuration(job_id) if request.query_id else {}
            if request.query_id and request.query_id not in {
                configuration.get("hive.query.id"), configuration.get("hive.exec.query.id")
            }:
                continue
            items.append(self._job_search_item(job, configuration))
            if len(items) >= request.limit:
                break
        complete = not request.query_id or len(jobs) < 50
        return JobSearchResponse(
            source=self.job_history.source,
            items=items,
            total=len(items),
            complete=complete,
            missingReasons=[] if complete else ["query_id_search_candidate_limit_reached"],
            warnings=[] if complete else ["queryId 搜索达到 50 个候选上限，请缩小时间范围。"],
        )

    def job_diagnostics(self, request: JobDiagnosticsRequest) -> JobDiagnosticsResponse:
        job_ids = list(dict.fromkeys([*request.job_ids, *(application_to_job_id(item) for item in request.application_ids)]))
        jobs: list[dict[str, Any]] = []
        for job_id in job_ids:
            try:
                jobs.append(self._job_profile(job_id, request.include_task_outliers, request.outlier_limit))
            except McpDomainError as exc:
                if exc.code not in {McpErrorCode.NOT_FOUND, McpErrorCode.DEPENDENCY_UNAVAILABLE}:
                    raise
                jobs.append(self._expired_job_profile(job_id))
        aggregate = self._aggregate_jobs(jobs)
        missing_reasons = sorted({reason for job in jobs for reason in job.get("missingReasons", [])})
        warnings: list[str] = []
        if "job_history_not_retained_or_unavailable" in missing_reasons:
            warnings.append("部分 Job 的 JobHistory 详情已过期或不可用，仅保留运行标识及仍可取得的 YARN 事实。")
        if "yarn_application_not_retained_or_unavailable" in missing_reasons:
            warnings.append("部分 YARN Application 明细已过期或不可用，仍保留可取得的 JobHistory 事实。")
        return JobDiagnosticsResponse(
            source=self.job_history.source,
            jobs=jobs,
            aggregate=aggregate,
            complete=not missing_reasons,
            missingReasons=missing_reasons,
            warnings=warnings,
        )

    def _expired_job_profile(self, job_id: str) -> dict[str, Any]:
        application_id = job_to_application_id(job_id)
        missing_reasons = ["job_history_not_retained_or_unavailable"]
        yarn_application = None
        try:
            yarn_application = self.yarn.get_application(
                application_id, include_attempts=True, include_containers=False
            )
        except McpDomainError as exc:
            if exc.code not in {McpErrorCode.NOT_FOUND, McpErrorCode.DEPENDENCY_UNAVAILABLE}:
                raise
            missing_reasons.append("yarn_application_not_retained_or_unavailable")
        return {
            "job": {"id": job_id, "applicationId": application_id},
            "query": {},
            "configuration": {},
            "counters": {},
            "tasks": _task_summary([]),
            "taskOutliers": [],
            "yarnApplication": yarn_application,
            "missingReasons": missing_reasons,
        }

    def compare_jobs(self, request: JobCompareRequest) -> JobCompareResponse:
        baseline = self.job_diagnostics(JobDiagnosticsRequest(jobIds=request.baseline_job_ids, includeTaskOutliers=False))
        candidate = self.job_diagnostics(JobDiagnosticsRequest(jobIds=request.candidate_job_ids, includeTaskOutliers=False))
        incompatibilities = self._comparison_incompatibilities(baseline.jobs, candidate.jobs)
        metric_names = set(baseline.aggregate.get("metrics", {})) | set(candidate.aggregate.get("metrics", {}))
        differences = {
            name: safe_delta(
                baseline.aggregate.get("metrics", {}).get(name),
                candidate.aggregate.get("metrics", {}).get(name),
            )
            for name in sorted(metric_names)
        }
        missing_reasons = list(dict.fromkeys([*baseline.missing_reasons, *candidate.missing_reasons]))
        return JobCompareResponse(
            source=self.job_history.source,
            comparable=not incompatibilities,
            incompatibilities=incompatibilities,
            baseline=baseline.aggregate,
            candidate=candidate.aggregate,
            differences=differences,
            complete=not missing_reasons,
            missingReasons=missing_reasons,
        )

    def _job_profile(self, job_id: str, include_outliers: bool, outlier_limit: int) -> dict[str, Any]:
        job = self.job_history.get_job(job_id)
        missing_reasons: list[str] = []
        try:
            counters = _selected_counters(self.job_history.get_counters(job_id))
        except McpDomainError:
            counters = {}
            missing_reasons.append("job_counters_unavailable")
        try:
            tasks = self.job_history.get_tasks(job_id)
        except McpDomainError:
            tasks = []
            missing_reasons.append("job_tasks_unavailable")
        try:
            configuration = self.job_history.get_safe_configuration(job_id)
        except McpDomainError:
            configuration = {}
            missing_reasons.append("job_configuration_unavailable")
        task_summary = _task_summary(tasks)
        outliers: list[dict] = []
        if include_outliers:
            selected_tasks = sorted(
                tasks,
                key=lambda item: (str(item.get("state")) != "SUCCEEDED", int(item.get("elapsedTime") or 0)),
                reverse=True,
            )[:outlier_limit]
            for task in selected_tasks:
                task_id = str(task.get("id") or "")
                try:
                    attempts = self.job_history.get_task_attempts(job_id, task_id)
                except McpDomainError:
                    attempts = []
                    missing_reasons.append("task_attempts_partial")
                outliers.append({
                    "task": _safe_task(task),
                    "attempts": [_safe_attempt(item) for item in attempts[:10]],
                })
        yarn_application = None
        application_id = job_to_application_id(job_id)
        try:
            yarn_application = self.yarn.get_application(
                application_id, include_attempts=True, include_containers=False
            )
        except McpDomainError as exc:
            if exc.code not in {McpErrorCode.NOT_FOUND, McpErrorCode.DEPENDENCY_UNAVAILABLE}:
                raise
            missing_reasons.append("yarn_application_not_retained_or_unavailable")
        return {
            "job": _safe_job(job, application_id),
            "query": {
                "queryId": configuration.get("hive.query.id") or configuration.get("hive.exec.query.id"),
                "database": configuration.get("hive.current.database"),
                "executionEngine": configuration.get("hive.execution.engine"),
                "fingerprints": configuration.get("fingerprints", {}),
            },
            "configuration": {key: value for key, value in configuration.items() if key != "fingerprints"},
            "counters": counters,
            "tasks": task_summary,
            "taskOutliers": outliers,
            "yarnApplication": yarn_application,
            "missingReasons": sorted(set(missing_reasons)),
        }

    @staticmethod
    def _job_search_item(job: dict[str, Any], configuration: dict[str, Any]) -> dict[str, Any]:
        job_id = str(job.get("id") or "")
        return {
            **_safe_job(job, job_to_application_id(job_id)),
            "queryId": configuration.get("hive.query.id") or configuration.get("hive.exec.query.id"),
        }

    @staticmethod
    def _aggregate_jobs(jobs: list[dict[str, Any]]) -> dict[str, Any]:
        counter_totals: Counter[str] = Counter()
        sum_job_duration_ms = 0
        starts: list[int] = []
        finishes: list[int] = []
        map_tasks = 0
        reduce_tasks = 0
        failed_tasks = 0
        for profile in jobs:
            counter_totals.update(profile.get("counters", {}))
            job = profile.get("job", {})
            sum_job_duration_ms += int(job.get("durationMs") or 0)
            if job.get("startTime"):
                starts.append(int(job["startTime"]))
            if job.get("finishTime"):
                finishes.append(int(job["finishTime"]))
            tasks = profile.get("tasks", {})
            map_tasks += int(tasks.get("map", {}).get("count") or 0)
            reduce_tasks += int(tasks.get("reduce", {}).get("count") or 0)
            failed_tasks += int(tasks.get("states", {}).get("FAILED") or 0)
        wall_clock_ms = max(finishes) - min(starts) if starts and finishes else None
        metrics = {"durationMs": wall_clock_ms, "sumJobDurationMs": sum_job_duration_ms,
                   "mapTaskCount": map_tasks,
                   "reduceTaskCount": reduce_tasks, "failedTaskCount": failed_tasks, **dict(counter_totals)}
        retained_jobs = sum(1 for profile in jobs if profile.get("job", {}).get("state"))
        return {
            "jobCount": len(jobs),
            "retainedJobCount": retained_jobs,
            "expiredJobCount": len(jobs) - retained_jobs,
            "metrics": metrics,
        }

    @staticmethod
    def _comparison_incompatibilities(baseline: list[dict], candidate: list[dict]) -> list[str]:
        reasons: list[str] = []
        baseline_queries = {item.get("query", {}).get("queryId") for item in baseline} - {None}
        candidate_queries = {item.get("query", {}).get("queryId") for item in candidate} - {None}
        if baseline_queries and candidate_queries and baseline_queries != candidate_queries:
            reasons.append("query_id_mismatch")
        baseline_users = {item.get("job", {}).get("user") for item in baseline}
        candidate_users = {item.get("job", {}).get("user") for item in candidate}
        if baseline_users != candidate_users:
            reasons.append("submitter_mismatch")
        baseline_queues = {item.get("job", {}).get("queue") for item in baseline}
        candidate_queues = {item.get("job", {}).get("queue") for item in candidate}
        if baseline_queues != candidate_queues:
            reasons.append("queue_mismatch")
        baseline_inputs = {item.get("query", {}).get("fingerprints", {}).get("mapred.input.dir")
                           or item.get("query", {}).get("fingerprints", {}).get("mapreduce.input.fileinputformat.inputdir")
                           for item in baseline} - {None}
        candidate_inputs = {item.get("query", {}).get("fingerprints", {}).get("mapred.input.dir")
                            or item.get("query", {}).get("fingerprints", {}).get("mapreduce.input.fileinputformat.inputdir")
                            for item in candidate} - {None}
        if baseline_inputs and candidate_inputs and baseline_inputs != candidate_inputs:
            reasons.append("input_range_fingerprint_mismatch")
        return reasons


def _selected_counters(counters: dict[str, int]) -> dict[str, int]:
    selected: Counter[str] = Counter()
    for full_name, value in counters.items():
        for suffix in COUNTER_SUFFIXES:
            if full_name.endswith("." + suffix):
                selected[suffix] += value
                break
    return dict(selected)


def _task_summary(tasks: list[dict[str, Any]]) -> dict[str, Any]:
    maps = [item for item in tasks if str(item.get("type") or "").upper() == "MAP"]
    reduces = [item for item in tasks if str(item.get("type") or "").upper() == "REDUCE"]
    return {
        "map": {"count": len(maps), "durationMs": distribution(item.get("elapsedTime") for item in maps)},
        "reduce": {"count": len(reduces), "durationMs": distribution(item.get("elapsedTime") for item in reduces)},
        "states": dict(Counter(str(item.get("state") or "UNKNOWN") for item in tasks)),
    }


def _safe_job(job: dict[str, Any], application_id: str) -> dict[str, Any]:
    start = int(job.get("startTime") or 0)
    finish = int(job.get("finishTime") or 0)
    keys = ("id", "name", "queue", "user", "state", "diagnostics", "submitTime", "startTime", "finishTime",
            "mapsTotal", "mapsCompleted", "reducesTotal", "reducesCompleted", "uberized", "avgMapTime",
            "avgReduceTime", "avgShuffleTime", "avgMergeTime", "failedMapAttempts", "killedMapAttempts",
            "successfulMapAttempts", "failedReduceAttempts", "killedReduceAttempts", "successfulReduceAttempts")
    result = {key: job.get(key) for key in keys if key in job}
    result["applicationId"] = application_id
    result["durationMs"] = finish - start if finish and start else None
    submit = int(job.get("submitTime") or 0)
    result["queueWaitMs"] = start - submit if start and submit else None
    return result


def _safe_task(task: dict[str, Any]) -> dict[str, Any]:
    keys = ("id", "type", "state", "startTime", "finishTime", "elapsedTime", "successfulAttempt", "status")
    return {key: task.get(key) for key in keys if key in task}


def _safe_attempt(attempt: dict[str, Any]) -> dict[str, Any]:
    keys = ("id", "type", "state", "startTime", "finishTime", "elapsedTime", "rack", "nodeHttpAddress",
            "assignedContainerId", "diagnostics")
    return {key: attempt.get(key) for key in keys if key in attempt}


def _tail_lines(content: str, limit: int) -> str:
    return "\n".join(content.splitlines()[-limit:])


def _log_highlights(content: str) -> list[str]:
    highlights: list[str] = []
    for line in content.splitlines():
        compact = " ".join(line.split())
        if compact and LOG_HIGHLIGHT_PATTERN.search(compact):
            highlights.append(compact[:1000])
        if len(highlights) >= 20:
            break
    return highlights
