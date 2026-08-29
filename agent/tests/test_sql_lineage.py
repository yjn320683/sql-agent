from __future__ import annotations

import pytest

from app.domain.sql.lineage import analyze_sql_lineage


def test_insert_lineage_distinguishes_output_and_inputs() -> None:
    result = analyze_sql_lineage(
        """
        INSERT OVERWRITE TABLE dw.order_summary PARTITION (dt='2026-08-24')
        SELECT a.id
        FROM ods.orders a
        JOIN ods.users u ON a.user_id = u.id
        """,
        default_db="default",
    )

    assert [item["qualifiedName"] for item in result["outputs"]] == ["dw.order_summary"]
    assert [item["qualifiedName"] for item in result["inputs"]] == ["ods.orders", "ods.users"]
    assert result["complete"] is True


def test_cte_references_are_not_reported_as_physical_tables() -> None:
    result = analyze_sql_lineage(
        """
        WITH base AS (SELECT id FROM ods.orders),
             joined AS (SELECT b.id FROM base b JOIN ods.users u ON b.id=u.id)
        SELECT * FROM joined
        """,
        default_db="dw",
    )

    assert [item["qualifiedName"] for item in result["inputs"]] == ["ods.orders", "ods.users"]
    assert [item["name"] for item in result["ctes"]] == ["base", "joined"]
    assert result["ctes"][1]["dependencies"] == [
        {"kind": "cte", "name": "base"},
        {"kind": "table", "catalog": None, "db": "ods", "table": "users", "qualifiedName": "ods.users"},
    ]


def test_cte_dependency_deduplication_keeps_distinct_ctes() -> None:
    result = analyze_sql_lineage(
        """
        WITH orders AS (SELECT id FROM ods.orders),
             users AS (SELECT id FROM ods.users),
             combined AS (
                 SELECT o.id FROM orders o
                 JOIN users u ON o.id = u.id
                 JOIN orders duplicate_o ON o.id = duplicate_o.id
             )
        SELECT * FROM combined
        """
    )

    combined = next(item for item in result["ctes"] if item["name"] == "combined")
    assert combined["dependencies"] == [
        {"kind": "cte", "name": "orders"},
        {"kind": "cte", "name": "users"},
    ]


def test_unqualified_table_without_default_database_is_incomplete() -> None:
    result = analyze_sql_lineage("SELECT * FROM orders")

    assert result["inputs"][0]["db"] is None
    assert result["complete"] is False
    assert result["missingReasons"] == ["unqualified_database"]


def test_invalid_sql_is_rejected() -> None:
    with pytest.raises(ValueError, match="Hive SQL 解析失败"):
        analyze_sql_lineage("SELECT FROM")


def test_insert_can_read_from_same_physical_target_table() -> None:
    result = analyze_sql_lineage(
        "insert overwrite table dw.snapshot select * from dw.snapshot"
    )

    assert [item["qualifiedName"] for item in result["outputs"]] == ["dw.snapshot"]
    assert [item["qualifiedName"] for item in result["inputs"]] == ["dw.snapshot"]


def test_task_step_markers_are_split_before_lineage_parsing() -> None:
    result = analyze_sql_lineage(
        """==== Step: 1: read orders ====
        SELECT * FROM ods.orders
        ==== Step: 2: write summary ====
        INSERT OVERWRITE TABLE dw.order_summary SELECT * FROM ods.orders"""
    )

    assert result["statementCount"] == 2
    assert [item["qualifiedName"] for item in result["inputs"]] == ["ods.orders"]
    assert [item["qualifiedName"] for item in result["outputs"]] == ["dw.order_summary"]
