"""日志初始化与关键日志行为测试。"""

from __future__ import annotations

import logging
from logging.handlers import TimedRotatingFileHandler
from pathlib import Path

from sql_agent_mcp_server import settings
from sql_agent_mcp_server.common.errors import McpDomainError, McpErrorCode
from sql_agent_mcp_server.common import logging as logging_utils


def test_configure_logging_registers_timed_rotating_file_handler(monkeypatch, tmp_path: Path) -> None:
    _reset_logging_state()
    _clear_settings_cache()
    monkeypatch.setenv("SQL_AGENT_MCP_PROJECT_ROOT", str(tmp_path))
    monkeypatch.setenv("MCP_LOG_FILE", "logs/mcp.log")
    monkeypatch.setenv("MCP_LOG_BACKUP_COUNT", "9")

    logging_utils.configure_logging()

    root_logger = logging.getLogger()
    file_handlers = [handler for handler in root_logger.handlers if isinstance(handler, TimedRotatingFileHandler)]
    assert len(file_handlers) == 1
    assert Path(file_handlers[0].baseFilename) == (tmp_path / "logs" / "mcp.log")
    assert file_handlers[0].backupCount == 9
    assert file_handlers[0].encoding == "utf-8"


def test_configure_logging_does_not_duplicate_handlers(monkeypatch, tmp_path: Path) -> None:
    _reset_logging_state()
    _clear_settings_cache()
    monkeypatch.setenv("SQL_AGENT_MCP_PROJECT_ROOT", str(tmp_path))

    logging_utils.configure_logging()
    logging_utils.configure_logging()

    root_logger = logging.getLogger()
    console_handlers = [
        handler for handler in root_logger.handlers if handler.get_name() == logging_utils.CONSOLE_HANDLER_NAME
    ]
    file_handlers = [
        handler for handler in root_logger.handlers if handler.get_name() == logging_utils.FILE_HANDLER_NAME
    ]
    assert len(console_handlers) == 1
    assert len(file_handlers) == 1


def test_configure_logging_suppresses_pyhive_sql_echo(monkeypatch, tmp_path: Path) -> None:
    _reset_logging_state()
    _clear_settings_cache()
    monkeypatch.setenv("SQL_AGENT_MCP_PROJECT_ROOT", str(tmp_path))
    logging.getLogger("pyhive").setLevel(logging.NOTSET)

    logging_utils.configure_logging()

    assert logging.getLogger("pyhive").level == logging.WARNING


def test_resolve_log_file_path_uses_project_root(monkeypatch, tmp_path: Path) -> None:
    _clear_settings_cache()
    monkeypatch.setenv("SQL_AGENT_MCP_PROJECT_ROOT", str(tmp_path))
    monkeypatch.delenv("MCP_LOG_FILE", raising=False)

    log_file_path = logging_utils._resolve_log_file_path()

    assert log_file_path == tmp_path / "logs" / "sql_agent_mcp_server.log"


def test_run_tool_safely_records_chinese_key_logs(caplog) -> None:
    logger = logging.getLogger("sql_agent_mcp_server.tools")
    logger.setLevel(logging.INFO)

    with caplog.at_level(logging.INFO):
        result = logging_utils.run_tool_safely("demo_tool", lambda: {"ok": True}, {"db": "test"})

    assert result == {"ok": True}
    assert "开始执行工具 tool=demo_tool 参数={'db': 'test'}" in caplog.text
    assert "工具执行成功 tool=demo_tool" in caplog.text


def test_run_tool_safely_records_domain_error_details(caplog) -> None:
    logger = logging.getLogger("sql_agent_mcp_server.tools")
    logger.setLevel(logging.INFO)

    def raise_domain_error() -> None:
        raise McpDomainError(
            McpErrorCode.DEPENDENCY_UNAVAILABLE,
            "Hive Metastore DB URI is not configured.",
            details={"required": "HIVE_METASTORE_DB_URI"},
        )

    with caplog.at_level(logging.INFO):
        result = logging_utils.run_tool_safely("hive_find_column_usage", raise_domain_error, {"column": "status"})

    assert result["code"] == McpErrorCode.DEPENDENCY_UNAVAILABLE
    assert "工具执行失败 tool=hive_find_column_usage" in caplog.text
    assert "异常类型=McpDomainError" in caplog.text
    assert "错误码=dependency_unavailable" in caplog.text
    assert "错误信息=Hive Metastore DB URI is not configured." in caplog.text
    assert "错误详情={'required': 'HIVE_METASTORE_DB_URI'}" in caplog.text


def test_run_tool_safely_records_unknown_error_message(caplog) -> None:
    logger = logging.getLogger("sql_agent_mcp_server.tools")
    logger.setLevel(logging.INFO)

    with caplog.at_level(logging.INFO):
        result = logging_utils.run_tool_safely("demo_tool", lambda: (_ for _ in ()).throw(RuntimeError("boom")))

    assert result["code"] == "internal_error"
    assert "工具执行失败 tool=demo_tool" in caplog.text
    assert "异常类型=RuntimeError" in caplog.text
    assert "错误信息=boom" in caplog.text


def _reset_logging_state() -> None:
    root_logger = logging.getLogger()
    for handler in list(root_logger.handlers):
        root_logger.removeHandler(handler)
        handler.close()


def _clear_settings_cache() -> None:
    settings.get_settings.cache_clear()
