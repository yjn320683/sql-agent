#!/usr/bin/env python3
"""SQL Agent 真实环境全链路测试：任务、执行、版本、生效、取消和验数。"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import uuid
from contextlib import closing
from datetime import datetime
from http.cookiejar import CookieJar
from pathlib import Path
from typing import Any
from urllib.error import HTTPError
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit
from urllib.request import HTTPCookieProcessor, Request, build_opener


ROOT = Path(__file__).resolve().parents[1]
AGENT_DIR = ROOT / "agent"
TERMINAL_EXECUTION = {"SUCCEEDED", "FAILED", "CANCELLED"}
TERMINAL_COMPARE = {"PASSED", "NOT_PASSED", "FORCE_PASSED", "FAILED", "CANCELLED"}
SAFE_IDENTIFIER = re.compile(r"^[a-z][a-z0-9_]{0,127}$")


class ApiClient:
    def __init__(self, base_url: str, timeout: int) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.opener = build_opener(HTTPCookieProcessor(CookieJar()))

    def raw(self, path: str, *, method: str = "GET", payload: dict | None = None):
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload is not None else None
        headers = {"Content-Type": "application/json"} if data is not None else {}
        return self.opener.open(
            Request(f"{self.base_url}{path}", data=data, headers=headers, method=method),
            timeout=self.timeout,
        )

    def api(self, path: str, *, method: str = "GET", payload: dict | None = None) -> Any:
        try:
            with self.raw(path, method=method, payload=payload) as response:
                body = response.read().decode("utf-8")
        except HTTPError as exc:
            body = exc.read().decode("utf-8", errors="replace")
            raise AssertionError(f"{method} {path} HTTP {exc.code}: {body[:1000]}") from exc
        parsed = json.loads(body)
        assert parsed.get("code") == 0, f"{method} {path} 失败：{parsed}"
        return parsed.get("data")

    def expect_error(self, path: str, *, method: str, payload: dict, contains: str) -> None:
        try:
            self.api(path, method=method, payload=payload)
        except AssertionError as exc:
            assert contains in str(exc), f"预期错误应包含 {contains!r}，实际为：{exc}"
            return
        raise AssertionError(f"{method} {path} 本应失败，但请求成功")


def load_agent_environment() -> str:
    sys.path.insert(0, str(AGENT_DIR))
    from dotenv import load_dotenv

    load_dotenv(AGENT_DIR / ".env", override=False)
    uri = os.environ.get("HIVE_SERVER2_URI", "").strip()
    assert uri, "agent/.env 未配置 HIVE_SERVER2_URI"
    return uri


def hive_uri_with_user(uri: str, user: str) -> str:
    """覆盖 Hive URI 的执行用户，避免重复的 user 参数产生身份歧义。"""
    parsed = urlsplit(uri)
    query = [(key, value) for key, value in parse_qsl(parsed.query, keep_blank_values=True) if key != "user"]
    query.append(("user", user))
    return urlunsplit((parsed.scheme, parsed.netloc, parsed.path, urlencode(query), parsed.fragment))


def hive_execute(uri: str, statements: list[str], timeout: int) -> None:
    from sql_agent_mcp_server.domains.hive_execution.adapter import HiveServer2Adapter

    adapter = HiveServer2Adapter(uri, timeout_seconds=timeout)
    connection = adapter._connect(adapter._connection_info("default"))
    with closing(connection):
        with closing(connection.cursor()) as cursor:
            for statement in statements:
                cursor.execute(statement)


def provision_hive(uri: str, database: str, temp_database: str, timeout: int) -> dict[str, str]:
    assert SAFE_IDENTIFIER.fullmatch(database), f"Hive 测试库名非法：{database}"
    assert SAFE_IDENTIFIER.fullmatch(temp_database), f"Hive 验数临时库名非法：{temp_database}"
    result_table = f"{database}.task_result"
    left_table = f"{database}.compare_left"
    right_table = f"{database}.compare_right"
    hive_execute(uri, [
        f"CREATE DATABASE IF NOT EXISTS `{database}`",
        f"CREATE DATABASE IF NOT EXISTS `{temp_database}`",
        f"CREATE TABLE IF NOT EXISTS `{database}`.`task_result` (id INT, label STRING) STORED AS TEXTFILE",
        f"CREATE TABLE IF NOT EXISTS `{database}`.`compare_left` (id INT, label STRING) STORED AS TEXTFILE",
        f"CREATE TABLE IF NOT EXISTS `{database}`.`compare_right` (id INT, label STRING) STORED AS TEXTFILE",
        f"INSERT OVERWRITE TABLE `{database}`.`compare_left` SELECT 1, 'same'",
        f"INSERT OVERWRITE TABLE `{database}`.`compare_right` SELECT 1, 'same'",
    ], timeout)
    return {"result": result_table, "left": left_table, "right": right_table}


def wait_execution(client: ApiClient, execution_id: int, timeout: int) -> dict:
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        last = client.api(f"/api/task-executions/{execution_id}")
        if last["status"] in TERMINAL_EXECUTION:
            return last
        time.sleep(1)
    raise AssertionError(f"执行实例 {execution_id} 超时，最后状态：{last}")


def wait_running(client: ApiClient, execution_id: int, timeout: int) -> dict:
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        last = client.api(f"/api/task-executions/{execution_id}")
        if last["status"] in {"QUEUED", "RUNNING"}:
            return last
        if last["status"] in TERMINAL_EXECUTION:
            raise AssertionError(f"执行实例 {execution_id} 未进入可取消状态：{last}")
        time.sleep(0.2)
    raise AssertionError(f"执行实例 {execution_id} 未及时进入运行状态：{last}")


def assert_execution_success(client: ApiClient, execution: dict) -> None:
    execution_id = int(execution["id"])
    completed = wait_execution(client, execution_id, client.timeout)
    assert completed["status"] == "SUCCEEDED", f"执行失败：{completed}"
    assert completed["steps"], f"执行 {execution_id} 没有 Step 记录"
    assert all(step["status"] == "SUCCEEDED" for step in completed["steps"]), completed
    wait_log(client, f"/api/task-executions/{execution_id}/logs", "所有Step执行成功")
    for step in completed["steps"]:
        wait_log(client, f"/api/task-executions/{execution_id}/steps/{step['stepNo']}/logs", "开始执行")


def wait_compare(client: ApiClient, compare_id: int, timeout: int) -> dict:
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        last = client.api(f"/api/data-compares/{compare_id}")
        if last["status"] in TERMINAL_COMPARE:
            return last
        time.sleep(1)
    raise AssertionError(f"验数任务 {compare_id} 超时，最后状态：{last}")


def wait_log(client: ApiClient, path: str, expected: str, timeout: int = 15) -> dict:
    """执行状态可能先于日志文件刷盘，有限重试避免把正常完成误判为无日志。"""
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        last = client.api(path)
        if expected in str(last.get("content") or ""):
            return last
        time.sleep(0.5)
    raise AssertionError(f"日志 {path} 未出现 {expected!r}，最后内容：{last}")


def run_version_compare(client: ApiClient, task_id: int, candidate_version_no: int) -> dict:
    """按准备、生成不可变计划、提交三段式接口完成一次版本验数。"""
    prepared = client.api(
        "/api/data-compares/prepare-version",
        method="POST",
        payload={"taskId": task_id, "candidateVersionNo": candidate_version_no},
    )
    assert prepared["baselineSteps"] and prepared["candidateSteps"], prepared
    generated = client.api(
        "/api/data-compares/generate-version-sql",
        method="POST",
        payload={
            "taskId": task_id,
            "candidateVersionNo": candidate_version_no,
            "baselineSteps": [],
            "candidateSteps": [],
        },
    )
    assert generated["planToken"] and generated["suggestedTables"], generated
    assert "CREATE TABLE" in generated["generatedBaselineSql"]
    assert "DROP" not in generated["generatedBaselineSql"].upper()
    assert "DROP" not in generated["generatedCandidateSql"].upper()
    compare = client.api(
        "/api/data-compares",
        method="POST",
        payload={
            "compareType": "VERSION",
            "planToken": generated["planToken"],
            "onlyCompareSameColumn": True,
            "tables": generated["suggestedTables"],
        },
    )
    compare = wait_compare(client, int(compare["id"]), client.timeout)
    assert compare["status"] == "PASSED", compare
    report = client.api(f"/api/data-compares/{compare['id']}/report?page=1&pageSize=20")
    assert report["tablePage"]["total"] >= 1, report
    assert all(row["display_status"] == "PASSED" for row in report["tablePage"]["items"]), report
    for row in report["tablePage"]["items"]:
        log = client.api(f"/api/data-compares/tables/{row['id']}/logs")
        assert str(log.get("content") or "").strip(), f"验数表 {row['id']} 没有日志"
    return compare


def task_payload(name: str, sql: str, ddl: str | None, owner: str = "900001") -> dict:
    return {
        "name": name,
        "description": "SQL Agent 自动化全链路测试任务",
        "taskType": "RUN_HIVE",
        "executionFrequency": "手动执行",
        "owner": owner,
        "sql": sql,
        "ddl": ddl,
        "parameters": [
            {
                "name": "test_id",
                "type": "INTEGER",
                "description": "自动化测试主键",
                "required": True,
                "defaultValue": 1,
            },
            {
                "name": "test_label",
                "type": "STRING",
                "description": "自动化测试文本",
                "required": True,
                "defaultValue": "same",
            },
        ],
    }


def control_plane_run(args: argparse.Namespace, client: ApiClient, prefix: str) -> dict:
    """在没有 Hive 建表权限时，验证不依赖输出表的任务控制面。"""
    sql_v1 = "SELECT ${test_id} AS id, ${test_label} AS label"
    task = client.api(
        "/api/tasks", method="POST", payload=task_payload(prefix + "-control", sql_v1, None, args.ob_id)
    )
    task_id = int(task["id"])
    assert task["taskType"] == "RUN_HIVE" and task["enabled"] is True
    assert task["executionFrequency"] == "手动执行" and task["owner"] == args.ob_id
    assert len(task["parameters"]) == 2

    execution = client.api(
        f"/api/tasks/{task_id}/executions",
        method="POST",
        payload={
            "revision": task["revision"],
            "parameters": {"test_id": 1, "test_label": "same"},
        },
    )
    assert_execution_success(client, execution)

    task = client.api(
        f"/api/tasks/{task_id}/disable", method="POST", payload={"revision": task["revision"]}
    )
    client.expect_error(
        f"/api/tasks/{task_id}/executions",
        method="POST",
        payload={"revision": task["revision"], "parameters": {"test_id": 1, "test_label": "same"}},
        contains="停用任务不能调试",
    )
    task = client.api(
        f"/api/tasks/{task_id}/enable", method="POST", payload={"revision": task["revision"]}
    )

    version1 = client.api(
        f"/api/tasks/{task_id}/versions",
        method="POST",
        payload={"note": "control baseline", "revision": task["revision"]},
    )
    version1 = client.api(
        f"/api/tasks/{task_id}/versions/{version1['versionNo']}",
        method="PATCH",
        payload={
            **task_payload(prefix + "-control-v1", sql_v1, None, args.ob_id),
            "note": "control baseline saved",
            "revision": version1["revision"],
        },
    )
    version1_execution = client.api(
        f"/api/tasks/{task_id}/executions",
        method="POST",
        payload={
            "versionNo": version1["versionNo"],
            "parameters": {"test_id": 1, "test_label": "same"},
        },
    )
    assert_execution_success(client, version1_execution)
    version1 = client.api(
        f"/api/tasks/{task_id}/versions/{version1['versionNo']}/activate",
        method="POST",
        payload={"taskRevision": task["revision"], "versionRevision": version1["revision"]},
    )
    task = client.api(f"/api/tasks/{task_id}")

    version2 = client.api(
        f"/api/tasks/{task_id}/versions",
        method="POST",
        payload={"note": "control candidate", "revision": task["revision"]},
    )
    sql_v2 = "SELECT CAST(${test_id} AS INT) AS id, concat(${test_label}, '') AS label"
    version2 = client.api(
        f"/api/tasks/{task_id}/versions/{version2['versionNo']}",
        method="PATCH",
        payload={
            **task_payload(prefix + "-control-v2", sql_v2, None, args.ob_id),
            "note": "control candidate saved",
            "revision": version2["revision"],
        },
    )
    version2_execution = client.api(
        f"/api/tasks/{task_id}/executions",
        method="POST",
        payload={
            "versionNo": version2["versionNo"],
            "parameters": {"test_id": 1, "test_label": "same"},
        },
    )
    assert_execution_success(client, version2_execution)
    version2 = client.api(
        f"/api/tasks/{task_id}/versions/{version2['versionNo']}/activate",
        method="POST",
        payload={"taskRevision": task["revision"], "versionRevision": version2["revision"]},
    )
    task = client.api(f"/api/tasks/{task_id}")
    assert task["effectiveVersionNo"] == version2["versionNo"]

    versions = client.api(f"/api/tasks/{task_id}/versions?page=1&pageSize=1&keyword=control")
    assert versions["total"] == 2 and len(versions["items"]) == 1

    cancel_task = client.api(
        "/api/tasks",
        method="POST",
        payload={
            "name": prefix + "-control-cancel",
            "description": "SQL Agent 自动化取消测试",
            "taskType": "RUN_HIVE",
            "executionFrequency": "手动执行",
            "owner": args.ob_id,
            "sql": "SELECT reflect('java.lang.Thread', 'sleep', 20000) AS slept",
            "ddl": None,
            "parameters": [],
        },
    )
    cancel_execution = client.api(
        f"/api/tasks/{cancel_task['id']}/executions",
        method="POST",
        payload={"revision": cancel_task["revision"], "parameters": {}},
    )
    wait_running(client, int(cancel_execution["id"]), 15)
    client.api(f"/api/task-executions/{cancel_execution['id']}/cancel", method="POST")
    cancelled = wait_execution(client, int(cancel_execution["id"]), 30)
    assert cancelled["status"] == "CANCELLED", cancelled
    cancel_log = client.api(f"/api/task-executions/{cancel_execution['id']}/logs")
    assert "Step" in cancel_log["content"], cancel_log

    center = client.api(f"/api/task-executions?page=1&pageSize=10&status=all&keyword={prefix}")
    assert center["total"] >= 4

    cancel_task = client.api(
        f"/api/tasks/{cancel_task['id']}/archive",
        method="POST",
        payload={"revision": cancel_task["revision"]},
    )
    assert cancel_task["archived"] is True

    task = client.api(
        f"/api/tasks/{task_id}/archive", method="POST", payload={"revision": task["revision"]}
    )
    assert task["archived"] is True
    return {
        "taskId": task_id,
        "effectiveExecutionId": int(execution["id"]),
        "version1ExecutionId": int(version1_execution["id"]),
        "version2ExecutionId": int(version2_execution["id"]),
        "cancelExecutionId": int(cancel_execution["id"]),
        "publishedVersion": int(version2["versionNo"]),
        "controlPlane": "passed",
    }


def run(args: argparse.Namespace) -> dict:
    suffix = datetime.now().strftime("%Y%m%d_%H%M%S") + "_" + uuid.uuid4().hex[:6]
    database = args.hive_database or f"sql_agent_e2e_{suffix}"
    prefix = f"E2E_{suffix}"
    client = ApiClient(args.base_url, args.timeout)

    with client.raw("/") as response:
        root = response.read().decode("utf-8")
    assert '<div id="root">' in root, "Backend 未提供前端静态资源"
    assert client.raw("/api/ready").read().decode("utf-8") == "ok"
    client.api("/api/auth/login", method="POST", payload={"obId": args.ob_id})
    health = client.api("/api/workspace/platform/health")
    assert health["ok"] is True and health["complete"] is True, health

    if args.control_plane_only:
        return {
            "front": "ok",
            "backend": "ok",
            "agent": "ok",
            **control_plane_run(args, client, prefix),
            "result": "passed",
        }

    hive_uri = hive_uri_with_user(load_agent_environment(), args.hive_user)
    tables = provision_hive(hive_uri, database, args.hive_temp_database, args.timeout)
    # 测试表由 provision_hive 显式准备；版本内容不携带 CREATE/DROP DDL。
    ddl = None
    initial_sql = (
        "====step:1:write-result====\n"
        f"INSERT OVERWRITE TABLE {tables['result']} "
        "SELECT ${test_id}, ${test_label}"
    )
    task = client.api("/api/tasks", method="POST", payload=task_payload(prefix, initial_sql, ddl, args.ob_id))
    task_id = int(task["id"])
    assert task["taskType"] == "RUN_HIVE"
    assert task["executionFrequency"] == "手动执行"
    assert task["owner"] == args.ob_id
    assert task["enabled"] is True and len(task["parameters"]) == 2

    listed = client.api(f"/api/tasks?page=1&pageSize=10&status=all&keyword={prefix}")
    assert listed["total"] == 1 and int(listed["items"][0]["id"]) == task_id

    effective_execution = client.api(
        f"/api/tasks/{task_id}/executions",
        method="POST",
        payload={
            "revision": task["revision"],
            "businessDate": "2026-08-27",
            "parameters": {"test_id": 1, "test_label": "same"},
        },
    )
    assert_execution_success(client, effective_execution)

    task = client.api(
        f"/api/tasks/{task_id}/disable", method="POST", payload={"revision": task["revision"]}
    )
    assert task["enabled"] is False
    client.expect_error(
        f"/api/tasks/{task_id}/executions",
        method="POST",
        payload={"revision": task["revision"], "parameters": {"test_id": 1, "test_label": "same"}},
        contains="停用任务不能调试",
    )
    task = client.api(
        f"/api/tasks/{task_id}/enable", method="POST", payload={"revision": task["revision"]}
    )
    assert task["enabled"] is True

    version1 = client.api(
        f"/api/tasks/{task_id}/versions",
        method="POST",
        payload={"note": "E2E baseline", "revision": task["revision"]},
    )
    version1 = client.api(
        f"/api/tasks/{task_id}/versions/{version1['versionNo']}",
        method="PATCH",
        payload={
            **task_payload(prefix + "-v1", initial_sql, ddl, args.ob_id),
            "note": "E2E baseline saved",
            "revision": version1["revision"],
        },
    )
    version1_execution = client.api(
        f"/api/tasks/{task_id}/executions",
        method="POST",
        payload={
            "versionNo": version1["versionNo"],
            "parameters": {"test_id": 1, "test_label": "same"},
        },
    )
    assert_execution_success(client, version1_execution)
    version1 = client.api(
        f"/api/tasks/{task_id}/versions/{version1['versionNo']}/activate",
        method="POST",
        payload={"taskRevision": task["revision"], "versionRevision": version1["revision"]},
    )
    task = client.api(f"/api/tasks/{task_id}")
    assert task["effectiveVersionNo"] == version1["versionNo"] and version1["status"] == "EFFECTIVE"

    version2 = client.api(
        f"/api/tasks/{task_id}/versions",
        method="POST",
        payload={"note": "E2E candidate", "revision": task["revision"]},
    )
    candidate_sql = (
        "====step:1:write-result====\n"
        f"INSERT OVERWRITE TABLE {tables['result']} "
        "SELECT CAST(${test_id} AS INT), concat(substr(${test_label}, 1, 2), substr(${test_label}, 3))"
    )
    version2 = client.api(
        f"/api/tasks/{task_id}/versions/{version2['versionNo']}",
        method="PATCH",
        payload={
            **task_payload(prefix + "-v2", candidate_sql, ddl, args.ob_id),
            "note": "E2E candidate saved",
            "revision": version2["revision"],
        },
    )
    version2_execution = client.api(
        f"/api/tasks/{task_id}/executions",
        method="POST",
        payload={
            "versionNo": version2["versionNo"],
            "parameters": {"test_id": 1, "test_label": "same"},
        },
    )
    assert_execution_success(client, version2_execution)

    versions = client.api(f"/api/tasks/{task_id}/versions?page=1&pageSize=1&keyword=E2E")
    assert versions["total"] == 2 and len(versions["items"]) == 1

    # 创建第二个独立成员，验证联合版本必须逐成员验数、统一发布。
    second_initial_sql = (
        "====step:1:write-result====\n"
        f"INSERT OVERWRITE TABLE {tables['left']} SELECT ${{test_id}}, ${{test_label}}"
    )
    second_task = client.api(
        "/api/tasks", method="POST",
        payload=task_payload(prefix + "-member2", second_initial_sql, None, args.ob_id),
    )
    second_v1 = client.api(
        f"/api/tasks/{second_task['id']}/versions", method="POST",
        payload={"note": "member2 baseline", "revision": second_task["revision"]},
    )
    second_v1 = client.api(
        f"/api/tasks/{second_task['id']}/versions/{second_v1['versionNo']}", method="PATCH",
        payload={
            **task_payload(prefix + "-member2-v1", second_initial_sql, None, args.ob_id),
            "note": "member2 baseline saved", "revision": second_v1["revision"],
        },
    )
    second_v1 = client.api(
        f"/api/tasks/{second_task['id']}/versions/{second_v1['versionNo']}/activate", method="POST",
        payload={"taskRevision": second_task["revision"], "versionRevision": second_v1["revision"]},
    )
    second_task = client.api(f"/api/tasks/{second_task['id']}")
    second_v2 = client.api(
        f"/api/tasks/{second_task['id']}/versions", method="POST",
        payload={"note": "member2 candidate", "revision": second_task["revision"]},
    )
    second_candidate_sql = (
        "====step:1:write-result====\n"
        f"INSERT OVERWRITE TABLE {tables['left']} "
        "SELECT CAST(${test_id} AS INT), concat(${test_label}, '')"
    )
    second_v2 = client.api(
        f"/api/tasks/{second_task['id']}/versions/{second_v2['versionNo']}", method="PATCH",
        payload={
            **task_payload(prefix + "-member2-v2", second_candidate_sql, None, args.ob_id),
            "note": "member2 candidate saved", "revision": second_v2["revision"],
        },
    )

    union = client.api(
        "/api/task-version-unions", method="POST",
        payload={
            "unionDdl": None,
            "members": [
                {"taskId": task_id, "versionNo": version2["versionNo"],
                 "versionRevision": version2["revision"], "ddl": None},
                {"taskId": second_task["id"], "versionNo": second_v2["versionNo"],
                 "versionRevision": second_v2["revision"], "ddl": None},
            ],
        },
    )
    union_id = int(union["id"])
    compare = run_version_compare(client, task_id, int(version2["versionNo"]))
    second_compare = run_version_compare(client, int(second_task["id"]), int(second_v2["versionNo"]))
    union = client.api(f"/api/task-version-unions/{union_id}")
    assert union["can_publish"] is True, union
    client.expect_error(
        f"/api/tasks/{task_id}/versions/{version2['versionNo']}/activate",
        method="POST",
        payload={"taskRevision": task["revision"], "versionRevision": version2["revision"]},
        contains="联合版本",
    )
    union = client.api(
        f"/api/task-version-unions/{union_id}/publish", method="POST",
        payload={"revision": union["revision"]},
    )
    assert union["status"] == "PUBLISHED", union
    task = client.api(f"/api/tasks/{task_id}")
    second_task = client.api(f"/api/tasks/{second_task['id']}")
    assert task["effectiveVersionNo"] == version2["versionNo"]
    assert second_task["effectiveVersionNo"] == second_v2["versionNo"]

    table_compare = client.api(
        "/api/data-compares",
        method="POST",
        payload={
            "compareType": "TABLE",
            "baselineTable": tables["left"],
            "candidateTable": tables["right"],
            "onlyCompareSameColumn": True,
            "tables": [],
        },
    )
    table_compare = wait_compare(client, int(table_compare["id"]), args.timeout)
    assert table_compare["status"] == "PASSED", table_compare

    # 制造可审计的不一致结果，覆盖规则失效、单表重跑和强制通过。
    hive_execute(hive_uri, [
        f"INSERT OVERWRITE TABLE `{database}`.`compare_right` SELECT 1, 'different'"
    ], args.timeout)
    mismatch = client.api(
        "/api/data-compares", method="POST",
        payload={
            "compareType": "TABLE", "baselineTable": tables["left"],
            "candidateTable": tables["right"], "onlyCompareSameColumn": True,
            "tables": [{
                "baselineTable": tables["left"], "candidateTable": tables["right"],
                "rule": {"onlyCompareSamePrimaryKey": False, "ignoreNullPrimaryKey": False,
                         "primaryKeyList": ["id"], "compareColumnList": [], "probeColumnList": ["label"]},
            }],
        },
    )
    mismatch = wait_compare(client, int(mismatch["id"]), args.timeout)
    assert mismatch["status"] == "NOT_PASSED", mismatch
    mismatch_table_id = int(mismatch["tables"][0]["id"])
    stale = client.api(
        f"/api/data-compares/tables/{mismatch_table_id}/rule", method="PUT",
        payload={"rule": {"onlyCompareSamePrimaryKey": False, "ignoreNullPrimaryKey": False,
                          "primaryKeyList": ["id"], "compareColumnList": ["label"],
                          "probeColumnList": ["label"]}},
    )
    assert stale["display_status"] == "NEEDS_RERUN", stale
    client.api(f"/api/data-compares/tables/{mismatch_table_id}/rerun", method="POST")
    mismatch = wait_compare(client, int(mismatch["id"]), args.timeout)
    assert mismatch["status"] == "NOT_PASSED", mismatch
    forced = client.api(
        f"/api/data-compares/tables/{mismatch_table_id}/force-pass", method="POST",
        payload={"reason": "自动化验收：确认差异符合测试预期"},
    )
    assert forced["display_status"] == "FORCE_PASSED" and forced["force_operator_ob_id"] == args.ob_id
    mismatch = client.api(f"/api/data-compares/{mismatch['id']}")
    assert mismatch["status"] == "FORCE_PASSED", mismatch
    filtered = client.api(
        f"/api/data-compares?type=TABLE&status=FORCE_PASSED&jobId={mismatch['id']}"
        "&mine=true&page=1&pageSize=10"
    )
    assert filtered["total"] == 1, filtered

    cancel_sql = "SELECT reflect('java.lang.Thread', 'sleep', 20000) AS slept"
    cancel_task = client.api(
        "/api/tasks",
        method="POST",
        payload={
            "name": prefix + "-cancel",
            "description": "SQL Agent 自动化取消测试",
            "taskType": "RUN_HIVE",
            "executionFrequency": "手动执行",
            "owner": args.ob_id,
            "sql": cancel_sql,
            "ddl": None,
            "parameters": [],
        },
    )
    cancel_execution = client.api(
        f"/api/tasks/{cancel_task['id']}/executions",
        method="POST",
        payload={"revision": cancel_task["revision"], "parameters": {}},
    )
    wait_running(client, int(cancel_execution["id"]), 15)
    client.api(f"/api/task-executions/{cancel_execution['id']}/cancel", method="POST")
    cancelled = wait_execution(client, int(cancel_execution["id"]), 30)
    assert cancelled["status"] == "CANCELLED", cancelled

    center = client.api(f"/api/task-executions?page=1&pageSize=10&status=all&keyword={prefix}")
    assert center["total"] >= 4, center
    summary = client.api("/api/task-executions/summary")
    assert summary is not None

    cancel_task = client.api(
        f"/api/tasks/{cancel_task['id']}/archive",
        method="POST",
        payload={"revision": cancel_task["revision"]},
    )
    task = client.api(
        f"/api/tasks/{task_id}/archive", method="POST", payload={"revision": task["revision"]}
    )
    second_task = client.api(
        f"/api/tasks/{second_task['id']}/archive", method="POST",
        payload={"revision": second_task["revision"]},
    )
    assert cancel_task["archived"] is True and task["archived"] is True
    assert second_task["archived"] is True

    return {
        "front": "ok",
        "backend": "ok",
        "agent": "ok",
        "hiveDatabase": database,
        "taskId": task_id,
        "secondTaskId": int(second_task["id"]),
        "cancelTaskId": int(cancel_task["id"]),
        "effectiveExecutionId": int(effective_execution["id"]),
        "version1ExecutionId": int(version1_execution["id"]),
        "version2ExecutionId": int(version2_execution["id"]),
        "cancelExecutionId": int(cancel_execution["id"]),
        "versionCompareId": int(compare["id"]),
        "secondVersionCompareId": int(second_compare["id"]),
        "tableCompareId": int(table_compare["id"]),
        "forcePassedCompareId": int(mismatch["id"]),
        "unionId": union_id,
        "publishedVersion": int(version2["versionNo"]),
        "result": "passed",
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8382")
    parser.add_argument("--ob-id", default="900001")
    parser.add_argument("--timeout", type=int, default=300)
    parser.add_argument("--hive-database")
    parser.add_argument("--hive-user", default="root")
    parser.add_argument("--hive-temp-database", default="sql_agent_verify")
    parser.add_argument("--control-plane-only", action="store_true")
    args = parser.parse_args()
    try:
        print(json.dumps(run(args), ensure_ascii=False, indent=2))
    except Exception as exc:  # noqa: BLE001 - CLI 需要保留精确失败步骤
        raise SystemExit(f"E2E FAILED: {exc}") from exc


if __name__ == "__main__":
    main()
