"""MCP Server 配置读取。"""

from __future__ import annotations

import os
from functools import lru_cache
from pathlib import Path

from dotenv import load_dotenv
from pydantic import BaseModel, Field

PROJECT_ROOT_MARKERS = ("pyproject.toml",)
PROJECT_ROOT_ENV_VARS = ("SQL_AGENT_MCP_PROJECT_ROOT", "APP_HOME")


class Settings(BaseModel):
    """服务运行配置。"""

    hive_metastore_uri: str | None = Field(default=None)
    hive_metastore_db_uri: str | None = Field(default=None)
    data_map_db_uri: str | None = Field(default=None)
    sql_agent_db_uri: str | None = Field(default=None)
    hive_server2_uri: str | None = Field(default=None)
    webhdfs_urls: tuple[str, ...] = Field(default_factory=tuple)
    yarn_resource_manager_urls: tuple[str, ...] = Field(default_factory=tuple)
    mapreduce_job_history_urls: tuple[str, ...] = Field(default_factory=tuple)
    hadoop_http_user: str | None = Field(default=None)
    mcp_tool_timeout_seconds: int = Field(default=10, ge=1, le=120)
    mcp_log_level: str = Field(default="INFO")
    mcp_log_file: str = Field(default="logs/sql_agent_mcp_server.log")
    mcp_log_backup_count: int = Field(default=7, ge=0, le=365)
    mcp_http_host: str = Field(default="127.0.0.1")
    mcp_http_port: int = Field(default=8820, ge=1, le=65535)
    mcp_http_path: str = Field(default="/mcp", min_length=1)


def load_project_env() -> None:
    """加载项目根目录下的 `.env` 文件。"""

    env_path = get_project_root() / ".env"
    if env_path.exists():
        load_dotenv(env_path, override=False)


@lru_cache
def get_settings() -> Settings:
    """从环境变量读取配置。"""

    load_project_env()
    return Settings(
        hive_metastore_uri=os.getenv("HIVE_METASTORE_URI"),
        hive_metastore_db_uri=os.getenv("HIVE_METASTORE_DB_URI"),
        data_map_db_uri=os.getenv("DATA_MAP_DB_URI"),
        sql_agent_db_uri=os.getenv("SQL_AGENT_DB_URI"),
        hive_server2_uri=os.getenv("HIVE_SERVER2_URI"),
        webhdfs_urls=_split_urls(os.getenv("WEBHDFS_URLS")),
        yarn_resource_manager_urls=_split_urls(os.getenv("YARN_RESOURCE_MANAGER_URLS")),
        mapreduce_job_history_urls=_split_urls(os.getenv("MAPREDUCE_JOB_HISTORY_URLS")),
        hadoop_http_user=os.getenv("HADOOP_HTTP_USER"),
        mcp_tool_timeout_seconds=int(os.getenv("MCP_TOOL_TIMEOUT_SECONDS", "10")),
        mcp_log_level=os.getenv("MCP_LOG_LEVEL", "INFO"),
        mcp_log_file=os.getenv("MCP_LOG_FILE", "logs/sql_agent_mcp_server.log"),
        mcp_log_backup_count=int(os.getenv("MCP_LOG_BACKUP_COUNT", "7")),
        mcp_http_host=os.getenv("MCP_HTTP_HOST", "127.0.0.1"),
        mcp_http_port=int(os.getenv("MCP_HTTP_PORT", "8820")),
        mcp_http_path=os.getenv("MCP_HTTP_PATH", "/mcp"),
    )


def _split_urls(value: str | None) -> tuple[str, ...]:
    return tuple(item.strip().rstrip("/") for item in (value or "").split(",") if item.strip())


def get_project_root() -> Path:
    """返回项目根目录路径。"""

    configured_root = _get_configured_project_root()
    if configured_root is not None:
        return configured_root

    try:
        return _find_project_root(Path(__file__).resolve())
    except RuntimeError:
        return Path.cwd().resolve()


def _find_project_root(start: Path) -> Path:
    """向上查找项目根目录。"""

    for directory in _iter_search_directories(start):
        if _is_project_root(directory):
            return directory
    raise RuntimeError("未找到项目根目录，无法定位项目配置。")


def _is_project_root(directory: Path) -> bool:
    """判断目录是否满足项目根目录标识。"""

    return all((directory / marker).exists() for marker in PROJECT_ROOT_MARKERS)


def _get_configured_project_root() -> Path | None:
    """优先使用显式配置的项目根目录。"""

    for env_var in PROJECT_ROOT_ENV_VARS:
        configured_root = os.getenv(env_var)
        if not configured_root:
            continue

        candidate = Path(configured_root).expanduser().resolve()
        if candidate.exists():
            return candidate

    return None


def _iter_search_directories(start: Path) -> tuple[Path, ...]:
    """返回用于查找项目根目录的候选路径。"""

    seen: set[Path] = set()
    candidates: list[Path] = []

    for candidate in (_get_configured_project_root(), start.resolve(), Path.cwd().resolve()):
        if candidate is None:
            continue

        for directory in (candidate, *candidate.parents):
            if directory in seen:
                continue
            seen.add(directory)
            candidates.append(directory)

    return tuple(candidates)
