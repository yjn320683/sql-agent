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
    def test_ask_user_question_result_is_not_exposed_as_error(self) -> None:
        mapper = events.EventMapper()
        assistant_msg = events.AssistantMessage(
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

        user_msg = events.UserMessage(
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
