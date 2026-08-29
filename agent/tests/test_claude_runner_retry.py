import sys
import types
import unittest
from types import SimpleNamespace
from unittest.mock import patch

fake_claude_sdk = sys.modules.setdefault("claude_agent_sdk", types.ModuleType("claude_agent_sdk"))


class _FakeClaudeAgentOptions:
    def __init__(self, **kwargs) -> None:
        self.__dict__.update(kwargs)


class _FakePermissionResultAllow:
    pass


class _FakePermissionResultDeny:
    def __init__(self, message: str = "", interrupt: bool = False) -> None:
        self.message = message
        self.interrupt = interrupt


fake_claude_sdk.ClaudeAgentOptions = _FakeClaudeAgentOptions
fake_claude_sdk.ClaudeSDKClient = type("ClaudeSDKClient", (), {})
fake_claude_sdk.ProcessError = type("ProcessError", (Exception,), {})
fake_claude_sdk.AssistantMessage = type("AssistantMessage", (), {})
fake_claude_sdk.ResultMessage = type("ResultMessage", (), {})
fake_claude_sdk.TextBlock = type("TextBlock", (), {})
fake_claude_sdk.ThinkingBlock = type("ThinkingBlock", (), {})
fake_claude_sdk.ToolResultBlock = type("ToolResultBlock", (), {})
fake_claude_sdk.ToolUseBlock = type("ToolUseBlock", (), {})
fake_claude_sdk.UserMessage = type("UserMessage", (), {})
fake_claude_sdk.PermissionResultAllow = _FakePermissionResultAllow
fake_claude_sdk.PermissionResultDeny = _FakePermissionResultDeny

from app.claude import runner
from app.core.sse import sse_event


class ClaudeRunnerRetryTests(unittest.IsolatedAsyncioTestCase):
    def test_optimize_message_contains_only_command_context_and_user_request(self) -> None:
        message = runner._build_agent_message("sql_optimize", 42, 99, None, "帮我优化")

        self.assertIn("当前 command：sql_optimize", message)
        self.assertIn("当前 taskId：42", message)
        self.assertIn("当前 executionId：99", message)
        self.assertIn("当前 versionNo：未指定", message)
        self.assertIn("sql_task_get", message)
        self.assertIn("用户需求：\n帮我优化", message)
        self.assertNotIn("运行时预分析", message)

    def test_version_message_requires_immutable_version_tool(self) -> None:
        message = runner._build_agent_message("sql_explain", 42, None, 3, "解释版本")

        self.assertIn("当前 versionNo：3", message)
        self.assertIn("sql_task_version_get(taskId=42, versionNo=3)", message)

    async def test_resume_mcp_connection_error_triggers_fresh_retry(self) -> None:
        calls: list[tuple[object, str]] = []

        def fake_build_options(_ob_id: str, _command: str, **kwargs):
            return SimpleNamespace(**kwargs)

        async def fake_stream_once(options, _session_id: str, message: str):
            calls.append((options, message))
            if getattr(options, "resume", None):
                yield sse_event(
                    "tool_result",
                    {
                        "toolUseId": "toolu_1",
                        "content": "Error executing tool mcp__sql_agent__hive_search_tables: Remote end closed connection without response",
                        "isError": True,
                    },
                )
                yield sse_event("text", {"delta": "服务不可用"})
                yield sse_event("done", {"usage": {}, "sessionId": "session-1"})
            else:
                yield sse_event("text", {"delta": "fresh ok"})
                yield sse_event("done", {"usage": {}, "sessionId": "session-1"})

        with patch.object(runner, "build_options", side_effect=fake_build_options), patch.object(
            runner, "_stream_once", side_effect=fake_stream_once
        ):
            events = [event async for event in runner.run_chat("session-1", "138284", 42, "继续")]

        self.assertEqual(len(calls), 2)
        self.assertEqual(getattr(calls[0][0], "resume"), "session-1")
        self.assertFalse(hasattr(calls[1][0], "resume"))
        self.assertIn("重新调用相关 Hive/Data Map 工具", calls[1][1])
        self.assertIn("fresh ok", events[0]["data"])

    async def test_resume_non_connection_error_does_not_retry(self) -> None:
        calls: list[object] = []

        def fake_build_options(_ob_id: str, _command: str, **kwargs):
            return SimpleNamespace(**kwargs)

        async def fake_stream_once(options, _session_id: str, _message: str):
            calls.append(options)
            yield sse_event(
                "tool_result",
                {
                    "toolUseId": "toolu_1",
                    "content": "Error executing tool mcp__sql_agent__hive_search_tables: 参数校验失败",
                    "isError": True,
                },
            )
            yield sse_event("done", {"usage": {}, "sessionId": "session-1"})

        with patch.object(runner, "build_options", side_effect=fake_build_options), patch.object(
            runner, "_stream_once", side_effect=fake_stream_once
        ):
            events = [event async for event in runner.run_chat("session-1", "138284", 42, "继续")]

        self.assertEqual(len(calls), 1)
        self.assertEqual(events[0]["event"], "tool_result")

    async def test_resume_user_question_is_streamed_without_waiting_for_completion(self) -> None:
        calls: list[object] = []

        def fake_build_options(_ob_id: str, _command: str, **kwargs):
            return SimpleNamespace(**kwargs)

        async def fake_stream_once(options, _session_id: str, _message: str):
            calls.append(options)
            yield sse_event(
                "tool_use",
                {
                    "id": "toolu_question",
                    "name": "AskUserQuestion",
                    "input": {"questions": [{"question": "需要补充什么？"}]},
                },
            )
            yield sse_event(
                "user_question_request",
                {
                    "requestId": "question-1",
                    "questions": [{"question": "需要补充什么？"}],
                    "rawInput": {"questions": [{"question": "需要补充什么？"}]},
                },
            )

        with patch.object(runner, "build_options", side_effect=fake_build_options), patch.object(
            runner, "_stream_once", side_effect=fake_stream_once
        ):
            stream = runner.run_chat("session-1", "138284", 42, "继续")
            first = await stream.__anext__()
            second = await stream.__anext__()
            await stream.aclose()

        self.assertEqual(len(calls), 1)
        self.assertEqual(first["event"], "tool_use")
        self.assertEqual(second["event"], "user_question_request")


if __name__ == "__main__":
    unittest.main()
