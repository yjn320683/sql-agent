"""SQL 任务只读服务。"""

from pathlib import Path
from typing import Any

from sql_agent_mcp_server.domains.sql_task.adapter import SqlTaskAdapter
from sql_agent_mcp_server.settings import Settings


class SqlTaskService:
    def __init__(self, adapter: SqlTaskAdapter) -> None:
        self.adapter = adapter

    @classmethod
    def from_settings(cls, settings: Settings) -> "SqlTaskService":
        log_dir = Path(settings.mcp_log_file).expanduser().resolve().parent / "task-executions"
        return cls(SqlTaskAdapter(
            settings.sql_agent_db_uri,
            timeout_seconds=settings.mcp_tool_timeout_seconds,
            log_dir=log_dir,
        ))

    def get_task(self, task_id: int, version_no: int | None = None) -> dict[str, Any]:
        return _response(self.adapter.source, {"task": self.adapter.get_task(task_id, version_no)})

    def get_execution(self, execution_id: int) -> dict[str, Any]:
        return _response(self.adapter.source, {"execution": self.adapter.get_execution(execution_id)})

    def get_version(self, task_id: int, version_no: int) -> dict[str, Any]:
        return _response(self.adapter.source, {"version": self.adapter.get_version(task_id, version_no)})

    def list_tasks(self, limit: int, offset: int = 0) -> dict[str, Any]:
        bounded_limit = max(1, min(limit, 500))
        bounded_offset = max(0, offset)
        return _response(self.adapter.source, self.adapter.list_tasks(bounded_limit, bounded_offset))

    def list_executions(self, task_id: int, limit: int) -> dict[str, Any]:
        return _response(self.adapter.source, {"items": self.adapter.list_executions(task_id, limit)})


def _response(source: str, payload: dict[str, Any]) -> dict[str, Any]:
    from datetime import datetime, timezone
    return {
        "ok": True,
        "source": source,
        "fetchedAt": datetime.now(timezone.utc).isoformat(),
        "warnings": [],
        "complete": True,
        "missingReasons": [],
        **payload,
    }
