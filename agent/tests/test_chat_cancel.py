import asyncio
import sys
import types
import unittest

fake_claude_sdk = types.ModuleType("claude_agent_sdk")


class _FakeClaudeAgentOptions:
    def __init__(self, **kwargs) -> None:
        self.__dict__.update(kwargs)


class _FakeClaudeSDKClient:
    pass


class _FakeProcessError(Exception):
    pass


class _FakeAssistantMessage:
    pass


class _FakeResultMessage:
    pass


class _FakeTextBlock:
    pass


class _FakeThinkingBlock:
    pass


class _FakeToolResultBlock:
    pass


class _FakeToolUseBlock:
    pass


class _FakeUserMessage:
    pass


class _FakePermissionResultAllow:
    pass


class _FakePermissionResultDeny:
    def __init__(self, message: str = "", interrupt: bool = False) -> None:
        self.message = message
        self.interrupt = interrupt


fake_claude_sdk.ClaudeAgentOptions = _FakeClaudeAgentOptions
fake_claude_sdk.ClaudeSDKClient = _FakeClaudeSDKClient
fake_claude_sdk.ProcessError = _FakeProcessError
fake_claude_sdk.PermissionResultAllow = _FakePermissionResultAllow
fake_claude_sdk.PermissionResultDeny = _FakePermissionResultDeny
fake_claude_sdk.AssistantMessage = _FakeAssistantMessage
fake_claude_sdk.ResultMessage = _FakeResultMessage
fake_claude_sdk.TextBlock = _FakeTextBlock
fake_claude_sdk.ThinkingBlock = _FakeThinkingBlock
fake_claude_sdk.ToolResultBlock = _FakeToolResultBlock
fake_claude_sdk.ToolUseBlock = _FakeToolUseBlock
fake_claude_sdk.UserMessage = _FakeUserMessage
sys.modules.setdefault("claude_agent_sdk", fake_claude_sdk)

from app.claude import runner


class ChatCancelTests(unittest.IsolatedAsyncioTestCase):
    async def test_cancel_chat_cancels_registered_task(self) -> None:
        async def wait_forever() -> None:
            await asyncio.sleep(3600)

        task = asyncio.create_task(wait_forever())
        runner._RUNNING_TASKS["session-1"] = task

        try:
            self.assertTrue(runner.cancel_chat("session-1"))
            with self.assertRaises(asyncio.CancelledError):
                await task
        finally:
            runner._RUNNING_TASKS.pop("session-1", None)

    async def test_cancel_chat_returns_false_for_missing_session(self) -> None:
        self.assertFalse(runner.cancel_chat("missing-session"))


if __name__ == "__main__":
    unittest.main()
