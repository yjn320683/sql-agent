"""FastAPI 路由注册。"""

from fastapi import Depends, FastAPI

from app.api import chat, executions, health, permissions, user_questions, workspace
from app.api.auth import require_service_token


def register_routes(app: FastAPI) -> None:
    """注册所有 HTTP 路由。"""
    app.include_router(health.router)
    dependencies = [Depends(require_service_token)]
    app.include_router(chat.router, dependencies=dependencies)
    app.include_router(permissions.router, dependencies=dependencies)
    app.include_router(user_questions.router, dependencies=dependencies)
    app.include_router(executions.router, dependencies=dependencies)
    app.include_router(workspace.router, dependencies=dependencies)
