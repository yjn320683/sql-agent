import asyncio
import json
import sys
import types
import unittest

fake_claude_sdk = sys.modules.setdefault("claude_agent_sdk", types.ModuleType("claude_agent_sdk"))


class _FakePermissionResultAllow:
    pass


class _FakePermissionResultDeny:
    def __init__(self, message: str = "", interrupt: bool = False) -> None:
        self.message = message
        self.interrupt = interrupt


fake_claude_sdk.PermissionResultAllow = _FakePermissionResultAllow
fake_claude_sdk.PermissionResultDeny = _FakePermissionResultDeny

from app.permissions import manager
from app.user_questions.manager import resolve_user_question


class PermissionManagerTests(unittest.IsolatedAsyncioTestCase):
    async def test_only_sql_agent_skill_is_auto_allowed(self) -> None:
        result = await manager.can_use_tool(
            "Skill",
            {"skill": "sql-agent-rule"},
            asyncio.Queue(),
            asyncio.get_running_loop(),
        )

        self.assertIsInstance(result, manager.PermissionResultAllow)

    async def test_unknown_skill_is_denied_without_frontend_confirmation(self) -> None:
        queue: asyncio.Queue = asyncio.Queue()

        result = await manager.can_use_tool(
            "Skill",
            {"skill": "other-skill", "args": "读取 tool result 文件"},
            queue,
            asyncio.get_running_loop(),
        )

        self.assertIsInstance(result, manager.PermissionResultDeny)
        self.assertIn("只能加载 sql-agent-rule", result.message)
        self.assertTrue(queue.empty())

    async def test_read_tools_still_auto_allowed(self) -> None:
        result = await manager.can_use_tool("Read", {"file_path": "app/settings.py"}, asyncio.Queue(), asyncio.get_running_loop())

        self.assertIsInstance(result, manager.PermissionResultAllow)

    async def test_sql_mcp_tools_auto_allowed(self) -> None:
        for tool_name, tool_input in [
            ("mcp__sql_agent__hive_list_databases", {}),
            ("mcp__sql_agent__hive_search_tables", {"keyword": "order"}),
            ("mcp__sql_agent__hive_get_table", {"database": "dw", "table": "orders"}),
            ("mcp__sql_agent__hive_get_columns", {"database": "dw", "table": "orders"}),
            ("mcp__sql_agent__hive_get_partitions", {"database": "dw", "table": "orders"}),
            ("mcp__sql_agent__hive_get_table_ddl", {"database": "dw", "table": "orders"}),
            ("mcp__sql_agent__hive_find_column_usage", {"column": "order_id"}),
            ("mcp__sql_agent__hive_table_statistics_get", {"db": "dw", "table": "orders"}),
            ("mcp__sql_agent__hive_task_sql_validate", {"taskId": 42}),
            ("mcp__sql_agent__hive_task_sql_explain", {"taskId": 42}),
            ("mcp__sql_agent__hive_sql_validate", {"sql": "select 1"}),
            ("mcp__sql_agent__hive_sql_explain", {"sql": "select 1"}),
            ("mcp__sql_agent__hive_function_search", {"keyword": "date"}),
            ("mcp__sql_agent__hive_function_get", {"name": "date_add"}),
            ("mcp__sql_agent__hive_storage_layout_get", {"db": "dw", "table": "orders"}),
            ("mcp__sql_agent__yarn_application_diagnostics_get", {"applicationIds": ["application_1_1"]}),
            ("mcp__sql_agent__mapreduce_job_search", {"jobId": "job_1_1"}),
            ("mcp__sql_agent__mapreduce_job_diagnostics_get", {"jobIds": ["job_1_1"]}),
            ("mcp__sql_agent__mapreduce_aggregated_logs_get", {"jobIds": ["job_1_1"]}),
            ("mcp__sql_agent__mapreduce_job_compare", {"baselineJobIds": ["job_1_1"], "candidateJobIds": ["job_1_2"]}),
            ("mcp__sql_agent__platform_dependency_health_get", {}),
            ("mcp__sql_agent__data_map_get_table_primary_keys", {"database": "dw", "table": "orders"}),
        ]:
            result = await manager.can_use_tool(tool_name, tool_input, asyncio.Queue(), asyncio.get_running_loop())

            self.assertIsInstance(result, manager.PermissionResultAllow)

    async def test_bash_is_denied_without_frontend_confirmation(self) -> None:
        queue: asyncio.Queue = asyncio.Queue()

        result = await manager.can_use_tool("Bash", {"command": "cat app/settings.py"}, queue, asyncio.get_running_loop())

        self.assertIsInstance(result, manager.PermissionResultDeny)
        self.assertIn("不允许使用 Bash", result.message)
        self.assertTrue(queue.empty())

    async def test_ask_user_question_uses_business_question_event(self) -> None:
        queue: asyncio.Queue = asyncio.Queue()
        task = asyncio.create_task(
            manager.can_use_tool(
                "AskUserQuestion",
                {
                    "questions": [
                        {
                            "question": "销售额口径是什么？",
                            "header": "销售额口径",
                            "options": [{"label": "累计销售总额 (sum)"}],
                            "multiSelect": False,
                        }
                    ]
                },
                queue,
                asyncio.get_running_loop(),
            )
        )

        event = await queue.get()
        payload = json.loads(event["data"])
        self.assertEqual(event["event"], "user_question_request")
        self.assertEqual(payload["questions"][0]["header"], "销售额口径")

        accepted = resolve_user_question(
            payload["requestId"],
            {
                "answers": [
                    {
                        "question": "销售额口径是什么？",
                        "selectedLabels": ["累计销售总额 (sum)"],
                        "selectedOptions": [{"label": "累计销售总额 (sum)"}],
                    }
                ]
            },
        )
        self.assertTrue(accepted)
        result = await task

        self.assertIsInstance(result, manager.PermissionResultDeny)
        self.assertIn("累计销售总额 (sum)", result.message)
        self.assertNotIn("权限", result.message)


if __name__ == "__main__":
    unittest.main()
