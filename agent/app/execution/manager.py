"""进程内 Hive SQL 多Step执行队列。"""

from __future__ import annotations

import asyncio
import logging
import re
import threading
import time
import uuid
from contextlib import closing
from functools import lru_cache
from typing import Any

from pyhive import hive

from app.execution.logs import ExecutionLogWriter
from app.execution.repository import TaskExecutionRepository
from app.execution.sql_script import SqlStep, parse_and_render_script
from app.settings import get_settings
from sql_agent_mcp_server.domains.hive_execution.adapter import HiveServer2Adapter, _build_transport

LOGGER = logging.getLogger(__name__)
QUERY_ID_PATTERNS = (
    re.compile(r"(?i)query\s*id\s*[=:]\s*([A-Za-z0-9_-]+)"),
    re.compile(r"(?i)queryId[=: ]+([A-Za-z0-9_-]+)"),
)
APPLICATION_ID_PATTERN = re.compile(r"application_\d+_\d+")
JOB_ID_PATTERN = re.compile(r"job_\d+_\d+")
TERMINAL_STATES = {"FINISHED_STATE", "CANCELED_STATE", "CLOSED_STATE", "ERROR_STATE", "TIMEDOUT_STATE"}
HIVE_CONNECT_ATTEMPTS = 3


class TaskExecutionManager:
    def __init__(self, repository: TaskExecutionRepository, hive_uri: str, log_dir: str, timeout: int) -> None:
        self.repository = repository
        self.hive_uri = hive_uri
        self.log_dir = log_dir
        self.timeout = timeout
        self.instance_id = uuid.uuid4().hex
        self._semaphore = asyncio.Semaphore(2)
        self._tasks: dict[int, asyncio.Task[None]] = {}
        self._cursors: dict[int, Any] = {}
        self._cursor_lock = threading.Lock()

    async def recover(self) -> None:
        await asyncio.to_thread(self.repository.fail_orphaned, self.instance_id)
        await asyncio.to_thread(self.repository.adopt_queued, self.instance_id)
        for execution_id in await asyncio.to_thread(self.repository.queued):
            self._schedule(execution_id)

    async def start(self, execution_id: int) -> bool:
        claimed = await asyncio.to_thread(self.repository.claim, execution_id, self.instance_id)
        if not claimed:
            return False
        self._schedule(execution_id)
        return True

    async def cancel(self, execution_id: int) -> bool:
        current = await asyncio.to_thread(self.repository.request_cancel, execution_id)
        if current is None or current not in {"CANCELLED", "CANCELLING"}:
            return False
        if current == "CANCELLING":
            with self._cursor_lock:
                cursor = self._cursors.get(execution_id)
            if cursor is not None:
                try:
                    await asyncio.to_thread(cursor.cancel)
                except Exception as exc:  # noqa: BLE001
                    LOGGER.warning("取消Hive操作失败 executionId=%s errorType=%s", execution_id, type(exc).__name__)
        return True

    async def shutdown(self) -> None:
        tasks = list(self._tasks.values())
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)

    def _schedule(self, execution_id: int) -> None:
        if execution_id in self._tasks and not self._tasks[execution_id].done():
            return
        task = asyncio.create_task(self._worker(execution_id))
        self._tasks[execution_id] = task
        task.add_done_callback(lambda _task: self._tasks.pop(execution_id, None))

    async def _worker(self, execution_id: int) -> None:
        async with self._semaphore:
            started = await asyncio.to_thread(self.repository.start_running, execution_id, self.instance_id)
            if not started:
                return
            try:
                await asyncio.to_thread(self._execute_sync, execution_id)
            except Exception as exc:  # noqa: BLE001
                message = _safe_error(exc)
                LOGGER.error("Hive任务执行失败 executionId=%s errorType=%s", execution_id, type(exc).__name__)
                await asyncio.to_thread(self.repository.finish, execution_id, "FAILED", error_message=message)

    def _execute_sync(self, execution_id: int) -> None:
        row = self.repository.get(execution_id)
        if row is None:
            raise RuntimeError("执行实例不存在")
        rendered_script, steps, _ = parse_and_render_script(
            str(row.get("sql_snapshot") or ""),
            row.get("parameter_schema_snapshot"),
            row.get("parameter_values"),
            row.get("business_date"),
        )
        self.repository.prepare_steps(execution_id, rendered_script, [
            {
                "execution_id": execution_id,
                "task_id": int(row["task_id"]),
                "step_no": step.number,
                "step_order": step.order,
                "step_name": step.name,
                "source_sql": step.source_sql,
                "rendered_sql": step.rendered_sql,
                "log_file": f"{execution_id}/step-{step.number}.log",
            }
            for step in steps
        ])
        writer = ExecutionLogWriter(self.log_dir, execution_id)
        writer.append(f"[INFO] executionId={execution_id} taskId={row['task_id']} 共{len(steps)}个Step")

        adapter = HiveServer2Adapter(self.hive_uri, timeout_seconds=self.timeout)
        info = adapter._connection_info(None)
        connection = self._connect_hive(info, writer)
        all_applications: set[str] = set()
        all_jobs: set[str] = set()
        last_query_id: str | None = None

        try:
            with closing(connection):
                for step in steps:
                    current = self.repository.get(execution_id) or {}
                    if current.get("status") == "CANCELLING":
                        self.repository.skip_remaining(execution_id, step.order - 1, "CANCELLED")
                        self.repository.finish(
                            execution_id, "CANCELLED", query_id=last_query_id,
                            application_ids=sorted(all_applications), job_ids=sorted(all_jobs),
                        )
                        return
                    result = self._execute_step(connection, execution_id, step, writer)
                    last_query_id = result[1] or last_query_id
                    all_applications.update(result[2])
                    all_jobs.update(result[3])
                    if result[0] != "SUCCEEDED":
                        remaining_status = "CANCELLED" if result[0] == "CANCELLED" else "SKIPPED"
                        self.repository.skip_remaining(execution_id, step.order, remaining_status)
                        self.repository.finish(
                            execution_id, result[0], query_id=last_query_id,
                            application_ids=sorted(all_applications), job_ids=sorted(all_jobs),
                            error_message=result[4] if result[0] == "FAILED" else None,
                        )
                        return
            self.repository.finish(
                execution_id, "SUCCEEDED", query_id=last_query_id,
                application_ids=sorted(all_applications), job_ids=sorted(all_jobs),
            )
            writer.append("[INFO] 所有Step执行成功")
        finally:
            with self._cursor_lock:
                self._cursors.pop(execution_id, None)

    def _connect_hive(self, info: dict[str, Any], writer: ExecutionLogWriter) -> Any:
        """仅在 SQL 提交前有限重试建连，避免重放已经执行的语句。"""
        for attempt in range(1, HIVE_CONNECT_ATTEMPTS + 1):
            try:
                transport = _build_transport(
                    str(info["host"]), int(info["port"]), info["username"], str(info["auth"]), self.timeout
                )
                return hive.connect(
                    database=str(info["database"]), username=info["username"], thrift_transport=transport
                )
            except Exception as exc:  # noqa: BLE001
                if attempt >= HIVE_CONNECT_ATTEMPTS:
                    raise
                writer.append(
                    f"[WARN] HiveServer2 建连失败，第{attempt}/{HIVE_CONNECT_ATTEMPTS}次，稍后重试："
                    f"{type(exc).__name__}"
                )
                time.sleep(attempt)
        raise RuntimeError("HiveServer2 建连失败")

    def _execute_step(
        self, connection: Any, execution_id: int, step: SqlStep, aggregate_writer: ExecutionLogWriter,
    ) -> tuple[str, str | None, set[str], set[str], str | None]:
        self.repository.start_step(execution_id, step.number)
        step_writer = ExecutionLogWriter(self.log_dir, execution_id, step.number)
        heading = f"[INFO] Step {step.number} ({step.name}) 开始执行"
        aggregate_writer.append(heading)
        step_writer.append(heading)
        query_id: str | None = None
        application_ids: set[str] = set()
        job_ids: set[str] = set()
        previous_logs: list[str] = []
        last_heartbeat = 0.0
        try:
            with closing(connection.cursor()) as cursor:
                with self._cursor_lock:
                    self._cursors[execution_id] = cursor
                cursor.execute(step.rendered_sql, async_=True)
                while True:
                    response = cursor.poll()
                    state = _operation_state(response.operationState)
                    logs = [str(item) for item in cursor.fetch_logs()]
                    new_logs, previous_logs = _log_delta(previous_logs, logs)
                    if new_logs:
                        aggregate_writer.append(new_logs)
                        step_writer.append(new_logs)
                        query_id = query_id or _query_id(new_logs)
                        joined = "\n".join(new_logs)
                        application_ids.update(APPLICATION_ID_PATTERN.findall(joined))
                        job_ids.update(JOB_ID_PATTERN.findall(joined))
                    now = time.monotonic()
                    if now - last_heartbeat >= 10:
                        self.repository.heartbeat(execution_id, self.instance_id)
                        last_heartbeat = now
                    if state in TERMINAL_STATES:
                        if state == "FINISHED_STATE":
                            final_status, error = "SUCCEEDED", None
                        elif state in {"CANCELED_STATE", "CLOSED_STATE"}:
                            final_status, error = "CANCELLED", None
                        else:
                            final_status = "FAILED"
                            error = _safe_error(getattr(response, "errorMessage", None) or state)
                        step_writer.append(f"[INFO] Hive operation state={state}")
                        aggregate_writer.append(f"[INFO] Step {step.number} state={state}")
                        self.repository.finish_step(
                            execution_id, step.number, final_status, query_id=query_id,
                            application_ids=sorted(application_ids), job_ids=sorted(job_ids), error_message=error,
                        )
                        return final_status, query_id, application_ids, job_ids, error
        except Exception as exc:  # noqa: BLE001
            error = _safe_error(exc)
            current = self.repository.get(execution_id) or {}
            final_status = "CANCELLED" if current.get("status") == "CANCELLING" else "FAILED"
            aggregate_writer.append(f"[ERROR] Step {step.number}: {error}")
            step_writer.append(f"[ERROR] {error}")
            self.repository.finish_step(
                execution_id, step.number, final_status, query_id=query_id,
                application_ids=sorted(application_ids), job_ids=sorted(job_ids),
                error_message=None if final_status == "CANCELLED" else error,
            )
            return final_status, query_id, application_ids, job_ids, error
        finally:
            with self._cursor_lock:
                self._cursors.pop(execution_id, None)


