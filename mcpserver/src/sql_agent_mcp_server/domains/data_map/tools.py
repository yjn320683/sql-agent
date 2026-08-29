"""Data map MCP tools。"""

from __future__ import annotations

from typing import Any

from mcp.server.fastmcp import FastMCP

from sql_agent_mcp_server.common.logging import run_tool_safely
from sql_agent_mcp_server.common.schemas import dump_response
from sql_agent_mcp_server.domains.data_map.schemas import GetTablePrimaryKeysRequest
from sql_agent_mcp_server.domains.data_map.service import DataMapService


def register_data_map_tools(mcp: FastMCP, service: DataMapService) -> None:
    """注册 Data Map MCP tools。"""

    @mcp.tool()
    def data_map_get_table_primary_keys(db: str = "", table: str = "") -> dict[str, Any]:
        """Get primary key fields for a table from Data Map."""

        return run_tool_safely(
            "data_map_get_table_primary_keys",
            lambda: dump_response(service.get_table_primary_keys(GetTablePrimaryKeysRequest(db=db, table=table))),
            {"db": db, "table": table},
        )
