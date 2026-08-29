from __future__ import annotations

import pytest
from sqlalchemy import create_engine, text

from sql_agent_mcp_server.common.errors import McpDomainError, McpErrorCode
from sql_agent_mcp_server.domains.hive_metadata.adapters.metastore_search import (
    MetastoreSqlSearchAdapter,
    _sanitize_error_message,
)


@pytest.fixture()
def search_adapter() -> MetastoreSqlSearchAdapter:
    engine = create_engine("sqlite:///:memory:")
    with engine.begin() as conn:
        conn.execute(text("CREATE TABLE DBS (DB_ID INTEGER PRIMARY KEY, NAME TEXT NOT NULL)"))
        conn.execute(text("CREATE TABLE TBLS (TBL_ID INTEGER PRIMARY KEY, DB_ID INTEGER NOT NULL, TBL_NAME TEXT NOT NULL, SD_ID INTEGER NOT NULL)"))
        conn.execute(text("CREATE TABLE SDS (SD_ID INTEGER PRIMARY KEY, CD_ID INTEGER NOT NULL)"))
        conn.execute(text("CREATE TABLE COLUMNS_V2 (CD_ID INTEGER NOT NULL, COLUMN_NAME TEXT NOT NULL, TYPE_NAME TEXT NOT NULL, COMMENT TEXT)"))
        conn.execute(text("CREATE TABLE PARTITION_KEYS (TBL_ID INTEGER NOT NULL, PKEY_NAME TEXT NOT NULL, PKEY_TYPE TEXT NOT NULL, PKEY_COMMENT TEXT)"))
        conn.execute(text("INSERT INTO DBS (DB_ID, NAME) VALUES (1, 'dwd'), (2, 'dim')"))
        conn.execute(text("INSERT INTO TBLS (TBL_ID, DB_ID, TBL_NAME, SD_ID) VALUES (10, 1, 'orders', 100), (20, 2, 'users', 200), (30, 1, 'order_items', 300)"))
        conn.execute(text("INSERT INTO SDS (SD_ID, CD_ID) VALUES (100, 1000), (200, 2000), (300, 3000)"))
        conn.execute(text("INSERT INTO COLUMNS_V2 (CD_ID, COLUMN_NAME, TYPE_NAME, COMMENT) VALUES (1000, 'user_id', 'string', '用户 ID'), (2000, 'user_id', 'string', '用户 ID'), (3000, 'sku_id', 'string', 'SKU ID')"))
        conn.execute(text("INSERT INTO PARTITION_KEYS (TBL_ID, PKEY_NAME, PKEY_TYPE, PKEY_COMMENT) VALUES (10, 'dt', 'string', '业务日期'), (30, 'dt', 'string', '业务日期')"))
    return MetastoreSqlSearchAdapter(None, engine=engine)


def test_find_column_usage_finds_normal_columns(search_adapter: MetastoreSqlSearchAdapter) -> None:
    usages, total = search_adapter.find_column_usage(
        catalog="hive",
        column="user_id",
        db=None,
        table_pattern="",
        limit=20,
        offset=0,
    )

    assert total == 2
    assert [(item.db, item.table, item.column.name) for item in usages] == [
        ("dim", "users", "user_id"),
        ("dwd", "orders", "user_id"),
    ]
    assert usages[0].column.partition_key is False


def test_find_column_usage_finds_partition_columns(search_adapter: MetastoreSqlSearchAdapter) -> None:
    usages, total = search_adapter.find_column_usage(
        catalog="hive",
        column="dt",
        db="dwd",
        table_pattern="",
        limit=20,
        offset=0,
    )

    assert total == 2
    assert {item.table for item in usages} == {"orders", "order_items"}
    assert all(item.column.partition_key for item in usages)


def test_find_column_usage_filters_table_pattern(search_adapter: MetastoreSqlSearchAdapter) -> None:
    usages, total = search_adapter.find_column_usage(
        catalog="hive",
        column="dt",
        db="dwd",
        table_pattern="items",
        limit=20,
        offset=0,
    )

    assert total == 1
    assert usages[0].table == "order_items"


def test_find_column_usage_paginates(search_adapter: MetastoreSqlSearchAdapter) -> None:
    usages, total = search_adapter.find_column_usage(
        catalog="hive",
        column="user_id",
        db=None,
        table_pattern="",
        limit=1,
        offset=1,
    )

    assert total == 2
    assert len(usages) == 1
    assert usages[0].table == "orders"


def test_find_column_usage_requires_db_uri_without_injected_engine() -> None:
    adapter = MetastoreSqlSearchAdapter(None)

    with pytest.raises(McpDomainError) as exc_info:
        adapter.find_column_usage(catalog="hive", column="user_id", db=None, table_pattern="", limit=20, offset=0)

    assert exc_info.value.code == McpErrorCode.DEPENDENCY_UNAVAILABLE
    assert exc_info.value.details["required"] == "HIVE_METASTORE_DB_URI"


def test_sanitize_error_message_masks_url_password() -> None:
    pymysql_prefix = "mysql+pymysql://user:"
    pymysql_secret = "XxgtE76z8BeH^w*I"
    pymysql_host = "@mysql.example.com:3306/hive"
    mysql_prefix = "mysql://user:"
    mysql_secret = "p%40ss"
    mysql_host = "@127.0.0.1:3306/db"
    message = (
        "连接失败 "
        + pymysql_prefix
        + pymysql_secret
        + pymysql_host
        + " and "
        + mysql_prefix
        + mysql_secret
        + mysql_host
    )

    sanitized = _sanitize_error_message(message)

    assert pymysql_secret not in sanitized
    assert mysql_secret not in sanitized
    assert pymysql_prefix + "******" + pymysql_host in sanitized
    assert mysql_prefix + "******" + mysql_host in sanitized
