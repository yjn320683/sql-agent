"""业务澄清问题管理：把 AskUserQuestion 转成前端可回答的交互。"""

from __future__ import annotations

import asyncio
import json
import uuid
from typing import Any

from app.core.sse import sse_event

QUESTION_TIMEOUT_SECONDS = 300

_pending_questions: dict[str, tuple[asyncio.AbstractEventLoop, asyncio.Future[dict[str, Any]]]] = {}


def resolve_user_question(request_id: str, answer: dict[str, Any]) -> bool:
    """唤醒等待中的业务澄清问题。"""
    waiter = _pending_questions.get(request_id)
    if waiter is None:
        return False
    loop, future = waiter

    def complete() -> None:
        if not future.done():
            future.set_result(answer)

    loop.call_soon_threadsafe(complete)
    return True


async def ask_user_question(
    tool_input: dict[str, Any],
    queue: asyncio.Queue[dict[str, str] | BaseException | None],
    loop: asyncio.AbstractEventLoop,
) -> str:
    """发起前端澄清问题并等待用户选择结果。"""
    request_id = str(uuid.uuid4())
    future: asyncio.Future[dict[str, Any]] = loop.create_future()
    _pending_questions[request_id] = (loop, future)
    await queue.put(
        sse_event(
            "user_question_request",
            {
                "requestId": request_id,
                "questions": tool_input.get("questions", []),
                "rawInput": tool_input,
            },
        )
    )
    try:
        answer = await asyncio.wait_for(future, timeout=QUESTION_TIMEOUT_SECONDS)
    except asyncio.TimeoutError:
        answer = {"cancelled": True, "reason": "用户长时间未回答澄清问题"}
    finally:
        _pending_questions.pop(request_id, None)
    return _format_answer_for_agent(answer)


def _format_answer_for_agent(answer: dict[str, Any]) -> str:
    """把前端选择结果整理成 Claude 能继续理解的工具反馈文本。"""
    if answer.get("cancelled"):
        reason = answer.get("reason") or "用户取消了澄清问题"
        return f"用户未确认澄清问题：{reason}"

    answers = answer.get("answers")
    if not isinstance(answers, list):
        return "用户已回答澄清问题，但答案格式为空。"

    lines = ["用户已通过前端回答澄清问题，请基于以下选择继续分析："]
    for index, item in enumerate(answers, start=1):
        if not isinstance(item, dict):
            continue
        question = str(item.get("question") or item.get("header") or f"问题 {index}")
        labels = item.get("selectedLabels") or item.get("selectedValues") or []
        custom_answer = item.get("customAnswer")
        if isinstance(labels, list) and labels:
            selected = "、".join(str(label) for label in labels)
        elif custom_answer:
            selected = str(custom_answer)
        else:
            selected = "未选择"
        lines.append(f"{index}. {question}：{selected}")

    lines.append("原始结构化答案：")
    lines.append(json.dumps(answer, ensure_ascii=False, indent=2))
    return "\n".join(lines)
