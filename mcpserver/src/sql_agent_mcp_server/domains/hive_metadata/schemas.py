"""Hive 元数据 Schema。"""

from __future__ import annotations

from pydantic import BaseModel, Field

from sql_agent_mcp_server.common.schemas import DiagnosticFactResponse, FactResponse, PageRequest


class CatalogRequest(BaseModel):
    catalog: str = Field(default="hive", min_length=1)


class TableIdentifier(CatalogRequest):
    db: str = Field(min_length=1)
    table: str = Field(min_length=1)


class ListDatabasesRequest(CatalogRequest):
    pass


class SearchTablesRequest(CatalogRequest, PageRequest):
    pattern: str = Field(default="")
    db: str | None = Field(default=None)
    include_columns: bool = Field(default=False, alias="includeColumns")
    include_partitions: bool = Field(default=False, alias="includePartitions")


class GetTableRequest(TableIdentifier):
    include_columns: bool = Field(default=False, alias="includeColumns")
    include_partitions: bool = Field(default=False, alias="includePartitions")


class GetColumnsRequest(TableIdentifier):
    pass


class GetPartitionsRequest(TableIdentifier, PageRequest):
    pass


class GetTableDdlRequest(TableIdentifier):
    pass


class FindColumnUsageRequest(CatalogRequest, PageRequest):
    column: str = Field(min_length=1)
    db: str | None = Field(default=None)
    table_pattern: str = Field(default="", alias="tablePattern")


class GetTableStatisticsRequest(TableIdentifier):
    partitions: list[str] = Field(default_factory=list, max_length=50)
    columns: list[str] = Field(default_factory=list, max_length=50)


class ColumnMetadata(BaseModel):
    name: str
    data_type: str = Field(alias="dataType")
    comment: str | None = None
    nullable: bool = True
    partition_key: bool = Field(default=False, alias="partitionKey")


class PartitionMetadata(BaseModel):
    name: str
    values: dict[str, str]
    location: str | None = None


class TableMetadata(BaseModel):
    catalog: str = "hive"
    db: str
    table: str
    owner: str | None = None
    comment: str | None = None
    location: str | None = None
    table_type: str = Field(default="MANAGED_TABLE", alias="tableType")
    columns: list[ColumnMetadata] | None = None
    partitions: list[PartitionMetadata] | None = None
    serde_class: str | None = Field(default=None, alias="serdeClass")
    input_format: str | None = Field(default=None, alias="inputFormat")
    output_format: str | None = Field(default=None, alias="outputFormat")
    tbl_properties: dict[str, str] | None = Field(default=None, alias="tblProperties")


class DatabaseListResponse(FactResponse):
    databases: list[str]


class SearchTablesResponse(FactResponse):
    items: list[TableMetadata]
    total: int
    limit: int
    offset: int


class TableResponse(FactResponse):
    table: TableMetadata


class ColumnsResponse(FactResponse):
    columns: list[ColumnMetadata]


class PartitionsResponse(FactResponse):
    partitions: list[PartitionMetadata]
    total: int
    limit: int
    offset: int


class DdlResponse(FactResponse):
    ddl: str


class ColumnUsage(BaseModel):
    catalog: str = "hive"
    db: str
    table: str
    column: ColumnMetadata


class ColumnUsageResponse(FactResponse):
    usages: list[ColumnUsage]
    total: int
    limit: int
    offset: int


class TableStatisticsResponse(DiagnosticFactResponse):
    table_statistics: dict = Field(default_factory=dict, alias="tableStatistics")
    partition_statistics: list[dict] = Field(default_factory=list, alias="partitionStatistics")
    column_statistics: list[dict] = Field(default_factory=list, alias="columnStatistics")
