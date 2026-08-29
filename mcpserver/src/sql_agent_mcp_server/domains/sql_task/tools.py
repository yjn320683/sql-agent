"""SQL 任务 MCP 工具。"""

from mcp.server.fastmcp import FastMCP

from sql_agent_mcp_server.common.logging import run_tool_safely
from sql_agent_mcp_server.domains.sql_task.service import SqlTaskService


def register_sql_task_tools(mcp: FastMCP, service: SqlTaskService) -> None:
    @mcp.tool()
    def sql_task_get(taskId: int, versionNo: int | None = None) -> dict:
        """Get effective task code, or one saved version in any lifecycle status when versionNo is provided."""
        context = {"taskId": taskId}
        if versionNo is not None:
            context["versionNo"] = versionNo
        return run_tool_safely(
            "sql_task_get", lambda: service.get_task(taskId, versionNo), context
        )

    @mcp.tool()
    def sql_task_execution_get(executionId: int) -> dict:
        """Get one SQL task execution with runtime IDs, error summary, and bounded sanitized log tail."""
        return run_tool_safely(
            "sql_task_execution_get", lambda: service.get_execution(executionId), {"executionId": executionId}
        )

    @mcp.tool()
    def sql_task_version_get(taskId: int, versionNo: int) -> dict:
        """Get one saved SQL task version, including its lifecycle status, parameters, and normalized Steps."""
        return run_tool_safely(
            "sql_task_version_get",
            lambda: service.get_version(taskId, versionNo),
            {"taskId": taskId, "versionNo": versionNo},
        )

    @mcp.tool()
    def sql_task_execution_list(taskId: int, limit: int = 10) -> dict:
        """List recent SQL task executions without returning SQL snapshots."""
        bounded_limit = max(1, min(limit, 20))
        return run_tool_safely(
            "sql_task_execution_list",
            lambda: service.list_executions(taskId, bounded_limit),
            {"taskId": taskId, "limit": bounded_limit},
        )
