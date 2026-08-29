import pytest
from pydantic import ValidationError

from app.models import ChatRequest


def test_chat_request_requires_task_id_and_accepts_execution_id():
    req = ChatRequest.model_validate(
        {
            "sessionId": "11111111-1111-4111-8111-111111111111",
            "obId": "10001",
            "taskId": 42,
            "executionId": 99,
            "command": "sql_optimize",
            "message": "优化 SQL",
        }
    )

    assert req.command == "sql_optimize"
    assert req.task_id == 42
    assert req.execution_id == 99


def test_chat_request_rejects_missing_task_id():
    with pytest.raises(ValidationError):
        ChatRequest.model_validate(
            {
                "sessionId": "11111111-1111-4111-8111-111111111111",
                "obId": "10001",
                "message": "优化 SQL",
            }
        )
