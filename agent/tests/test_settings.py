import os
import unittest
from unittest.mock import patch

from app.settings import get_settings


class SettingsTests(unittest.TestCase):
    def tearDown(self) -> None:
        get_settings.cache_clear()

    @patch("app.settings.load_dotenv")
    def test_agent_cwd_uses_env(self, _mock_load_dotenv) -> None:
        with patch.dict(os.environ, {"AGENT_CWD": "/tmp/agent"}, clear=True):
            get_settings.cache_clear()
            settings = get_settings()

        self.assertEqual(settings.agent_cwd, "/tmp/agent")
        self.assertEqual(settings.mcp_tool_timeout_seconds, 10)

    @patch("app.settings.load_dotenv")
    def test_mcp_tool_timeout_can_be_configured_by_env(self, _mock_load_dotenv) -> None:
        with patch.dict(
            os.environ,
            {"AGENT_CWD": "/tmp/agent", "MCP_TOOL_TIMEOUT_SECONDS": "25"},
            clear=True,
        ):
            get_settings.cache_clear()
            settings = get_settings()

        self.assertEqual(settings.mcp_tool_timeout_seconds, 25)

    @patch("app.settings.load_dotenv")
    def test_local_execution_logs_use_host_log_dir(self, _mock_load_dotenv) -> None:
        with patch.dict(
            os.environ,
            {"HOST_LOG_DIR": "/tmp/sql-agent/logs", "MCP_LOG_FILE": "/app/logs/mcp.log"},
            clear=True,
        ):
            get_settings.cache_clear()
            settings = get_settings()

        self.assertEqual(settings.task_execution_log_dir, "/tmp/sql-agent/logs/task-executions")

    @patch("app.settings.load_dotenv")
    def test_container_execution_logs_use_mounted_app_log_dir(self, _mock_load_dotenv) -> None:
        with patch.dict(
            os.environ,
            {
                "AGENT_CWD": "/app",
                "HOST_LOG_DIR": "/host/sql-agent/logs",
                "MCP_LOG_FILE": "/app/logs/mcp.log",
            },
            clear=True,
        ):
            get_settings.cache_clear()
            settings = get_settings()

        self.assertEqual(settings.task_execution_log_dir, "/app/logs/task-executions")

    @patch("app.settings.load_dotenv")
    def test_execution_log_dir_can_be_overridden(self, _mock_load_dotenv) -> None:
        with patch.dict(
            os.environ,
            {"TASK_EXECUTION_LOG_DIR": "./custom-task-logs"},
            clear=True,
        ):
            get_settings.cache_clear()
            settings = get_settings()

        self.assertEqual(settings.task_execution_log_dir, os.path.abspath("./custom-task-logs"))
