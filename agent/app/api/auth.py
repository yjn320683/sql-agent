"""backend 到 agent 的服务鉴权。"""

from __future__ import annotations

import secrets

from fastapi import Header, HTTPException, status

from app.settings import get_settings


def require_service_token(x_agent_service_token: str | None = Header(default=None)) -> None:
    """仅允许持有共享服务令牌的 backend 调用业务接口。"""

    configured = get_settings().service_token
    if not configured:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Agent service token is not configured.",
        )
    if not x_agent_service_token or not secrets.compare_digest(x_agent_service_token, configured):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Unauthorized agent caller.")
