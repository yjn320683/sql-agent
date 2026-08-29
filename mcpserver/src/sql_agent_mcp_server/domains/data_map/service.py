"""Data map 领域服务。"""

from __future__ import annotations

from sql_agent_mcp_server.domains.data_map.adapters.table_detail import TableDetailInfoAdapter
from sql_agent_mcp_server.domains.data_map.schemas import GetTablePrimaryKeysRequest, TablePrimaryKeysResponse
from sql_agent_mcp_server.settings import Settings


class DataMapService:
    """组织 Data Map 查询语义。"""

    def __init__(self, adapter: TableDetailInfoAdapter) -> None:
        self.adapter = adapter

    @classmethod
    def from_settings(cls, settings: Settings) -> "DataMapService":
        return cls(
            TableDetailInfoAdapter(
                settings.data_map_db_uri,
                timeout_seconds=settings.mcp_tool_timeout_seconds,
            )
        )

    def get_table_primary_keys(self, request: GetTablePrimaryKeysRequest) -> TablePrimaryKeysResponse:
        result = self.adapter.get_table_primary_keys(db_name=request.db, table_name=request.table)
        return TablePrimaryKeysResponse(source=self.adapter.source, result=result)
