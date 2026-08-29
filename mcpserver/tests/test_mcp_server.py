from __future__ import annotations

import asyncio

from sql_agent_mcp_server.server import create_mcp
from sql_agent_mcp_server.settings import Settings


def _structured_result(result):
    if isinstance(result, tuple):
        return result[1]
    return result


def test_server_registers_hive_tools() -> None:
    mcp = create_mcp(Settings())

    tools = asyncio.run(mcp.list_tools())
    tool_names = {tool.name for tool in tools}

    assert {
        "hive_list_databases",
        "hive_search_tables",
        "hive_get_table",
        "hive_get_columns",
        "hive_get_partitions",
        "hive_get_table_ddl",
        "hive_find_column_usage",
        "data_map_get_table_primary_keys",
        "platform_dependency_health_get",
        "hive_task_sql_validate",
        "hive_task_sql_explain",
        "hive_sql_validate",
        "hive_sql_explain",
        "hive_function_search",
        "hive_function_get",
        "hive_table_statistics_get",
        "hive_storage_layout_get",
        "hive_table_freshness_get",
        "yarn_application_diagnostics_get",
        "mapreduce_job_search",
        "mapreduce_job_diagnostics_get",
        "mapreduce_aggregated_logs_get",
        "mapreduce_job_compare",
        "sql_task_get",
        "sql_task_execution_get",
        "sql_task_execution_list",
    } <= tool_names


def test_sql_task_get_exposes_optional_version_number() -> None:
    tools = asyncio.run(create_mcp(Settings()).list_tools())
    sql_task_get = next(tool for tool in tools if tool.name == "sql_task_get")

    assert sql_task_get.inputSchema["required"] == ["taskId"]
    assert sql_task_get.inputSchema["properties"]["versionNo"]["default"] is None


def test_server_uses_streamable_http_settings() -> None:
    mcp = create_mcp(
        Settings(
            mcp_http_host="0.0.0.0",
            mcp_http_port=8100,
            mcp_http_path="/sql-agent-mcp",
            mcp_log_level="DEBUG",
        )
    )

    assert mcp.settings.host == "0.0.0.0"
    assert mcp.settings.port == 8100
    assert mcp.settings.streamable_http_path == "/sql-agent-mcp"
    assert mcp.settings.log_level == "DEBUG"


def test_mcp_search_tables_contract() -> None:
    mcp = create_mcp(Settings(hive_metastore_uri=None))

    result = _structured_result(asyncio.run(mcp.call_tool("hive_search_tables", {"pattern": "orders"})))

    assert result["ok"] is False
    assert result["code"] == "dependency_unavailable"
    assert result["details"]["required"] == "HIVE_METASTORE_URI"


def test_mcp_invalid_tool_request_returns_structured_error() -> None:
    mcp = create_mcp(Settings(hive_metastore_uri="thrift://localhost:9083"))

    result = _structured_result(asyncio.run(mcp.call_tool("hive_get_columns", {"db": "", "table": "orders"})))

    assert result["ok"] is False
    assert result["code"] == "invalid_request"


def test_mcp_metastore_connection_error_is_structured() -> None:
    mcp = create_mcp(Settings(hive_metastore_uri="thrift://127.0.0.1:1"))

    result = _structured_result(asyncio.run(mcp.call_tool("hive_list_databases", {})))

    assert result["ok"] is False
    assert result["code"] == "dependency_unavailable"
    assert result["details"]["endpoint"] == "127.0.0.1:1"


def test_platform_health_reports_each_missing_dependency_without_exposing_urls() -> None:
    mcp = create_mcp(Settings())

    result = _structured_result(asyncio.run(mcp.call_tool("platform_dependency_health_get", {})))

    assert result["ok"] is True
    assert result["complete"] is False
    assert {item["name"] for item in result["dependencies"]} == {
        "hiveServer2", "hiveMetastore", "webHdfs", "yarnResourceManager", "mapReduceJobHistory"
    }
    assert "http://" not in str(result)


def test_mapreduce_diagnostics_requires_an_identifier() -> None:
    mcp = create_mcp(Settings())

    result = _structured_result(asyncio.run(mcp.call_tool("mapreduce_job_diagnostics_get", {})))

    assert result["ok"] is False
    assert result["code"] == "invalid_request"
    assert "input" not in str(result["details"])
