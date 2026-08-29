import sys
import types
import unittest
from pathlib import Path
from unittest.mock import patch

from app.settings import Settings


fake_claude_sdk = sys.modules.setdefault("claude_agent_sdk", types.ModuleType("claude_agent_sdk"))


class _FakeClaudeAgentOptions:
    def __init__(self, **kwargs) -> None:
        self.__dict__.update(kwargs)


fake_claude_sdk.ClaudeAgentOptions = _FakeClaudeAgentOptions

from app.claude import options

SKILL_PATH = Path(__file__).resolve().parents[1] / ".claude" / "skills" / "sql-agent-rule" / "SKILL.md"


def _fake_settings() -> Settings:
    return Settings(
        agent_cwd="/tmp",
        service_token="test-token",
        langsmith_tracing=False,
        langsmith_project="",
        mcp_tool_timeout_seconds=10,
        sql_agent_db_uri="",
        hive_server2_uri="",
        task_execution_log_dir="/tmp/task-executions",
    )


class ClaudeOptionsTests(unittest.TestCase):
    @patch("app.claude.options.ensure_project_skill")
    @patch("app.claude.options.get_settings", side_effect=_fake_settings)
    def test_build_options_sets_max_turns_to_30(self, _settings_mock, _skill_mock) -> None:
        result = options.build_options("138284", "sql_optimize", resume="session-1")

        self.assertEqual(result.max_turns, 30)
        self.assertEqual(result.resume, "session-1")
        self.assertEqual(result.cwd, "/tmp")
        self.assertIn("agent", result.mcp_servers["sql_agent"]["env"]["PYTHONPATH"])
        self.assertEqual(result.mcp_servers["sql_agent"]["env"]["SQL_AGENT_OB_ID"], "138284")
        self.assertEqual(result.mcp_servers["sql_agent"]["type"], "stdio")
        self.assertEqual(result.mcp_servers["sql_agent"]["args"], ["-m", "sql_agent_mcp_server.server", "--transport", "stdio"])
        self.assertEqual(result.tools, ["Skill", "AskUserQuestion", "Read"])
        self.assertEqual(result.skills, ["sql-agent-rule"])
        prompt = result.system_prompt["append"] if isinstance(result.system_prompt, dict) else result.system_prompt
        self.assertIn("数开平台 SQL Agent", prompt)
        self.assertIn("当前 command：sql_optimize", prompt)
        self.assertIn("Skill(sql-agent-rule)", prompt)
        self.assertIn("Hive、HDFS、YARN、MapReduce JobHistory 或 Data Map MCP 工具", prompt)
        self.assertIn("不要执行用户 SQL", prompt)
        legacy_name = "\u6570\u636e\u96c6\u5e02\u573a" + "助手"
        self.assertNotIn(legacy_name, prompt)

    @patch("app.claude.options.ensure_project_skill")
    @patch("app.claude.options.get_settings", side_effect=_fake_settings)
    def test_build_options_ignores_external_max_turns_override(self, _settings_mock, _skill_mock) -> None:
        result = options.build_options("138284", "sql_generate", max_turns=100)

        self.assertEqual(result.max_turns, 30)

    def test_sql_skill_owns_sql_command_rules(self) -> None:
        skill = SKILL_PATH.read_text(encoding="utf-8")

        self.assertIn("sql_generate", skill)
        self.assertIn("sql_optimize", skill)
        self.assertIn("sql_fix", skill)
        self.assertIn("sql_explain", skill)
        self.assertIn("sql_static_check", skill)
        self.assertIn("Hive/Data Map MCP 工具", skill)
        self.assertIn("不要调用 Bash", skill)


if __name__ == "__main__":
    unittest.main()
