"""日志配置。"""

from __future__ import annotations

import logging
import time
from collections.abc import Callable
from logging.handlers import TimedRotatingFileHandler
from pathlib import Path
from typing import Any, TypeVar

from sql_agent_mcp_server.common.errors import McpDomainError, to_error_response
from sql_agent_mcp_server.common.context import require_principal_ob_id
from sql_agent_mcp_server.settings import get_project_root, get_settings

T = TypeVar("T")

LOG_FORMAT = "%(asctime)s | %(levelname)s | %(name)s | %(message)s"
CONSOLE_HANDLER_NAME = "datadev_mcp_console"
FILE_HANDLER_NAME = "datadev_mcp_file"


def configure_logging() -> None:
    """初始化基础日志，同时输出到控制台和文件。"""

    settings = get_settings()
    log_level = getattr(logging, settings.mcp_log_level.upper(), logging.INFO)
    fmt = logging.Formatter(LOG_FORMAT)
    root = logging.getLogger()
    root.setLevel(log_level)
    log_file_path = _resolve_log_file_path()

    _ensure_console_handler(root_logger=root, level=log_level, formatter=fmt)
    _ensure_file_handler(
        root_logger=root,
        level=log_level,
        formatter=fmt,
        log_file_path=log_file_path,
        backup_count=settings.mcp_log_backup_count,
    )
    suppress_sensitive_dependency_logs()


def suppress_sensitive_dependency_logs() -> None:
    """禁止第三方客户端在 INFO 日志中回显完整 SQL。"""

    logging.getLogger("pyhive").setLevel(logging.WARNING)


def get_logger(name: str) -> logging.Logger:
    """返回项目内统一使用的 Logger。"""

    return logging.getLogger(name)


def run_tool_safely(tool_name: str, fn: Callable[[], T], params: dict[str, Any] | None = None) -> T | dict[str, Any]:
    """执行 tool 并记录耗时和请求参数。"""

    logger = get_logger("sql_agent_mcp_server.tools")
    started_at = time.perf_counter()
    try:
        require_principal_ob_id()
        logger.info("开始执行工具 tool=%s 参数=%s", tool_name, params or {})
        result = fn()
        elapsed_ms = int((time.perf_counter() - started_at) * 1000)
        logger.info("工具执行成功 tool=%s 耗时毫秒=%s", tool_name, elapsed_ms)
        return result
    except Exception as exc:  # noqa: BLE001 - MCP tool 边界需要统一兜底
        elapsed_ms = int((time.perf_counter() - started_at) * 1000)
        if isinstance(exc, McpDomainError):
            logger.warning(
                "工具执行失败 tool=%s 耗时毫秒=%s 异常类型=%s 错误码=%s 错误信息=%s 错误详情=%s",
                tool_name,
                elapsed_ms,
                type(exc).__name__,
                exc.code,
                exc.message,
                exc.details,
            )
        else:
            logger.warning(
                "工具执行失败 tool=%s 耗时毫秒=%s 异常类型=%s 错误信息=%s",
                tool_name,
                elapsed_ms,
                type(exc).__name__,
                str(exc),
            )
        return to_error_response(exc)


def _resolve_log_file_path() -> Path:
    """解析文件日志路径，并确保日志目录存在。"""

    settings = get_settings()
    project_root = get_project_root()
    configured_log_file = Path(settings.mcp_log_file)
    if not configured_log_file.is_absolute():
        configured_log_file = project_root / configured_log_file
    configured_log_file.parent.mkdir(parents=True, exist_ok=True)
    return configured_log_file


def _ensure_console_handler(
    *,
    root_logger: logging.Logger,
    level: int,
    formatter: logging.Formatter,
) -> None:
    """确保根日志器上存在且只存在一个控制台 handler。"""

    console_handler = _find_named_handler(root_logger=root_logger, handler_name=CONSOLE_HANDLER_NAME)
    if console_handler is None:
        console_handler = logging.StreamHandler()
        console_handler.set_name(CONSOLE_HANDLER_NAME)
        root_logger.addHandler(console_handler)
    _configure_handler(handler=console_handler, level=level, formatter=formatter)


def _ensure_file_handler(
    *,
    root_logger: logging.Logger,
    level: int,
    formatter: logging.Formatter,
    log_file_path: Path,
    backup_count: int,
) -> None:
    """确保根日志器上存在且只存在一个按天轮转的文件 handler。"""

    file_handler = _find_named_handler(root_logger=root_logger, handler_name=FILE_HANDLER_NAME)
    if file_handler is None:
        file_handler = TimedRotatingFileHandler(
            filename=str(log_file_path),
            when="midnight",
            backupCount=max(backup_count, 0),
            encoding="utf-8",
        )
        file_handler.set_name(FILE_HANDLER_NAME)
        root_logger.addHandler(file_handler)
    _configure_handler(handler=file_handler, level=level, formatter=formatter)


def _configure_handler(
    *,
    handler: logging.Handler,
    level: int,
    formatter: logging.Formatter,
) -> None:
    """统一设置 handler 的日志级别与格式。"""

    handler.setLevel(level)
    handler.setFormatter(formatter)


def _find_named_handler(
    *,
    root_logger: logging.Logger,
    handler_name: str,
) -> logging.Handler | None:
    """按稳定名称查找已注册的 handler。"""

    for handler in root_logger.handlers:
        if handler.get_name() == handler_name:
            return handler
    return None
