#!/usr/bin/env python3
"""同步任务预览/创建性能回归；只创建元数据任务，不调试、不启动 Flink。"""

from __future__ import annotations

import argparse
import json
import math
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from http.cookiejar import CookieJar
from pathlib import Path
from typing import Any
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import HTTPCookieProcessor, Request, build_opener


class ApiClient:
    def __init__(self, base_url: str, timeout: int) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.opener = build_opener(HTTPCookieProcessor(CookieJar()))

    def api(self, path: str, *, method: str = "GET", payload: dict[str, Any] | None = None) -> Any:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload is not None else None
        headers = {"Content-Type": "application/json; charset=UTF-8"} if body is not None else {}
        request = Request(f"{self.base_url}{path}", data=body, headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=self.timeout) as response:
                parsed = json.loads(response.read().decode("utf-8"))
        except HTTPError as exc:
            response_body = exc.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"{method} {path} HTTP {exc.code}: {response_body[:1000]}") from exc
        if parsed.get("code") != 0:
            raise RuntimeError(f"{method} {path} 失败：{parsed}")
        return parsed.get("data")

    def timed_api(self, path: str, *, method: str, payload: dict[str, Any]) -> tuple[Any, float]:
        started_at = time.perf_counter()
        result = self.api(path, method=method, payload=payload)
        return result, round((time.perf_counter() - started_at) * 1000, 3)


def parser() -> argparse.ArgumentParser:
    value = argparse.ArgumentParser(description="同步任务 20/40/100/200 表预览与创建性能测试")
    value.add_argument("--base-url", default="http://127.0.0.1:8392")
    value.add_argument("--ob-id", default="1")
    value.add_argument("--server-id", type=int)
    value.add_argument("--server-name-contains", default="数开")
    value.add_argument("--counts", default="20,40,100,200")
    value.add_argument("--rounds", type=int, default=5)
    value.add_argument("--timeout", type=int, default=180)
    value.add_argument("--report")
    value.add_argument("--skip-login", action="store_true", help="隔离测试 Harness 使用固定测试身份时跳过登录")
    return value


def choose_server(client: ApiClient, server_id: int | None, name_fragment: str) -> dict[str, Any]:
    servers = client.api("/api/servers")
    if server_id is not None:
        matches = [item for item in servers if int(item["id"]) == server_id]
    else:
        matches = [item for item in servers if name_fragment.lower() in str(item.get("name", "")).lower()]
    if not matches:
        visible = [{"id": item.get("id"), "name": item.get("name"), "database": item.get("database")}
                   for item in servers]
        raise RuntimeError(f"未找到指定数开 Server；当前可选 Server：{visible}")
    if len(matches) > 1:
        raise RuntimeError(f"匹配到多个 Server，请使用 --server-id：{[(item['id'], item['name']) for item in matches]}")
    return matches[0]


def source_tables(client: ApiClient, server: dict[str, Any]) -> list[str]:
    query = urlencode({"sourceServerId": server["id"], "database": server["database"]})
    options = client.api(f"/api/tasks/sync/source-tables?{query}")
    return [str(item["tableName"]) for item in options if not item.get("occupied")]


def eligible_source_tables(client: ApiClient, server: dict[str, Any], candidates: list[str],
                           required: int, workers: int = 6) -> tuple[list[str], int, int]:
    """筛选具备主键的源表；仅属于测试数据准备，不计入预览和创建耗时。"""
    eligible: list[str] = []
    checked = 0
    excluded = 0

    def has_primary_key(table: str) -> bool:
        query = urlencode({"database": server["database"], "table": table})
        schema = client.api(f"/api/servers/{server['id']}/mysql/table-schema?{query}")
        return bool(schema.get("primaryKeys"))

    batch_size = max(workers * 4, 24)
    for offset in range(0, len(candidates), batch_size):
        batch = candidates[offset:offset + batch_size]
        with ThreadPoolExecutor(max_workers=workers) as executor:
            accepted = list(executor.map(has_primary_key, batch))
        checked += len(batch)
        for table, accepted_table in zip(batch, accepted):
            if accepted_table:
                eligible.append(table)
            else:
                excluded += 1
        if len(eligible) >= required:
            break
    return eligible, checked, excluded


