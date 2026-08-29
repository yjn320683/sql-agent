"""对话核心：驱动 Claude Agent SDK 并产出 SSE 事件。"""

import asyncio
import json
import logging
import uuid
from typing import Any, AsyncIterator

from claude_agent_sdk import ClaudeAgentOptions, ClaudeSDKClient, ProcessError

from app.claude.events import EventMapper
from app.claude.options import build_options
from app.core.sse import sse_event
from app.domain.sql.commands import normalize_command
from app.permissions.manager import can_use_tool

_RUNNING_TASKS: dict[str, asyncio.Task[None]] = {}
_ACTIVE_SESSIONS: set[str] = set()
logger = logging.getLogger(__name__)

MCP_CONNECTION_ERROR_MARKERS = (
    "remote end closed connection without response",
    "connection refused",
    "连接失败",
    "连接被拒绝",
)
MCP_CONNECTION_ERROR_TOOL_MARKERS = (
    "error executing tool",
    "mcp__sql_agent__",
)
MCP_CONNECTION_RETRY_HINT = (
    "\n\n注意：上一轮 SQL Agent MCP 连接失败可能已恢复。"
    "请忽略旧的连接失败结论，必须重新调用相关 Hive/Data Map 工具获取最新事实，再继续回答。"
)


def cancel_chat(session_id: str) -> bool:
    """取消指定 session 当前正在运行的 Claude 请求。"""
    task = _RUNNING_TASKS.get(session_id)
    if task is None or task.done():
        return False
    task.cancel()
    return True


async def run_chat(
    session_id: str,
    ob_id: str,
    task_id: int,
    message: str,
    command: str | None = None,
    execution_id: int | None = None,
    version_no: int | None = None,
) -> AsyncIterator[dict[str, str]]:
    """保证同一会话只有一个活动 turn，再执行对话。"""

    if session_id in _ACTIVE_SESSIONS:
        yield sse_event("error", {"message": "当前会话已有请求正在执行，请等待完成或先停止。"})
        return

    _ACTIVE_SESSIONS.add(session_id)
    try:
        async for event in _run_chat_claimed(
            session_id, ob_id, task_id, message, command=command, execution_id=execution_id,
            version_no=version_no,
        ):
            yield event
    finally:
        _ACTIVE_SESSIONS.discard(session_id)


async def _run_chat_claimed(
    session_id: str,
    ob_id: str,
    task_id: int,
    message: str,
    command: str | None = None,
    execution_id: int | None = None,
    version_no: int | None = None,
) -> AsyncIterator[dict[str, str]]:
    """先尝试续接已有会话；session 不存在则新建。"""

    parsed_command = normalize_command(command, message)
    effective_message = _build_agent_message(
        parsed_command.command, task_id, execution_id, version_no, parsed_command.message
    )
    emitted = False
    try:
        async for evt in _stream_resume_or_retry(session_id, ob_id, parsed_command.command, effective_message):
            emitted = True
            yield evt
        return
    except ProcessError as exc:
        if emitted:
            yield _safe_error_event(session_id, exc)
            return
    except Exception as exc:  # noqa: BLE001 - SSE 边界兜底，避免连接静默挂死
        yield _safe_error_event(session_id, exc)
        return

    try:
        async for evt in _stream_once(
            build_options(ob_id, parsed_command.command, session_id=session_id),
            session_id,
            effective_message,
        ):
            yield evt
    except Exception as exc:  # noqa: BLE001
        yield _safe_error_event(session_id, exc)


def _safe_error_event(session_id: str, exc: BaseException) -> dict[str, str]:
    """记录完整异常，只向调用方返回可关联的脱敏错误。"""

    error_id = str(uuid.uuid4())
    logger.error("claude_request_failed errorId=%s sessionId=%s", error_id, session_id, exc_info=exc)
    return sse_event("error", {"message": f"Agent 执行失败，错误编号：{error_id}"})


async def _stream_resume_or_retry(
    session_id: str,
    ob_id: str,
    command: str,
    message: str,
) -> AsyncIterator[dict[str, str]]:
    buffered_events: list[dict[str, str]] = []
    has_connection_error = False
    passthrough = False
    async for event in _stream_once(build_options(ob_id, command, resume=session_id), session_id, message):
        if passthrough:
            yield event
            continue

        if _is_interactive_event(event):
            for buffered in buffered_events:
                yield buffered
            buffered_events.clear()
            passthrough = True
            yield event
            continue

        buffered_events.append(event)
        if _is_mcp_connection_error_event(event):
            has_connection_error = True

        if has_connection_error and _is_retry_decision_boundary(event):
            logger.info(
                "resume_retry_due_to_mcp_connection_error sessionId=%s retry=1 reason=%s",
                session_id,
                _mcp_connection_error_summary(buffered_events),
            )
            async for retry_event in _stream_once(
                build_options(ob_id, command, session_id=session_id),
                session_id,
                _fresh_retry_message(message),
            ):
                yield retry_event
            return

        if _is_resume_passthrough_boundary(event):
            for buffered in buffered_events:
                yield buffered
            buffered_events.clear()
            passthrough = True

    if has_connection_error:
        logger.info(
            "resume_retry_due_to_mcp_connection_error sessionId=%s retry=1 reason=%s",
            session_id,
            _mcp_connection_error_summary(buffered_events),
        )
        async for retry_event in _stream_once(
            build_options(ob_id, command, session_id=session_id),
            session_id,
            _fresh_retry_message(message),
        ):
            yield retry_event
        return

    for buffered in buffered_events:
        yield buffered


