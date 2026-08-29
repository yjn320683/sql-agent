"""SQL Agent 业务库只读任务访问。"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from sqlalchemy import create_engine, text
from sqlalchemy.engine import Engine

from sql_agent_mcp_server.common.errors import McpDomainError, McpErrorCode


class SqlTaskAdapter:
    source = "sql-agent-db"

    def __init__(self, db_uri: str | None, *, timeout_seconds: int, log_dir: Path) -> None:
        self.db_uri = db_uri
        self.timeout_seconds = timeout_seconds
        self.log_dir = log_dir.resolve()
        self._engine: Engine | None = None

    def get_task(self, task_id: int, version_no: int | None = None) -> dict[str, Any]:
        if version_no is not None:
            if version_no < 1:
                raise McpDomainError(
                    McpErrorCode.INVALID_REQUEST,
                    "SQL task version number must be positive.",
                    details={"taskId": task_id, "versionNo": version_no},
                )
            return self.get_version(task_id, version_no)
        row = self._one("""
            SELECT id, name, description, task_type, execution_frequency, owner, enabled,
                   sql_content, ddl_content, parameter_schema, sql_checksum, revision,
                   effective_version_no, archived, created_by, updated_by,
                   create_time, update_time
            FROM sql_task WHERE id = :id
        """, {"id": task_id})
        if row is None:
            raise McpDomainError(McpErrorCode.NOT_FOUND, "SQL task was not found.", details={"taskId": task_id})
        row["sql"] = row.pop("sql_content")
        row["ddl"] = row.pop("ddl_content", None)
        row["parameters"] = _json(row.pop("parameter_schema"), [])
        row["content_source"] = "EFFECTIVE_TASK"
        row["status"] = "EFFECTIVE"
        return row

    def get_version(self, task_id: int, version_no: int) -> dict[str, Any]:
        row = self._one("""
            SELECT task_id, version_no, status, name, description, sql_content, ddl_content,
                   parameter_schema, sql_checksum, revision, base_effective_version_no,
                   base_effective_checksum, version_note
            FROM sql_task_version WHERE task_id = :task_id AND version_no = :version_no
        """, {"task_id": task_id, "version_no": version_no})
        if row is None:
            raise McpDomainError(McpErrorCode.NOT_FOUND, "SQL task version was not found.",
                                 details={"taskId": task_id, "versionNo": version_no})
        row["sql"] = row.pop("sql_content")
        row["ddl"] = row.pop("ddl_content", None)
        row["parameters"] = _json(row.pop("parameter_schema"), [])
        row["content_source"] = "SAVED_VERSION"
        row["steps"] = self._version_steps(task_id, version_no)
        return row

    def get_execution(self, execution_id: int) -> dict[str, Any]:
        row = self._one("""
            SELECT id, task_id, task_name_snapshot, source_type, task_version_no, task_revision,
                   status, current_step_no, total_steps, succeeded_steps, failed_step_no,
                   requested_by, query_id, application_ids, job_ids, error_message,
                   submitted_at, started_at, finished_at
            FROM sql_task_execution WHERE id = :id
        """, {"id": execution_id})
        if row is None:
            raise McpDomainError(
                McpErrorCode.NOT_FOUND, "SQL task execution was not found.", details={"executionId": execution_id}
            )
        row["logTail"] = self._read_log_tail(execution_id)
        row["steps"] = self._execution_steps(execution_id)
        return row

    def list_tasks(self, limit: int, offset: int = 0) -> dict[str, Any]:
        try:
            with self._engine_or_raise().connect() as conn:
                total = int(conn.execute(text("SELECT COUNT(*) FROM sql_task")).scalar_one())
                rows = conn.execute(text("""
                    SELECT id, name, description, task_type, execution_frequency, owner, enabled,
                           sql_content, parameter_schema, sql_checksum,
                           revision, effective_version_no, archived, created_by, updated_by,
                           create_time, update_time
                    FROM sql_task
                    ORDER BY id
                    LIMIT :limit OFFSET :offset
                """), {"limit": limit, "offset": offset}).mappings().all()
                items = []
                for row in rows:
                    item = _serialize(dict(row))
                    item["sql"] = item.pop("sql_content")
                    item["parameters"] = _json(item.pop("parameter_schema"), [])
                    item["content_source"] = "EFFECTIVE_TASK"
                    item["status"] = "EFFECTIVE"
                    items.append(item)
                return {"items": items, "total": total, "limit": limit, "offset": offset}
        except McpDomainError:
            raise
        except Exception as exc:  # noqa: BLE001
            raise self._dependency_error(exc) from exc

    def list_executions(self, task_id: int, limit: int) -> list[dict[str, Any]]:
        self.get_task(task_id)
        try:
            with self._engine_or_raise().connect() as conn:
                rows = conn.execute(text("""
                    SELECT id, task_id, task_name_snapshot, status, requested_by, query_id,
                           application_ids, job_ids, error_message, submitted_at, started_at, finished_at
                    FROM sql_task_execution WHERE task_id = :task_id
                    ORDER BY submitted_at DESC, id DESC LIMIT :limit
                """), {"task_id": task_id, "limit": limit}).mappings().all()
                return [_serialize(dict(row)) for row in rows]
        except McpDomainError:
            raise
        except Exception as exc:  # noqa: BLE001
            raise self._dependency_error(exc) from exc

    def _one(self, sql: str, params: dict[str, Any]) -> dict[str, Any] | None:
        try:
            with self._engine_or_raise().connect() as conn:
                row = conn.execute(text(sql), params).mappings().first()
                return _serialize(dict(row)) if row else None
        except McpDomainError:
            raise
        except Exception as exc:  # noqa: BLE001
            raise self._dependency_error(exc) from exc

    def _version_steps(self, task_id: int, version_no: int) -> list[dict[str, Any]]:
        try:
            with self._engine_or_raise().connect() as conn:
                rows = conn.execute(text("""
                    SELECT step_no, step_order, step_name, step_sql, statement_type,
                           input_tables, output_tables, sql_checksum
                    FROM sql_task_version_step
                    WHERE task_id = :task_id AND version_no = :version_no ORDER BY step_order
                """), {"task_id": task_id, "version_no": version_no}).mappings().all()
                result = []
                for item in rows:
                    row = _serialize(dict(item))
                    row["sql"] = row.pop("step_sql")
                    row["input_tables"] = _json(row.get("input_tables"), [])
                    row["output_tables"] = _json(row.get("output_tables"), [])
                    result.append(row)
                return result
        except Exception as exc:  # noqa: BLE001
            raise self._dependency_error(exc) from exc

    def _execution_steps(self, execution_id: int) -> list[dict[str, Any]]:
        try:
            with self._engine_or_raise().connect() as conn:
                rows = conn.execute(text("""
                    SELECT step_no, step_order, step_name, status, query_id, application_ids,
                           job_ids, error_message, log_file, started_at, finished_at
                    FROM sql_task_execution_step WHERE execution_id = :id ORDER BY step_order
                """), {"id": execution_id}).mappings().all()
                return [_serialize(dict(item)) for item in rows]
        except Exception as exc:  # noqa: BLE001
            raise self._dependency_error(exc) from exc

    def _engine_or_raise(self) -> Engine:
        if self._engine is not None:
            return self._engine
        if not self.db_uri:
            raise McpDomainError(
                McpErrorCode.DEPENDENCY_UNAVAILABLE,
                "SQL Agent DB URI is not configured.",
                details={"required": "SQL_AGENT_DB_URI"},
            )
        connect_args = {
            "connect_timeout": self.timeout_seconds,
            "read_timeout": self.timeout_seconds,
            "write_timeout": self.timeout_seconds,
        } if self.db_uri.startswith("mysql") else {}
        self._engine = create_engine(self.db_uri, pool_pre_ping=True, connect_args=connect_args)
        return self._engine

    def _read_log_tail(self, execution_id: int) -> str:
        path = (self.log_dir / f"{execution_id}.log").resolve()
        if not path.is_file() or not path.parent == self.log_dir:
            return ""
        with path.open("rb") as stream:
            size = path.stat().st_size
            stream.seek(max(0, size - 64 * 1024))
            return stream.read(64 * 1024).decode("utf-8", errors="replace")

    @staticmethod
    def _dependency_error(exc: Exception) -> McpDomainError:
        return McpDomainError(
            McpErrorCode.DEPENDENCY_UNAVAILABLE,
            "SQL Agent DB query failed.",
            details={"errorType": type(exc).__name__},
        )


def _serialize(value: dict[str, Any]) -> dict[str, Any]:
    for key, item in list(value.items()):
        if hasattr(item, "isoformat"):
            value[key] = item.isoformat()
        elif key in {"application_ids", "job_ids"} and isinstance(item, str):
            try:
                parsed = json.loads(item)
                value[key] = parsed if isinstance(parsed, list) else []
            except json.JSONDecodeError:
                value[key] = []
    return value


def _json(value: Any, fallback: Any) -> Any:
    if not isinstance(value, str) or not value.strip():
        return fallback
    try:
        return json.loads(value)
    except json.JSONDecodeError:
        return fallback
