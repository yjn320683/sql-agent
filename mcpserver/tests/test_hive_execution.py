from __future__ import annotations

import asyncio

import pytest
from mcp.server.fastmcp import FastMCP

from sql_agent_mcp_server.common.errors import McpDomainError
from sql_agent_mcp_server.domains.hive_execution.adapter import (
    ExplainResult,
    FunctionDetailResult,
    FunctionSearchResult,
    HiveCompilationError,
    HiveServer2Adapter,
    _compact_plan,
    _has_multiple_statements,
    _is_describable_function,
    _parse_function_properties,
    _sanitize_compilation_error,
    _validate_explain_input,
)
from sql_agent_mcp_server.domains.hive_execution.schemas import (
    HiveExplainRequest,
    HiveFunctionDetailRequest,
    HiveFunctionSearchRequest,
    HiveSqlRequest,
)
from sql_agent_mcp_server.domains.hive_execution.service import HiveExecutionService
from sql_agent_mcp_server.domains.hive_execution.tools import register_hive_execution_tools


class FakeHiveServer2Adapter:
    source = "fake-hive-server2"

    def __init__(self, error: Exception | None = None) -> None:
        self.error = error

    def explain(self, sql: str, *, default_db: str | None, extended: bool) -> ExplainResult:
        self.last_sql = sql
        if self.error:
            raise self.error
        return ExplainResult("STAGE DEPENDENCIES", default_db or "default", 12, False)

    def search_functions(self, *, keyword: str, limit: int, offset: int, default_db: str | None):
        return FunctionSearchResult(["count", "count_if"], 2, limit, offset, 8, default_db or "default")

    def get_function(self, name: str, *, default_db: str | None):
        return FunctionDetailResult(
            name,
            [f"Function: {name}", "Class: org.apache.hadoop.hive.ql.udf.UDFAbs", "Usage: abs(x)"],
            {"function": name, "class": "org.apache.hadoop.hive.ql.udf.UDFAbs", "usage": "abs(x)"},
            9,
            default_db or "default",
            False,
        )


class FakeSqlTaskService:
    def __init__(self, sql: str) -> None:
        self.sql = sql
        self.requested_version_no = None

    def get_task(self, task_id: int, version_no: int | None = None) -> dict:
        self.requested_version_no = version_no
        return {"ok": True, "task": {"id": task_id, "sql": self.sql}}


def test_hive_uri_parses_single_compact_connection_value() -> None:
    adapter = HiveServer2Adapter("hive://hs2.internal:10001/warehouse?auth=NOSASL", timeout_seconds=10)

    result = adapter._connection_info(None)

    assert result == {
        "host": "hs2.internal",
        "port": 10001,
        "database": "warehouse",
        "username": None,
        "auth": "NOSASL",
    }


def test_hive_uri_rejects_authenticated_modes() -> None:
    adapter = HiveServer2Adapter("hive://hs2:10000/default?auth=KERBEROS", timeout_seconds=10)

    with pytest.raises(McpDomainError) as exc_info:
        adapter._connection_info(None)

    assert exc_info.value.code == "invalid_request"


def test_explain_input_rejects_multiple_statements_and_analyze() -> None:
    with pytest.raises(McpDomainError):
        _validate_explain_input("select 1; select 2")
    with pytest.raises(McpDomainError):
        _validate_explain_input("EXPLAIN ANALYZE select 1")
    _validate_explain_input("select ';' as value;")
    assert _has_multiple_statements("select ';' as value;") is False


def test_compilation_error_redacts_endpoint() -> None:
    message = _sanitize_compilation_error("failed at thrift://secret-host:10000/default with parser error")

    assert "secret-host" not in message
    assert "parser error" in message


def test_compilation_error_keeps_business_message_without_server_stack() -> None:
    raw = (
        "TExecuteStatementResp(status=TStatus(infoMessages=["
        "'*org.apache.hive.service.cli.HiveSQLException:Error while compiling statement: "
        "FAILED: HiveAccessControlException Permission denied on tmp.orders:28:27', "
        "'org.apache.hive.service.cli.operation.Operation:toSQLException:Operation.java:335'])"
    )

    message = _sanitize_compilation_error(raw)

    assert message == (
        "Error while compiling statement: FAILED: HiveAccessControlException "
        "Permission denied on tmp.orders:28:27"
    )
    assert "Operation.java" not in message


def test_long_plan_compaction_keeps_signals_from_all_stages() -> None:
    plan = "\n".join([
        "STAGE DEPENDENCIES:",
        "  Stage-1 is a root stage",
        *("  unneeded expression " + str(index) for index in range(200)),
        "  Stage: Stage-5",
        "    Map Reduce",
        "      TableScan",
        "        alias: orders",
        "        Statistics: Num rows: 3284781 Data size: 8684968159",
        "      Reduce Output Operator",
        "        sort order: +-",
        "        Map-reduce partition columns: order_no",
        "      PTF Operator",
        "        partition by: order_no",
        "        order by: update_dt DESC",
        "      File Output Operator",
    ])

    compacted, was_compacted = _compact_plan(plan, max_chars=1000)

    assert was_compacted is True
    assert "PLAN COMPACTED" in compacted
    assert "Stage: Stage-5" in compacted
    assert "Statistics: Num rows: 3284781" in compacted
    assert "partition by: order_no" in compacted
    assert "File Output Operator" in compacted
    assert "unneeded expression" not in compacted