def _fresh_retry_message(message: str) -> str:
    return message + MCP_CONNECTION_RETRY_HINT


def _mcp_connection_error_summary(events: list[dict[str, str]]) -> str:
    for event in events:
        if _is_mcp_connection_error_event(event):
            return _event_text(event)[:240]
    return ""


def _is_mcp_connection_error_event(event: dict[str, str]) -> bool:
    text = _event_text(event).lower()
    if not any(marker in text for marker in MCP_CONNECTION_ERROR_MARKERS):
        return False
    return any(marker in text for marker in MCP_CONNECTION_ERROR_TOOL_MARKERS)


def _is_interactive_event(event: dict[str, str]) -> bool:
    return event.get("event") in {"user_question_request", "permission_request"}


def _is_retry_decision_boundary(event: dict[str, str]) -> bool:
    return event.get("event") in {"text", "done", "error"}


def _is_resume_passthrough_boundary(event: dict[str, str]) -> bool:
    return event.get("event") in {"text", "done", "error"}


def _event_text(event: dict[str, str]) -> str:
    try:
        payload = json.loads(event.get("data", "{}"))
    except json.JSONDecodeError:
        return event.get("data", "")
    parts = [event.get("event", "")]
    for key in ("content", "message", "delta", "toolName"):
        value = payload.get(key)
        if isinstance(value, str):
            parts.append(value)
    if payload.get("isError") is True:
        parts.append("is_error")
    return "\n".join(parts)


def _build_agent_message(
    command: str,
    task_id: int,
    execution_id: int | None,
    version_no: int | None,
    message: str,
) -> str:
    source_instruction = (
        f"必须先调用 sql_task_version_get(taskId={task_id}, versionNo={version_no}) 查询指定版本及其 SQL。"
        if version_no is not None
        else "必须先调用 sql_task_get 查询任务当前生效代码，不得要求用户重新粘贴 SQL。"
    )
    return (
        f"当前 command：{command}\n"
        f"当前 taskId：{task_id}\n"
        f"当前 executionId：{execution_id if execution_id is not None else '未指定'}\n"
        f"当前 versionNo：{version_no if version_no is not None else '未指定'}\n"
        f"{source_instruction}\n\n"
        f"用户需求：\n{message}"
    )


async def _prompt_stream(session_id: str, message: str) -> AsyncIterator[dict[str, Any]]:
    """SDK 的 can_use_tool 权限回调要求 query 使用流式输入。"""
    yield {
        "type": "user",
        "message": {"role": "user", "content": message},
        "parent_tool_use_id": None,
        "session_id": session_id,
    }


async def _stream_once(
    options: ClaudeAgentOptions,
    session_id: str,
    message: str,
) -> AsyncIterator[dict[str, str]]:
    """用给定 options 跑一轮对话，逐 block 产出 SSE 事件。"""
    queue: asyncio.Queue[dict[str, str] | BaseException | None] = asyncio.Queue()
    loop = asyncio.get_running_loop()
    stderr_lines: list[str] = []
    event_mapper = EventMapper()

    def capture_stderr(line: str) -> None:
        if line:
            stderr_lines.append(line.rstrip())

    async def permission_callback(tool_name: str, tool_input: dict[str, Any], _context):
        return await can_use_tool(tool_name, tool_input, queue, loop)

    options.can_use_tool = permission_callback
    options.stderr = capture_stderr

    async def produce() -> None:
        try:
            async with ClaudeSDKClient(options=options) as client:
                await client.query(_prompt_stream(session_id, message), session_id=session_id)
                async for msg in client.receive_response():
                    for event in event_mapper.map_message_events(msg):
                        await queue.put(event)
        except asyncio.CancelledError:
            return
        except BaseException as exc:  # noqa: BLE001 - 交给外层保持原有重试语义
            detail = str(exc)
            if stderr_lines:
                detail = detail + "\n\nClaude stderr:\n" + "\n".join(stderr_lines[-20:])
            if isinstance(exc, ProcessError):
                exc.args = (detail,)
                await queue.put(exc)
            else:
                await queue.put(RuntimeError(detail))
        finally:
            await queue.put(None)

    producer = asyncio.create_task(produce())
    _RUNNING_TASKS[session_id] = producer
    try:
        while True:
            item = await queue.get()
            if item is None:
                break
            if isinstance(item, BaseException):
                raise item
            yield item
    finally:
        if _RUNNING_TASKS.get(session_id) is producer:
            _RUNNING_TASKS.pop(session_id, None)
        if not producer.done():
            producer.cancel()
            try:
                await producer
            except asyncio.CancelledError:
                pass
