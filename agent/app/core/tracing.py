"""LangSmith tracing 初始化。"""

from app.settings import Settings


def configure_tracing(settings: Settings) -> None:
    """在 FastAPI 启动时启用 Claude Agent SDK 的 LangSmith tracing。"""
    if not settings.langsmith_tracing:
        return

    from langsmith.integrations.claude_agent_sdk import configure_claude_agent_sdk

    configure_claude_agent_sdk(project_name=settings.langsmith_project or None)
