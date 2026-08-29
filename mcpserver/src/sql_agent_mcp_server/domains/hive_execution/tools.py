"""HiveServer2 MCP 工具。"""

from __future__ import annotations

from typing import Any

from mcp.server.fastmcp import FastMCP

from sql_agent_mcp_server.common.logging import run_tool_safely
from sql_agent_mcp_server.common.schemas import dump_response
from sql_agent_mcp_server.domains.hive_execution.schemas import (
    HiveExplainRequest,
    HiveFunctionDetailRequest,
    HiveFunctionSearchRequest,
    HiveSqlRequest,
)
from sql_agent_mcp_server.domains.hive_execution.service import HiveExecutionService
from sql_agent_mcp_server.domains.sql_task.service import SqlTaskService


def register_hive_execution_tools(
    mcp: FastMCP,
    service: HiveExecutionService,
    task_service: SqlTaskService,
) -> None:
    @mcp.tool()
    def hive_task_sql_validate(
        taskId: int,
        versionNo: int | None = None,
        defaultDb: str | None = None,
    ) -> dict[str, Any]:
        """Compile exact effective or version-draft SQL through read-only EXPLAIN without returning SQL."""

        return run_tool_safely(
            "hive_task_sql_validate",
            lambda: dump_response(service.validate(HiveSqlRequest(
                sql=_get_task_sql(task_service, taskId, versionNo),
                defaultDb=defaultDb,
            ))),
            {"taskId": taskId, "versionNo": versionNo, "defaultDb": defaultDb},
        )

    @mcp.tool()
    def hive_task_sql_explain(
        taskId: int,
        versionNo: int | None = None,
        defaultDb: str | None = None,
        extended: bool = False,
    ) -> dict[str, Any]:
        """Explain the exact current SQL of a task without copying it through the model or returning it."""

        return run_tool_safely(
            "hive_task_sql_explain",
            lambda: dump_response(service.explain(HiveExplainRequest(
                sql=_get_task_sql(task_service, taskId, versionNo),
                defaultDb=defaultDb,
                extended=extended,
            ))),
            {"taskId": taskId, "versionNo": versionNo, "defaultDb": defaultDb, "extended": extended},
        )

    @mcp.tool()
    def hive_sql_validate(sql: str = "", defaultDb: str | None = None) -> dict[str, Any]:
        """Compile candidate Hive SQL through read-only EXPLAIN; use hive_task_sql_validate for task SQL."""

        return run_tool_safely(
            "hive_sql_validate",
            lambda: dump_response(service.validate(HiveSqlRequest(sql=sql, defaultDb=defaultDb))),
            {"defaultDb": defaultDb, "sqlLength": len(sql)},
        )

    @mcp.tool()
    def hive_sql_explain(sql: str = "", defaultDb: str | None = None, extended: bool = False) -> dict[str, Any]:
        """Explain candidate Hive SQL; use hive_task_sql_explain for the exact current task SQL."""

        return run_tool_safely(
            "hive_sql_explain",
            lambda: dump_response(
                service.explain(HiveExplainRequest(sql=sql, defaultDb=defaultDb, extended=extended))
            ),
            {"defaultDb": defaultDb, "extended": extended, "sqlLength": len(sql)},
        )

    @mcp.tool()
    def hive_function_search(
        keyword: str = "",
        limit: int = 50,
        offset: int = 0,
        defaultDb: str | None = None,
    ) -> dict[str, Any]:
        """Search functions actually registered in HiveServer2 using SHOW FUNCTIONS."""

        return run_tool_safely(
            "hive_function_search",
            lambda: dump_response(service.search_functions(HiveFunctionSearchRequest(
                keyword=keyword,
                limit=limit,
                offset=offset,
                defaultDb=defaultDb,
            ))),
            {"keyword": keyword, "limit": limit, "offset": offset, "defaultDb": defaultDb},
        )

    @mcp.tool()
    def hive_function_get(name: str, defaultDb: str | None = None) -> dict[str, Any]:
        """Get the real HiveServer2 description for one strictly validated function name."""

        return run_tool_safely(
            "hive_function_get",
            lambda: dump_response(service.get_function(HiveFunctionDetailRequest(
                name=name,
                defaultDb=defaultDb,
            ))),
            {"name": name, "defaultDb": defaultDb},
        )


def _get_task_sql(task_service: SqlTaskService, task_id: int, version_no: int | None = None) -> str:
    """Resolve task SQL inside MCP so long statements never need model-side reconstruction."""

    result = task_service.get_task(task_id) if version_no is None else task_service.get_task(task_id, version_no)
    task = result.get("task") or {}
    return str(task.get("sql") or "")
