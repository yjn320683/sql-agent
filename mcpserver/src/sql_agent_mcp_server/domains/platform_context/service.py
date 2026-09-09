"""数据开发页面通用上下文服务。"""

from sql_agent_mcp_server.domains.platform_context.adapter import PlatformContextAdapter
from sql_agent_mcp_server.settings import Settings


class PlatformContextService:
    def __init__(self, adapter: PlatformContextAdapter) -> None:
        self.adapter = adapter

    @classmethod
    def from_settings(cls, settings: Settings) -> "PlatformContextService":
        return cls(PlatformContextAdapter(settings.sql_agent_db_uri, timeout_seconds=settings.mcp_tool_timeout_seconds))

    def get_context(self, context_type: str, entity_id: str | None) -> dict:
        normalized_type = context_type.strip().upper()
        normalized_id = entity_id.strip() if entity_id and entity_id.strip() else None
        return {"ok": True, "source": self.adapter.source, "data": self.adapter.get_context(normalized_type, normalized_id)}