def test_validate_returns_engine_compilation_error_as_fact() -> None:
    service = HiveExecutionService(FakeHiveServer2Adapter(HiveCompilationError(
        "line 1:7 invalid token", compilation_ms=23, default_db="tmp",
    )))

    response = service.validate(HiveSqlRequest(sql="select from"))

    assert response.ok is True
    assert response.valid is False
    assert response.compilation_ms == 23
    assert response.default_db == "tmp"
    assert response.errors[0]["type"] == "compilation_error"


def test_explain_is_always_marked_predicted() -> None:
    service = HiveExecutionService(FakeHiveServer2Adapter())

    response = service.explain(HiveExplainRequest(sql="select 1", defaultDb="dwd"))

    assert response.plan_source == "predicted"
    assert response.default_db == "dwd"
    assert response.plan_text == "STAGE DEPENDENCIES"


def test_function_catalog_service_returns_real_engine_facts() -> None:
    service = HiveExecutionService(FakeHiveServer2Adapter())

    search = service.search_functions(HiveFunctionSearchRequest(
        keyword="count", limit=20, offset=0, defaultDb="dw",
    ))
    detail = service.get_function(HiveFunctionDetailRequest(name="abs", defaultDb="dw"))

    assert search.source == "fake-hive-server2"
    assert search.items == ["count", "count_if"]
    assert search.total == 2
    assert search.default_db == "dw"
    assert detail.name == "abs"
    assert detail.properties["class"].endswith("UDFAbs")
    assert detail.default_db == "dw"


def test_function_detail_rejects_sql_injection_before_connecting() -> None:
    adapter = HiveServer2Adapter("hive://hs2:10000/default?auth=NOSASL", timeout_seconds=10)

    with pytest.raises(McpDomainError) as exc_info:
        adapter.get_function("abs; drop table dw.orders", default_db=None)

    assert exc_info.value.code == "invalid_request"


def test_function_catalog_excludes_operators_that_cannot_be_described() -> None:
    assert _is_describable_function("date_add") is True
    assert _is_describable_function("custom.$sum0") is True
    assert _is_describable_function("!") is False
    assert _is_describable_function("!=") is False
    assert _is_describable_function("%") is False


def test_function_properties_support_real_hive_describe_format() -> None:
    properties = _parse_function_properties([
        "date_add(start_date, num_days) - Returns the date after start_date.",
        "Function class:org.apache.hadoop.hive.ql.udf.generic.GenericUDFDateAdd",
        "Function type:BUILTIN",
    ])

    assert properties == {
        "usage": "date_add(start_date, num_days) - Returns the date after start_date.",
        "class": "org.apache.hadoop.hive.ql.udf.generic.GenericUDFDateAdd",
        "type": "BUILTIN",
    }


def test_task_explain_passes_exact_database_sql_without_model_reconstruction() -> None:
    original_sql = "insert overwrite table dw.target select `字段`, '中文值' from tmp.source;\n"
    adapter = FakeHiveServer2Adapter()
    mcp = FastMCP("test-task-explain")
    register_hive_execution_tools(
        mcp,
        HiveExecutionService(adapter),
        FakeSqlTaskService(original_sql),  # type: ignore[arg-type]
    )

    result = asyncio.run(mcp.call_tool("hive_task_sql_explain", {"taskId": 1, "defaultDb": "dw"}))

    structured = result[1] if isinstance(result, tuple) else result
    assert structured["ok"] is True
    assert structured["planSource"] == "predicted"
    assert adapter.last_sql == original_sql
    assert original_sql not in str(structured)


def test_task_explain_reads_requested_version_draft() -> None:
    adapter = FakeHiveServer2Adapter()
    task_service = FakeSqlTaskService("select 1")
    mcp = FastMCP("test-version-task-explain")
    register_hive_execution_tools(mcp, HiveExecutionService(adapter), task_service)  # type: ignore[arg-type]

    result = asyncio.run(mcp.call_tool("hive_task_sql_explain", {"taskId": 1, "versionNo": 3}))

    structured = result[1] if isinstance(result, tuple) else result
    assert structured["ok"] is True
    assert task_service.requested_version_no == 3


def test_adapter_distinguishes_connection_failure_from_compilation_failure(monkeypatch) -> None:
    from pyhive import hive
    from pyhive.exc import OperationalError
    import sql_agent_mcp_server.domains.hive_execution.adapter as module

    adapter = HiveServer2Adapter("hive://hs2:10000/default?auth=NOSASL", timeout_seconds=10)
    monkeypatch.setattr(module, "_build_transport", lambda *args: object())
    monkeypatch.setattr(hive, "connect", lambda **kwargs: (_ for _ in ()).throw(OperationalError("offline")))

    with pytest.raises(McpDomainError) as connection_error:
        adapter.explain("select 1", default_db=None, extended=False)
    assert connection_error.value.code == "dependency_unavailable"

    class FakeCursor:
        def execute(self, sql):
            raise OperationalError("line 1:7 invalid token")

        def close(self):
            pass

    class FakeConnection:
        def cursor(self):
            return FakeCursor()

        def close(self):
            pass

    monkeypatch.setattr(hive, "connect", lambda **kwargs: FakeConnection())

    with pytest.raises(HiveCompilationError) as compilation_error:
        adapter.explain("select from", default_db=None, extended=False)
    assert "invalid token" in str(compilation_error.value)
