"""Claude SDK 消息到前端 SSE 事件的映射。"""

from __future__ import annotations

from typing import Any, Iterator

from claude_agent_sdk import (
    AssistantMessage,
    ResultMessage,
    TextBlock,
    ThinkingBlock,
    ToolResultBlock,
    ToolUseBlock,
    UserMessage,
)

from app.core.sse import sse_event, stringify


class EventMapper:
    """把 Claude SDK 消息映射为页面消费的 SSE 事件。"""

    def __init__(self) -> None:
        self._tool_names_by_id: dict[str, str] = {}

    def map_message_events(self, msg: object) -> list[dict[str, str]]:
        events: list[dict[str, str]] = []
        if isinstance(msg, AssistantMessage):
            for block in msg.content:
                if isinstance(block, ThinkingBlock):
                    events.append(sse_event("thinking", {"delta": block.thinking}))
                elif isinstance(block, TextBlock):
                    events.append(sse_event("text", {"delta": block.text}))
                elif isinstance(block, ToolUseBlock):
                    self._tool_names_by_id[block.id] = block.name
                    events.append(
                        sse_event("tool_use", {"id": block.id, "name": block.name, "input": block.input})
                    )
                elif isinstance(block, dict) and block.get("type") == "tool_use":
                    tool_use_id = block.get("id")
                    tool_name = block.get("name", "")
                    if tool_use_id:
                        self._tool_names_by_id[tool_use_id] = tool_name
                    events.append(
                        sse_event("tool_use", {"id": tool_use_id, "name": tool_name, "input": block.get("input")})
                    )
        elif isinstance(msg, UserMessage):
            for tool_use_id, content, is_error in _iter_tool_results(msg.content):
                if tool_use_id:
                    tool_name = self._tool_names_by_id.get(tool_use_id, "")
                    semantic_type = "user_question_answer" if tool_name == "AskUserQuestion" else None
                    payload = {
                        "toolUseId": tool_use_id,
                        "content": content,
                        "isError": False if semantic_type else is_error,
                    }
                    if semantic_type:
                        payload["semanticType"] = semantic_type
                    events.append(
                        sse_event(
                            "tool_result",
                            payload,
                        )
                    )
        elif isinstance(msg, ResultMessage):
            events.append(sse_event("done", {"usage": msg.usage or {}, "sessionId": msg.session_id}))
        return events


def map_message_events(msg: object) -> list[dict[str, str]]:
    """兼容旧调用：无状态映射，历史测试和简单工具场景仍可直接使用。"""
    return EventMapper().map_message_events(msg)


def _iter_tool_results(content: Any) -> Iterator[tuple[str | None, str, bool]]:
    """兼容 SDK block 对象和 dict 两种工具结果形态。"""
    if not isinstance(content, list):
        return
    for block in content:
        if isinstance(block, ToolResultBlock):
            yield block.tool_use_id, stringify(block.content), bool(block.is_error)
        elif isinstance(block, dict) and block.get("type") == "tool_result":
            yield (
                block.get("tool_use_id") or block.get("toolUseId"),
                stringify(block.get("content")),
                bool(block.get("is_error") or block.get("isError")),
            )
