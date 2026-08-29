from __future__ import annotations

from contextlib import contextmanager
from types import SimpleNamespace

from sql_agent_mcp_server.domains.hive_metadata.adapters.metastore import RealHiveMetastoreAdapter


class TApplicationException(Exception):
    pass


def test_unsupported_column_statistics_keeps_real_table_statistics() -> None:
    client = SimpleNamespace(
        get_table=lambda _db, _table: SimpleNamespace(parameters={"numRows": "12"}),
        get_table_column_statistics=lambda _db, _table, _column: (_ for _ in ()).throw(
            TApplicationException("get_table_column_statistics failed: unknown result")
        ),
    )
    adapter = RealHiveMetastoreAdapter("thrift://unused:9083")

    @contextmanager
    def fake_client():
        yield client

    adapter._client = fake_client

    table_stats, partition_stats, column_stats, warnings = adapter.get_table_statistics(
        catalog="hive", db="default", table="orders", partitions=[], columns=["id", "name"]
    )

    assert table_stats == {"rowCount": 12}
    assert partition_stats == []
    assert column_stats == []
    assert warnings == ["当前 Hive Metastore 不支持列统计 RPC，仅返回表参数统计。"]


def test_table_parameter_statistics_use_stable_names() -> None:
    result = RealHiveMetastoreAdapter._parameter_statistics({
        "numRows": "100",
        "totalSize": "4096",
        "numFiles": "4",
        "COLUMN_STATS_ACCURATE": "true",
    })

    assert result == {
        "rowCount": 100,
        "totalSizeBytes": 4096,
        "fileCount": 4,
        "columnStatsAccurate": "true",
    }


def test_column_statistics_extract_only_standard_hive_fields() -> None:
    raw = SimpleNamespace(
        statsObj=[SimpleNamespace(
            colName="user_id",
            colType="bigint",
            lastAnalyzed=123,
            statsData=SimpleNamespace(
                longStats=SimpleNamespace(numNulls=3, numDVs=90, lowValue=1, highValue=100),
                doubleStats=None,
                stringStats=None,
                binaryStats=None,
                booleanStats=None,
                decimalStats=None,
                dateStats=None,
                timestampStats=None,
            ),
        )]
    )

    result = RealHiveMetastoreAdapter._column_statistics(raw)

    assert result["column"] == "user_id"
    assert result["nullCount"] == 3
    assert result["distinctCount"] == 90
    assert result["min"] == "1"
    assert result["max"] == "100"
    assert result["lastAnalyzedEpochSeconds"] == 123
