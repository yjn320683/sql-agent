"""进程级配置：加载 .env 并暴露 Agent 运行配置。"""

import os
from dataclasses import dataclass
from functools import lru_cache

from dotenv import load_dotenv


@dataclass(frozen=True)
class Settings:
    """Agent 运行期配置。"""

    agent_cwd: str
    service_token: str
    langsmith_tracing: bool
    langsmith_project: str
    mcp_tool_timeout_seconds: int
    sql_agent_db_uri: str
    hive_server2_uri: str
    task_execution_log_dir: str
    backend_base_url: str = ""


@lru_cache
def get_settings() -> Settings:
    """加载 .env 并构建配置；结果缓存。"""
    load_dotenv()
    configured_agent_cwd = os.environ.get("AGENT_CWD", "").strip()
    agent_cwd = configured_agent_cwd or os.path.abspath(os.getcwd())
    mcp_log_file = os.environ.get("MCP_LOG_FILE", "logs/sql-agent-mcp-server.log")
    container_log_root = os.path.dirname(os.path.abspath(mcp_log_file))
    host_log_root = os.environ.get("HOST_LOG_DIR", "").strip()
    explicit_execution_log_dir = os.environ.get("TASK_EXECUTION_LOG_DIR", "").strip()
    if explicit_execution_log_dir:
        task_execution_log_dir = os.path.abspath(explicit_execution_log_dir)
    elif configured_agent_cwd == "/app":
        # Docker 中 HOST_LOG_DIR 是宿主机挂载源，容器内固定写 /app/logs。
        task_execution_log_dir = os.path.join(container_log_root, "task-executions")
    else:
        # 本地开发不能回退到只读的容器路径 /app/logs。
        local_log_root = host_log_root or container_log_root
        task_execution_log_dir = os.path.join(os.path.abspath(local_log_root), "task-executions")
    return Settings(
        agent_cwd=agent_cwd,
        service_token=os.environ.get("AGENT_SERVICE_TOKEN", "").strip(),
        langsmith_tracing=os.environ.get("LANGSMITH_TRACING", "false").lower() == "true",
        langsmith_project=os.environ.get("LANGSMITH_PROJECT", ""),
        mcp_tool_timeout_seconds=int(os.environ.get("MCP_TOOL_TIMEOUT_SECONDS", "10")),
        sql_agent_db_uri=os.environ.get("SQL_AGENT_DB_URI", "").strip(),
        hive_server2_uri=os.environ.get("HIVE_SERVER2_URI", "").strip(),
        task_execution_log_dir=task_execution_log_dir,
        backend_base_url=os.environ.get("SQL_AGENT_BACKEND_URL", "http://127.0.0.1:8382").strip().rstrip("/"),
    )
