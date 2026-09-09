import json
import sys
import types
import unittest

fake_claude_sdk = sys.modules.setdefault("claude_agent_sdk", types.ModuleType("claude_agent_sdk"))

fake_claude_sdk.AssistantMessage = type("AssistantMessage", (), {})
fake_claude_sdk.ResultMessage = type("ResultMessage", (), {})
fake_claude_sdk.TextBlock = type("TextBlock", (), {})
fake_claude_sdk.ThinkingBlock = type("ThinkingBlock", (), {})
fake_claude_sdk.ToolResultBlock = type("ToolResultBlock", (), {})
fake_claude_sdk.ToolUseBlock = type("ToolUseBlock", (), {})
fake_claude_sdk.UserMessage = type("UserMessage", (), {})

from app.claude import events


class ClaudeEventsTests(unittest.TestCase):
    @staticmethod
    def _message(message_type, **values):
        value = object.__new__(message_type)
        for key, item in values.items():
            setattr(value, key, item)
        return value

    def test_platform_proposal_has_dedicated_sse_event(self) -> None:
        mapper = events.EventMapper()
        assistant_msg = self._message(events.AssistantMessage,
            content=[{
                "type": "tool_use",
                "id": "toolu_proposal",
                "name": "mcp__sql_agent__platform_proposal_present",
                "input": {"target": "sql", "kind": "SQL", "before": "select 1", "after": "select 2", "baseRevision": 3},
            }],
            model="test-model",
        )
        mapped = mapper.map_message_events(assistant_msg)
        self.assertEqual("proposal", mapped[0]["event"])
        self.assertEqual("select 2", json.loads(mapped[0]["data"])["after"])

    def test_ask_user_question_result_is_not_exposed_as_error(self) -> None:
        mapper = events.EventMapper()
        assistant_msg = self._message(events.AssistantMessage,
            content=[
                {
                    "type": "tool_use",
                    "id": "toolu_1",
                    "name": "AskUserQuestion",
                    "input": {"questions": [{"question": "分析什么业务场景？"}]},
                }
            ],
            model="test-model",
        )

        user_msg = self._message(events.UserMessage,
            content=[
                {
                    "type": "tool_result",
                    "tool_use_id": "toolu_1",
                    "content": "用户已通过前端回答澄清问题",
                    "is_error": True,
                }
            ]
        )

        mapper.map_message_events(assistant_msg)
        mapped_events = mapper.map_message_events(user_msg)
        payload = json.loads(mapped_events[0]["data"])

        self.assertFalse(payload["isError"])
        self.assertEqual(payload["semanticType"], "user_question_answer")


if __name__ == "__main__":
    unittest.main()