def request_payload(server: dict[str, Any], target_database: str, tables: list[str],
                    owner: str, marker: str) -> dict[str, Any]:
    domain = "p" + marker.lower().replace("_", "")[-16:]
    return {
        "taskType": "sync",
        "name": f"PERF-SYNC-{marker}",
        "owner": owner,
        "description": f"隔离性能测试：{len(tables)} 张表；仅创建后删除，不启动",
        "flinkVersion": "2.2.1",
        "alarmConfig": {"alarmType": "task_failure", "alarmGroup": "性能测试"},
        "flinkConf": {
            "parallelism": 4,
            "checkpointIntervalSeconds": 60,
            "taskManagerMemoryGb": 5,
            "jobManagerMemoryGb": 1,
            "flinkConfOverrides": {"taskmanager.numberOfTaskSlots": "4"},
        },
        "taskConfig": {
            "sourceServerId": int(server["id"]),
            "sourceType": "mysql-cdc",
            "cdcConfig": {
                "databaseName": server["database"],
                "selectedTables": tables,
                "targetDatabase": target_database,
                "domainPrefix": domain,
                "metadataColumns": ["database_name", "table_name", "op_ts"],
                "typeMappings": ["to-nullable", "tinyint1-not-bool"],
                "mode": "combined",
                "ignoreIncompatible": False,
                "tableConfigs": {},
                "mysqlConfOverrides": {},
                "tableConfOverrides": {"bucket": "4", "sink.parallelism": "4"},
            },
        },
    }


def percentile95(values: list[float]) -> float:
    return sorted(values)[max(0, math.ceil(len(values) * 0.95) - 1)]


def benchmark(client: ApiClient, server: dict[str, Any], target_database: str,
              available_tables: list[str], counts: list[int], rounds: int, owner: str,
              discovery: dict[str, int]) -> dict[str, Any]:
    report: dict[str, Any] = {
        "generatedAt": datetime.now().isoformat(timespec="seconds"),
        "server": {"id": server["id"], "name": server["name"], "database": server["database"]},
        "targetDatabase": target_database,
        "availableUnoccupiedTables": len(available_tables),
        "tableDiscovery": discovery,
        "rounds": rounds,
        "results": [],
    }
    for count in counts:
        if count > len(available_tables):
            report["results"].append({"tableCount": count, "status": "skipped", "reason": "可用未占用表不足"})
            continue
        measurements: list[dict[str, Any]] = []
        for round_no in range(1, rounds + 1):
            marker = f"{count}_{round_no}_{uuid.uuid4().hex[:6]}"
            payload = request_payload(server, target_database, available_tables[:count], owner, marker)
            task_id: int | None = None
            try:
                _, preview_ms = client.timed_api(
                    "/v1/api/tasks/command-preview", method="POST", payload=payload)
                created, create_ms = client.timed_api(
                    "/v1/api/tasks/create", method="POST", payload=payload)
                task_id = int(created)
                detail = client.api(f"/v1/api/tasks/{task_id}/detail")
                selected = detail["taskConfig"]["cdcConfig"]["selectedTables"]
                if len(selected) != count:
                    raise RuntimeError(f"任务 {task_id} 保存表数错误：期望 {count}，实际 {len(selected)}")
                instances = client.api(f"/v1/api/tasks/{task_id}/instances?executionMode=PRODUCTION")
                if instances:
                    raise RuntimeError(f"性能测试任务 {task_id} 意外产生运行实例")
                measurements.append({"round": round_no, "taskId": task_id,
                                     "previewMs": preview_ms, "createMs": create_ms})
            finally:
                if task_id is not None:
                    client.api(f"/v1/api/tasks/{task_id}/delete", method="POST")
        preview_values = [item["previewMs"] for item in measurements]
        create_values = [item["createMs"] for item in measurements]
        report["results"].append({
            "tableCount": count,
            "status": "passed",
            "measurements": measurements,
            "preview": {"minMs": min(preview_values), "maxMs": max(preview_values),
                        "avgMs": round(sum(preview_values) / len(preview_values), 3),
                        "p95Ms": percentile95(preview_values)},
            "create": {"minMs": min(create_values), "maxMs": max(create_values),
                       "avgMs": round(sum(create_values) / len(create_values), 3),
                       "p95Ms": percentile95(create_values)},
        })
    return report


def main() -> None:
    args = parser().parse_args()
    counts = [int(item.strip()) for item in args.counts.split(",") if item.strip()]
    if not counts or min(counts) <= 0 or args.rounds <= 0:
        raise SystemExit("counts 和 rounds 必须为正整数")
    client = ApiClient(args.base_url, args.timeout)
    if not args.skip_login:
        client.api("/api/auth/login", method="POST", payload={"obId": args.ob_id})
    server = choose_server(client, args.server_id, args.server_name_contains)
    candidates = source_tables(client, server)
    tables, checked, excluded = eligible_source_tables(client, server, candidates, max(counts))
    cdc_options = client.api("/api/paimon/cdc-options")
    target_database = str(cdc_options.get("targetDatabase", "")).strip()
    if not target_database:
        raise RuntimeError("未配置 Paimon 目标数据库")
    result = benchmark(
        client,
        server,
        target_database,
        tables,
        counts,
        args.rounds,
        args.ob_id,
        {"unoccupiedCandidates": len(candidates), "checked": checked,
         "excludedWithoutPrimaryKey": excluded, "eligible": len(tables)},
    )
    output = json.dumps(result, ensure_ascii=False, indent=2)
    print(output)
    if args.report:
        report_path = Path(args.report)
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(output + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
