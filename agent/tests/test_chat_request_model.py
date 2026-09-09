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


def test_chat_request_accepts_page_context_without_task_id():
    req = ChatRequest.model_validate(
        {
            "sessionId": "11111111-1111-4111-8111-111111111111",
            "obId": "10001",
            "context": {"contextType": "REALTIME_TABLE", "entityId": "9", "revision": 2},
            "intent": "RECOMMEND",
            "message": "建议表属性",
        }
    )
    assert req.task_id is None
    assert req.context is not None
    assert req.context.context_type == "REALTIME_TABLE"


def test_chat_request_rejects_missing_business_context():
    with pytest.raises(ValidationError):
        ChatRequest.model_validate(
            {
                "sessionId": "11111111-1111-4111-8111-111111111111",
                "obId": "10001",
                "message": "优化 SQL",
            }
        )
