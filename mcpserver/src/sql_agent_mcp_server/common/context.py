"""MCP 调用身份上下文。"""

from __future__ import annotations

import os
import re

from sql_agent_mcp_server.common.errors import McpDomainError, McpErrorCode

OB_ID_PATTERN = re.compile(r"^[0-9]{1,20}$")


def require_principal_ob_id() -> str:
    """要求每个 stdio MCP 进程绑定一个经过 backend 校验的用户。"""

    ob_id = os.getenv("SQL_AGENT_OB_ID", "").strip()
    if not OB_ID_PATTERN.fullmatch(ob_id):
        raise McpDomainError(
            McpErrorCode.PERMISSION_DENIED,
            "MCP caller identity is missing or invalid.",
        )
    return ob_id
