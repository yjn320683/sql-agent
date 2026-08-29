from __future__ import annotations

from sql_agent_mcp_server.domains.hive_metadata.schemas import (
    ColumnMetadata,
    ColumnUsage,
    FindColumnUsageRequest,
    GetColumnsRequest,
    PartitionMetadata,
    TableMetadata,
    GetTableRequest,
    SearchTablesRequest,
)
from sql_agent_mcp_server.domains.hive_metadata.service import HiveMetadataService


class FakeHiveMetadataAdapter:
    source = "fake-hive-metastore"

    def __init__(self) -> None:
        self.tables = [
            TableMetadata(
                catalog="hive",
                db="dwd",
                table="orders",
                owner="data_platform",
                comment="订单明细事实表",
                columns=[
                    ColumnMetadata(name="order_id", dataType="string", nullable=False),
                    ColumnMetadata(name="user_id", dataType="string", nullable=False),
                    ColumnMetadata(name="dt", dataType="string", partitionKey=True),
                ],
                partitions=[PartitionMetadata(name="dt=2026-05-06", values={"dt": "2026-05-06"})],
            ),
            TableMetadata(
                catalog="hive",
                db="dim",
                table="users",
                columns=[ColumnMetadata(name="user_id", dataType="string", nullable=False)],
                partitions=[],
            ),
        ]

    def list_databases(self, *, catalog: str) -> list[str]:
        return sorted({item.db for item in self.tables if item.catalog == catalog})

    def search_tables(
        self,
        *,
        catalog: str,
        pattern: str,
        db: str | None,
        limit: int,
        offset: int,
    ) -> tuple[list[TableMetadata], int]:
        tables = [
            item
            for item in self.tables
            if item.catalog == catalog
            and (db is None or item.db == db)
            and (not pattern or pattern in item.table)
        ]
        return tables[offset : offset + limit], len(tables)

    def get_table(self, *, catalog: str, db: str, table: str) -> TableMetadata:
        for item in self.tables:
            if item.catalog == catalog and item.db == db and item.table == table:
                return item
        raise AssertionError("unexpected table")

    def get_columns(self, *, catalog: str, db: str, table: str) -> list[ColumnMetadata]:
        return self.get_table(catalog=catalog, db=db, table=table).columns or []

    def get_partitions(
        self,
        *,
        catalog: str,
        db: str,
        table: str,
        limit: int,
        offset: int,
    ) -> tuple[list[PartitionMetadata], int]:
        partitions = self.get_table(catalog=catalog, db=db, table=table).partitions or []
        return partitions[offset : offset + limit], len(partitions)

    def get_table_ddl(self, *, catalog: str, db: str, table: str) -> str:
        return f"CREATE TABLE `{db}`.`{table}` (`id` string);"

    def find_column_usage(
        self,
        *,
        catalog: str,
        column: str,
        db: str | None,
        table_pattern: str,
        limit: int,
        offset: int,
    ) -> tuple[list[ColumnUsage], int]:
        usages = [
            ColumnUsage(catalog=item.catalog, db=item.db, table=item.table, column=table_column)
            for item in self.tables
            if db is None or item.db == db
            if not table_pattern or table_pattern in item.table
            for table_column in item.columns or []
            if table_column.name == column
        ]
        return usages[offset : offset + limit], len(usages)


def test_service_search_tables_hides_large_fields_by_default() -> None:
    service = HiveMetadataService(FakeHiveMetadataAdapter())

    response = service.search_tables(SearchTablesRequest(pattern="orders"))

    assert response.total == 1
    assert response.items[0].table == "orders"
    assert response.items[0].columns is None
    assert response.items[0].partitions is None


def test_service_get_table_can_include_columns_and_partitions() -> None:
    service = HiveMetadataService(FakeHiveMetadataAdapter())

    response = service.get_table(
        GetTableRequest(db="dwd", table="orders", includeColumns=True, includePartitions=True)
    )

    assert response.table.columns
    assert response.table.partitions
    assert response.table.columns[0].name == "order_id"


def test_service_get_columns_returns_structured_facts() -> None:
    service = HiveMetadataService(FakeHiveMetadataAdapter())

    response = service.get_columns(GetColumnsRequest(db="dwd", table="orders"))

    assert response.source == "fake-hive-metastore"
    assert {column.name for column in response.columns} >= {"order_id", "dt"}


def test_service_find_column_usage_is_paginated() -> None:
    service = HiveMetadataService(FakeHiveMetadataAdapter())

    response = service.find_column_usage(FindColumnUsageRequest(column="user_id", limit=1))

    assert response.total == 2
    assert len(response.usages) == 1

def test_service_from_settings_uses_real_metastore_adapter() -> None:
    from sql_agent_mcp_server.domains.hive_metadata.adapters.metastore import RealHiveMetastoreAdapter
    from sql_agent_mcp_server.settings import Settings

    service = HiveMetadataService.from_settings(Settings(hive_metastore_uri="thrift://localhost:9083"))

    assert isinstance(service.adapter, RealHiveMetastoreAdapter)
