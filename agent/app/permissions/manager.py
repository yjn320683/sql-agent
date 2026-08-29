"""工具权限管理：低风险自动允许，高风险发起前端确认。"""

from __future__ import annotations

import asyncio
import uuid
from typing import Any

from claude_agent_sdk import PermissionResultAllow, PermissionResultDeny

from app.core.sse import sse_event
from app.domain.sql.skill import SQL_AGENT_SKILL_NAME
from app.user_questions.manager import ask_user_question

READ_ONLY_TOOLS = {"Read", "Glob", "Grep", "LS"}
SQL_MCP_TOOLS = {
    "mcp__sql_agent__hive_list_databases",
    "mcp__sql_agent__hive_search_tables",
    "mcp__sql_agent__hive_get_table",
    "mcp__sql_agent__hive_get_columns",
    "mcp__sql_agent__hive_get_partitions",
    "mcp__sql_agent__hive_get_table_ddl",
    "mcp__sql_agent__hive_find_column_usage",
    "mcp__sql_agent__hive_table_statistics_get",
    "mcp__sql_agent__hive_task_sql_validate",
    "mcp__sql_agent__hive_task_sql_explain",
    "mcp__sql_agent__hive_sql_validate",
    "mcp__sql_agent__hive_sql_explain",
    "mcp__sql_agent__hive_function_search",
    "mcp__sql_agent__hive_function_get",
    "mcp__sql_agent__hive_storage_layout_get",
    "mcp__sql_agent__hive_table_freshness_get",
    "mcp__sql_agent__yarn_application_diagnostics_get",
    "mcp__sql_agent__mapreduce_job_search",
    "mcp__sql_agent__mapreduce_job_diagnostics_get",
    "mcp__sql_agent__mapreduce_aggregated_logs_get",
    "mcp__sql_agent__mapreduce_job_compare",
    "mcp__sql_agent__platform_dependency_health_get",
    "mcp__sql_agent__data_map_get_table_primary_keys",
    "mcp__sql_agent__sql_task_get",
    "mcp__sql_agent__sql_task_version_get",
    "mcp__sql_agent__sql_task_execution_get",
    "mcp__sql_agent__sql_task_execution_list",
}
AUTO_ALLOWED_TOOLS = READ_ONLY_TOOLS | SQL_MCP_TOOLS
PERMISSION_TIMEOUT_SECONDS = 300

_pending_permissions: dict[str, tuple[asyncio.AbstractEventLoop, asyncio.Future[str]]] = {}


def resolve_permission(request_id: str, decision: str) -> bool:
    """唤醒等待中的工具权限请求。"""
    waiter = _pending_permissions.get(request_id)
    if waiter is None:
        return False
    loop, future = waiter

    def complete() -> None:
        if not future.done():
            future.set_result(decision)

    loop.call_soon_threadsafe(complete)
    return True


async def can_use_tool(
    tool_name: str,
    tool_input: dict[str, Any],
    queue: asyncio.Queue[dict[str, str] | BaseException | None],
    loop: asyncio.AbstractEventLoop,
):
    """Claude SDK 权限回调：自动允许低风险工具，其余交给 Web 确认。"""
    if tool_name == "Skill":
        if tool_input.get("skill") == SQL_AGENT_SKILL_NAME:
            return PermissionResultAllow()
        return PermissionResultDeny(
            message=f"只能加载 {SQL_AGENT_SKILL_NAME}；不要加载其它 skill。",
            interrupt=False,
        )
    if tool_name == "AskUserQuestion":
        answer_message = await ask_user_question(tool_input, queue, loop)
        return PermissionResultDeny(message=answer_message, interrupt=False)
    if tool_name in AUTO_ALLOWED_TOOLS:
        return PermissionResultAllow()
    if tool_name == "Bash":
        return PermissionResultDeny(message="业务方环境不允许使用 Bash，请改用 Read/Grep/Glob/LS 或受控 SQL MCP 工具。", interrupt=False)

    request_id = str(uuid.uuid4())
    future: asyncio.Future[str] = loop.create_future()
    _pending_permissions[request_id] = (loop, future)
    await queue.put(
        sse_event(
            "permission_request",
            {"requestId": request_id, "toolName": tool_name, "toolInput": tool_input},
        )
    )
    try:
        decision = await asyncio.wait_for(future, timeout=PERMISSION_TIMEOUT_SECONDS)
    except asyncio.TimeoutError:
        decision = "deny"
    finally:
        _pending_permissions.pop(request_id, None)

    if decision == "allow":
        return PermissionResultAllow()
    return PermissionResultDeny(message="用户拒绝或权限请求超时", interrupt=False)
