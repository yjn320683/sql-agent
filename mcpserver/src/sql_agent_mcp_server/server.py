"""SQL Agent MCP Server 入口。"""

from __future__ import annotations

import argparse
from collections.abc import Sequence

from mcp.server.fastmcp import FastMCP

from sql_agent_mcp_server.common.logging import configure_logging, get_logger
from sql_agent_mcp_server.domains.data_map.service import DataMapService
from sql_agent_mcp_server.domains.data_map.tools import register_data_map_tools
from sql_agent_mcp_server.domains.hive_metadata.service import HiveMetadataService
from sql_agent_mcp_server.domains.hive_metadata.tools import register_hive_metadata_tools
from sql_agent_mcp_server.domains.hive_execution.service import HiveExecutionService
from sql_agent_mcp_server.domains.hive_execution.tools import register_hive_execution_tools
from sql_agent_mcp_server.domains.hadoop_runtime.service import HadoopRuntimeService
from sql_agent_mcp_server.domains.hadoop_runtime.tools import register_hadoop_runtime_tools
from sql_agent_mcp_server.domains.sql_task.service import SqlTaskService
from sql_agent_mcp_server.domains.sql_task.tools import register_sql_task_tools
from sql_agent_mcp_server.settings import Settings, get_settings

LOGGER = get_logger(__name__)


def create_mcp(settings: Settings | None = None) -> FastMCP:
    """创建 MCP app 并注册所有 SQL Agent 事实工具。"""

    resolved_settings = settings or get_settings()
    configure_logging()
    LOGGER.info(
        "初始化 MCP 服务 host=%s port=%s path=%s 超时秒=%s",
        resolved_settings.mcp_http_host,
        resolved_settings.mcp_http_port,
        resolved_settings.mcp_http_path,
        resolved_settings.mcp_tool_timeout_seconds,
    )

    mcp = FastMCP(
        "sql-agent-mcp-server",
        host=resolved_settings.mcp_http_host,
        port=resolved_settings.mcp_http_port,
        streamable_http_path=resolved_settings.mcp_http_path,
        log_level=resolved_settings.mcp_log_level,
    )
    hive_service = HiveMetadataService.from_settings(resolved_settings)
    hive_execution_service = HiveExecutionService.from_settings(resolved_settings)
    hadoop_runtime_service = HadoopRuntimeService.from_settings(
        resolved_settings,
        hive_service,
        hive_execution_service,
    )
    data_map_service = DataMapService.from_settings(resolved_settings)
    sql_task_service = SqlTaskService.from_settings(resolved_settings)
    register_hive_metadata_tools(mcp, hive_service)
    register_hive_execution_tools(mcp, hive_execution_service, sql_task_service)
    register_hadoop_runtime_tools(mcp, hadoop_runtime_service)
    register_data_map_tools(mcp, data_map_service)
    register_sql_task_tools(mcp, sql_task_service)
    return mcp


def main(argv: Sequence[str] | None = None) -> None:
    """启动 MCP Server，支持 stdio 和 streamable-http。"""

    parser = argparse.ArgumentParser()
    parser.add_argument("--transport", choices=["stdio", "streamable-http"], default="streamable-http")
    args = parser.parse_args(argv)
    LOGGER.info("MCP 服务启动，传输模式=%s", args.transport)
    create_mcp().run(transport=args.transport)


if __name__ == "__main__":
    main()
