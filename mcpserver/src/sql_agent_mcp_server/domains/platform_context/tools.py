"""通用上下文读取与 Proposal 展示工具。"""

from typing import Any, Literal

from mcp.server.fastmcp import FastMCP

from sql_agent_mcp_server.common.logging import run_tool_safely
from sql_agent_mcp_server.domains.platform_context.service import PlatformContextService


def register_platform_context_tools(mcp: FastMCP, service: PlatformContextService) -> None:
    @mcp.tool()
    def platform_context_get(contextType: str, entityId: str | None = None) -> dict:
        """Read sanitized facts for one data-development page entity, or a bounded page summary."""
        return run_tool_safely(
            "platform_context_get",
            lambda: service.get_context(contextType, entityId),
            {"contextType": contextType, "entityId": entityId},
        )

    @mcp.tool()
    def platform_proposal_present(
        target: str,
        kind: Literal["SQL", "DDL", "FORM", "CONFIG"],
        before: str | None = None,
        after: str | None = None,
        patch: dict[str, Any] | None = None,
        baseRevision: int | str = 0,
        summary: str = "",
        risks: list[str] | None = None,
    ) -> dict:
        """Present a non-executing SQL, DDL, or form-field proposal for explicit page confirmation."""
        if not target.strip():
            return {"ok": False, "code": "invalid_request", "message": "Proposal target is required."}
        if before is None and after is None and not patch:
            return {"ok": False, "code": "invalid_request", "message": "Proposal must contain content or a field patch."}
        proposal = {
            "target": target.strip(),
            "kind": kind,
            "before": before,
            "after": after,
            "patch": patch or {},
            "baseRevision": baseRevision,
            "summary": summary,
            "risks": risks or [],
        }
        return {"ok": True, "source": "model-proposal", "data": proposal}
