"""Hive metadata MCP tools."""

from __future__ import annotations

from typing import Any

from mcp.server.fastmcp import FastMCP

from sql_agent_mcp_server.common.logging import run_tool_safely
from sql_agent_mcp_server.common.schemas import dump_response
from sql_agent_mcp_server.domains.hive_metadata.schemas import (
    FindColumnUsageRequest,
    GetColumnsRequest,
    GetPartitionsRequest,
    GetTableDdlRequest,
    GetTableRequest,
    GetTableStatisticsRequest,
    ListDatabasesRequest,
    SearchTablesRequest,
)
from sql_agent_mcp_server.domains.hive_metadata.service import HiveMetadataService


def register_hive_metadata_tools(mcp: FastMCP, service: HiveMetadataService) -> None:
    """注册 Hive 元数据 MCP tools。"""

    @mcp.tool()
    def hive_list_databases(catalog: str = "hive") -> dict[str, Any]:
        """List Hive databases in a catalog."""

        return run_tool_safely(
            "hive_list_databases",
            lambda: dump_response(service.list_databases(ListDatabasesRequest(catalog=catalog))),
            {"catalog": catalog},
        )

    @mcp.tool()
    def hive_search_tables(
        catalog: str = "hive",
        pattern: str = "",
        db: str | None = None,
        limit: int = 20,
        offset: int = 0,
        includeColumns: bool = False,
        includePartitions: bool = False,
    ) -> dict[str, Any]:
        """Search Hive tables by database and table-name substring."""

        return run_tool_safely(
            "hive_search_tables",
            lambda: dump_response(
                service.search_tables(
                    SearchTablesRequest(
                        catalog=catalog,
                        pattern=pattern,
                        db=db,
                        limit=limit,
                        offset=offset,
                        includeColumns=includeColumns,
                        includePartitions=includePartitions,
                    )
                )
            ),
            {"catalog": catalog, "pattern": pattern, "db": db, "limit": limit, "offset": offset,
             "includeColumns": includeColumns, "includePartitions": includePartitions},
        )

    @mcp.tool()
    def hive_get_table(
        catalog: str = "hive",
        db: str = "",
        table: str = "",
        includeColumns: bool = False,
        includePartitions: bool = False,
    ) -> dict[str, Any]:
        """Get Hive table metadata."""

        return run_tool_safely(
            "hive_get_table",
            lambda: dump_response(
                service.get_table(
                    GetTableRequest(
                        catalog=catalog,
                        db=db,
                        table=table,
                        includeColumns=includeColumns,
                        includePartitions=includePartitions,
                    )
                )
            ),
            {"catalog": catalog, "db": db, "table": table,
             "includeColumns": includeColumns, "includePartitions": includePartitions},
        )

    @mcp.tool()
    def hive_get_columns(catalog: str = "hive", db: str = "", table: str = "") -> dict[str, Any]:
        """Get columns of a Hive table."""

        return run_tool_safely(
            "hive_get_columns",
            lambda: dump_response(service.get_columns(GetColumnsRequest(catalog=catalog, db=db, table=table))),
            {"catalog": catalog, "db": db, "table": table},
        )

    @mcp.tool()
    def hive_get_partitions(
        catalog: str = "hive",
        db: str = "",
        table: str = "",
        limit: int = 20,
        offset: int = 0,
    ) -> dict[str, Any]:
        """Get partitions of a Hive table."""

        return run_tool_safely(
            "hive_get_partitions",
            lambda: dump_response(
                service.get_partitions(
                    GetPartitionsRequest(catalog=catalog, db=db, table=table, limit=limit, offset=offset)
                )
            ),
            {"catalog": catalog, "db": db, "table": table, "limit": limit, "offset": offset},
        )

    @mcp.tool()
    def hive_get_table_ddl(catalog: str = "hive", db: str = "", table: str = "") -> dict[str, Any]:
        """Get Hive table DDL."""

        return run_tool_safely(
            "hive_get_table_ddl",
            lambda: dump_response(service.get_table_ddl(GetTableDdlRequest(catalog=catalog, db=db, table=table))),
            {"catalog": catalog, "db": db, "table": table},
        )

    @mcp.tool()
    def hive_find_column_usage(
        catalog: str = "hive",
        column: str = "",
        db: str | None = None,
        tablePattern: str = "",
        limit: int = 20,
        offset: int = 0,
    ) -> dict[str, Any]:
        """Find Hive tables containing a column."""

        return run_tool_safely(
            "hive_find_column_usage",
            lambda: dump_response(
                service.find_column_usage(
                    FindColumnUsageRequest(
                        catalog=catalog,
                        column=column,
                        db=db,
                        tablePattern=tablePattern,
                        limit=limit,
                        offset=offset,
                    )
                )
            ),
            {"catalog": catalog, "column": column, "db": db, "tablePattern": tablePattern,
             "limit": limit, "offset": offset},
        )

    @mcp.tool()
    def hive_table_statistics_get(
        catalog: str = "hive",
        db: str = "",
        table: str = "",
        partitions: list[str] | None = None,
        columns: list[str] | None = None,
    ) -> dict[str, Any]:
        """Get table, selected partition, and selected column statistics from Hive Metastore."""

        return run_tool_safely(
            "hive_table_statistics_get",
            lambda: dump_response(
                service.get_table_statistics(
                    GetTableStatisticsRequest(
                        catalog=catalog,
                        db=db,
                        table=table,
                        partitions=partitions or [],
                        columns=columns or [],
                    )
                )
            ),
            {"catalog": catalog, "db": db, "table": table,
             "partitionCount": len(partitions or []), "columnCount": len(columns or [])},
        )
