"""SSE 事件构造工具。"""

import json
from typing import Any


def sse_event(name: str, payload: dict[str, Any]) -> dict[str, str]:
    """构造 sse-starlette 可消费的事件字典。"""
    return {"event": name, "data": json.dumps(payload, ensure_ascii=False)}


def stringify(value: Any) -> str:
    """把 SDK 或工具返回内容规整成前端可展示字符串。"""
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        parts: list[str] = []
        for item in value:
            if isinstance(item, dict) and "text" in item:
                parts.append(str(item["text"]))
            else:
                parts.append(stringify(item))
        return "\n".join(part for part in parts if part)
    return json.dumps(value, ensure_ascii=False)