def _operation_state(value: int) -> str:
    from TCLIService import ttypes
    return ttypes.TOperationState._VALUES_TO_NAMES.get(value, "UNKNOWN_STATE")


def _log_delta(previous: list[str], current: list[str]) -> tuple[list[str], list[str]]:
    if previous and len(current) >= len(previous) and current[:len(previous)] == previous:
        return current[len(previous):], current
    return current, current


def _query_id(lines: list[str]) -> str | None:
    text = "\n".join(lines)
    for pattern in QUERY_ID_PATTERNS:
        match = pattern.search(text)
        if match:
            return match.group(1)
    return None


def _safe_error(error: object) -> str:
    value = re.sub(r"\s+", " ", str(error or "Hive SQL执行失败")).strip()
    value = re.sub(r"(?i)([a-z][a-z0-9+.-]*://)\S+", r"\1<redacted>", value)
    value = re.sub(r"(?i)(password|token|secret)\s*[=:]\s*\S+", r"\1=<redacted>", value)
    return value[:4000]


@lru_cache
def get_execution_manager() -> TaskExecutionManager:
    settings = get_settings()
    return TaskExecutionManager(
        TaskExecutionRepository(settings.sql_agent_db_uri, settings.mcp_tool_timeout_seconds),
        settings.hive_server2_uri,
        settings.task_execution_log_dir,
        settings.mcp_tool_timeout_seconds,
    )
