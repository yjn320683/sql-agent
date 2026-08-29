from __future__ import annotations

from types import SimpleNamespace

from app.domain.sql.completion import SqlCompletionService


class MetadataService:
    def list_databases(self, request):
        return SimpleNamespace(databases=["default", "dw"])

    def search_tables(self, request):
        items = [SimpleNamespace(db="dw", table="orders")] if request.db in {None, "dw"} else []
        return SimpleNamespace(items=items)

    def get_columns(self, request):
        return SimpleNamespace(columns=[
            SimpleNamespace(name="order_id", data_type="bigint", comment="订单ID"),
            SimpleNamespace(name="amount", data_type="decimal(18,2)", comment="金额"),
        ])


class ExecutionService:
    def search_functions(self, request):
        return SimpleNamespace(items=["date_add"])


def test_alias_dot_uses_real_table_columns_while_sql_is_incomplete() -> None:
    service = SqlCompletionService(MetadataService(), ExecutionService())
    sql = "select o. from dw.orders o"

    result = service.complete(sql, len("select o."), "default", 100)

    assert [item["label"] for item in result["items"]] == ["order_id", "amount"]
    assert result["items"][0]["detail"] == "o · bigint"


def test_database_dot_only_returns_real_tables() -> None:
    service = SqlCompletionService(MetadataService(), ExecutionService())
    sql = "select * from dw."

    result = service.complete(sql, len(sql), "default", 100)

    assert [item["label"] for item in result["items"]] == ["orders"]
    assert result["items"][0]["detail"] == "dw.orders"


def test_unknown_qualifier_does_not_guess_objects() -> None:
    service = SqlCompletionService(MetadataService(), ExecutionService())
    sql = "select missing."

    result = service.complete(sql, len(sql), "default", 100)

    assert result["items"] == []
