from __future__ import annotations

import pytest

from sql_agent_mcp_server.common.errors import McpDomainError, McpErrorCode
from sql_agent_mcp_server.domains.hive_metadata.adapters.metastore import RealHiveMetastoreAdapter


def test_parse_uri_accepts_thrift_uri() -> None:
    adapter = RealHiveMetastoreAdapter("thrift://metastore.example.com:9083")

    assert adapter._parse_uri() == ("metastore.example.com", 9083)


def test_parse_uri_accepts_host_port_without_scheme() -> None:
    adapter = RealHiveMetastoreAdapter("metastore.example.com:19083")

    assert adapter._parse_uri() == ("metastore.example.com", 19083)


def test_parse_uri_uses_first_metastore_uri() -> None:
    adapter = RealHiveMetastoreAdapter("thrift://meta-1:9083,thrift://meta-2:9083")

    assert adapter._parse_uri() == ("meta-1", 9083)


def test_parse_uri_requires_real_configuration() -> None:
    adapter = RealHiveMetastoreAdapter(None)

    with pytest.raises(McpDomainError) as exc_info:
        adapter._parse_uri()

    assert exc_info.value.code == McpErrorCode.DEPENDENCY_UNAVAILABLE


def test_table_conversion_maps_columns_and_partition_keys() -> None:
    from hmsclient.genthrift.hive_metastore.ttypes import FieldSchema, StorageDescriptor, Table

    raw_table = Table(
        dbName="dwd",
        tableName="orders",
        owner="data_platform",
        sd=StorageDescriptor(
            cols=[FieldSchema(name="order_id", type="string", comment="订单 ID")],
            location="hdfs://warehouse/dwd/orders",
        ),
        partitionKeys=[FieldSchema(name="dt", type="string", comment="业务日期")],
        parameters={"comment": "订单明细事实表"},
        tableType="MANAGED_TABLE",
    )

    table = RealHiveMetastoreAdapter._to_table_metadata(raw_table)

    assert table.db == "dwd"
    assert table.table == "orders"
    assert table.location == "hdfs://warehouse/dwd/orders"
    assert [column.name for column in table.columns or []] == ["order_id", "dt"]
    assert table.columns[-1].partition_key is True


def test_table_conversion_extracts_storage_descriptor_fields() -> None:
    from hmsclient.genthrift.hive_metastore.ttypes import FieldSchema, SerDeInfo, StorageDescriptor, Table

    raw_table = Table(
        dbName="dw",
        tableName="sales",
        owner="etl",
        sd=StorageDescriptor(
            cols=[FieldSchema(name="amount", type="decimal(18,2)", comment=None)],
            location="hdfs://ns/user/hive/warehouse/dw.db/sales",
            inputFormat="org.apache.hadoop.hive.ql.io.orc.OrcInputFormat",
            outputFormat="org.apache.hadoop.hive.ql.io.orc.OrcOutputFormat",
            serdeInfo=SerDeInfo(
                serializationLib="org.apache.hadoop.hive.ql.io.orc.OrcSerde",
            ),
        ),
        partitionKeys=[],
        parameters={"bucketing_version": "2", "transient_lastDdlTime": "1634798695"},
        tableType="MANAGED_TABLE",
    )

    table = RealHiveMetastoreAdapter._to_table_metadata(raw_table)

    assert table.serde_class == "org.apache.hadoop.hive.ql.io.orc.OrcSerde"
    assert table.input_format == "org.apache.hadoop.hive.ql.io.orc.OrcInputFormat"
    assert table.output_format == "org.apache.hadoop.hive.ql.io.orc.OrcOutputFormat"
    assert table.tbl_properties == {"bucketing_version": "2", "transient_lastDdlTime": "1634798695"}


def test_table_conversion_excludes_comment_from_tbl_properties() -> None:
    from hmsclient.genthrift.hive_metastore.ttypes import FieldSchema, StorageDescriptor, Table

    raw_table = Table(
        dbName="dw",
        tableName="sales",
        owner="etl",
        sd=StorageDescriptor(
            cols=[FieldSchema(name="id", type="bigint", comment=None)],
            location="hdfs://warehouse/dw/sales",
        ),
        partitionKeys=[],
        parameters={"comment": "sales table", "bucketing_version": "2"},
        tableType="MANAGED_TABLE",
    )

    table = RealHiveMetastoreAdapter._to_table_metadata(raw_table)

    assert table.comment == "sales table"
    assert table.tbl_properties is not None
    assert "comment" not in table.tbl_properties
    assert table.tbl_properties == {"bucketing_version": "2"}


def test_ddl_column_line_basic() -> None:
    from sql_agent_mcp_server.domains.hive_metadata.schemas import ColumnMetadata

    col = ColumnMetadata(name="amount", dataType="decimal(18,2)")
    assert RealHiveMetastoreAdapter._ddl_column_line(col) == "  `amount` decimal(18,2)"


def test_ddl_column_line_with_comment_and_escaping() -> None:
    from sql_agent_mcp_server.domains.hive_metadata.schemas import ColumnMetadata

    col = ColumnMetadata(name="note", dataType="string", comment="user's note")
    assert RealHiveMetastoreAdapter._ddl_column_line(col) == "  `note` string COMMENT 'user\\'s note'"


def test_get_table_ddl_includes_serde_format_and_tblproperties() -> None:
    from sql_agent_mcp_server.domains.hive_metadata.schemas import ColumnMetadata, TableMetadata

    metadata = TableMetadata(
        db="dw",
        table="sales",
        columns=[ColumnMetadata(name="amount", dataType="decimal(18,2)")],
        location="hdfs://ns/user/hive/warehouse/dw.db/sales",
        serdeClass="org.apache.hadoop.hive.ql.io.orc.OrcSerde",
        inputFormat="org.apache.hadoop.hive.ql.io.orc.OrcInputFormat",
        outputFormat="org.apache.hadoop.hive.ql.io.orc.OrcOutputFormat",
        tblProperties={"bucketing_version": "2", "transient_lastDdlTime": "1634798695"},
    )

    adapter = RealHiveMetastoreAdapter.__new__(RealHiveMetastoreAdapter)
    ddl = adapter._build_ddl(metadata)

    expected = """CREATE TABLE `dw`.`sales` (
  `amount` decimal(18,2)
)
ROW FORMAT SERDE
  'org.apache.hadoop.hive.ql.io.orc.OrcSerde'
STORED AS INPUTFORMAT
  'org.apache.hadoop.hive.ql.io.orc.OrcInputFormat'
OUTPUTFORMAT
  'org.apache.hadoop.hive.ql.io.orc.OrcOutputFormat'
LOCATION
  'hdfs://ns/user/hive/warehouse/dw.db/sales'
TBLPROPERTIES (
  'bucketing_version'='2',
  'transient_lastDdlTime'='1634798695');"""

    assert ddl == expected
