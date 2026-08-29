"""Hive 元数据领域服务。"""

from __future__ import annotations

from sql_agent_mcp_server.domains.hive_metadata.adapters.metastore import HiveMetadataAdapter, RealHiveMetastoreAdapter
from sql_agent_mcp_server.domains.hive_metadata.schemas import (
    ColumnUsageResponse,
    ColumnsResponse,
    DatabaseListResponse,
    DdlResponse,
    FindColumnUsageRequest,
    GetColumnsRequest,
    GetPartitionsRequest,
    GetTableDdlRequest,
    GetTableRequest,
    GetTableStatisticsRequest,
    ListDatabasesRequest,
    PartitionsResponse,
    SearchTablesRequest,
    SearchTablesResponse,
    TableResponse,
    TableStatisticsResponse,
)
from sql_agent_mcp_server.settings import Settings


class HiveMetadataService:
    """组织 Hive 元数据查询语义，不关心底层访问协议。"""

    def __init__(self, adapter: HiveMetadataAdapter) -> None:
        self.adapter = adapter

    @classmethod
    def from_settings(cls, settings: Settings) -> "HiveMetadataService":
        return cls(
            RealHiveMetastoreAdapter(
                settings.hive_metastore_uri,
                settings.hive_metastore_db_uri,
                timeout_seconds=settings.mcp_tool_timeout_seconds,
            )
        )

    def list_databases(self, request: ListDatabasesRequest) -> DatabaseListResponse:
        databases = self.adapter.list_databases(catalog=request.catalog)
        return DatabaseListResponse(source=self.adapter.source, databases=databases)

    def search_tables(self, request: SearchTablesRequest) -> SearchTablesResponse:
        tables, total = self.adapter.search_tables(
            catalog=request.catalog,
            pattern=request.pattern,
            db=request.db,
            limit=request.limit,
            offset=request.offset,
        )
        page = [self._shape_table(item, request.include_columns, False) for item in tables]
        warnings = []
        if request.include_partitions:
            warnings.append("搜索结果不内联分区；请对目标表调用 hive_get_partitions。")
        return SearchTablesResponse(
            source=self.adapter.source,
            items=page,
            total=total,
            limit=request.limit,
            offset=request.offset,
            warnings=warnings,
        )

    def get_table(self, request: GetTableRequest) -> TableResponse:
        table = self.adapter.get_table(catalog=request.catalog, db=request.db, table=request.table)
        warnings = []
        if request.include_partitions:
            partitions, total = self.adapter.get_partitions(
                catalog=request.catalog, db=request.db, table=request.table, limit=20, offset=0
            )
            table.partitions = partitions
            if total > len(partitions):
                warnings.append(f"仅内联前 {len(partitions)} 个分区，共 {total} 个；完整结果请分页调用 hive_get_partitions。")
        return TableResponse(
            source=self.adapter.source,
            table=self._shape_table(table, request.include_columns, request.include_partitions),
            warnings=warnings,
        )

    def get_columns(self, request: GetColumnsRequest) -> ColumnsResponse:
        columns = self.adapter.get_columns(catalog=request.catalog, db=request.db, table=request.table)
        return ColumnsResponse(source=self.adapter.source, columns=columns)

    def get_partitions(self, request: GetPartitionsRequest) -> PartitionsResponse:
        page, total = self.adapter.get_partitions(
            catalog=request.catalog,
            db=request.db,
            table=request.table,
            limit=request.limit,
            offset=request.offset,
        )
        return PartitionsResponse(
            source=self.adapter.source,
            partitions=page,
            total=total,
            limit=request.limit,
            offset=request.offset,
        )

    def get_table_ddl(self, request: GetTableDdlRequest) -> DdlResponse:
        ddl = self.adapter.get_table_ddl(catalog=request.catalog, db=request.db, table=request.table)
        return DdlResponse(source=self.adapter.source, ddl=ddl)

    def find_column_usage(self, request: FindColumnUsageRequest) -> ColumnUsageResponse:
        usages, total = self.adapter.find_column_usage(
            catalog=request.catalog,
            column=request.column,
            db=request.db,
            table_pattern=request.table_pattern,
            limit=request.limit,
            offset=request.offset,
        )
        return ColumnUsageResponse(
            source=self.adapter.source,
            usages=usages,
            total=total,
            limit=request.limit,
            offset=request.offset,
        )

    def get_table_statistics(self, request: GetTableStatisticsRequest) -> TableStatisticsResponse:
        table_stats, partition_stats, column_stats, warnings = self.adapter.get_table_statistics(
            catalog=request.catalog,
            db=request.db,
            table=request.table,
            partitions=request.partitions,
            columns=request.columns,
        )
        missing_reasons = []
        if not table_stats:
            missing_reasons.append("table_statistics_missing")
        if request.columns and len(column_stats) < len(request.columns):
            missing_reasons.append("column_statistics_partial")
        return TableStatisticsResponse(
            source=self.adapter.source,
            tableStatistics=table_stats,
            partitionStatistics=partition_stats,
            columnStatistics=column_stats,
            warnings=warnings,
            complete=not missing_reasons,
            missingReasons=missing_reasons,
        )

    @staticmethod
    def _shape_table(table, include_columns: bool, include_partitions: bool):
        shaped = table.model_copy(deep=True)
        if not include_columns:
            shaped.columns = None
        if not include_partitions:
            shaped.partitions = None
        return shaped
