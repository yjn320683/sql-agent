"""聊天和权限 API 请求模型。"""

from __future__ import annotations

from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field

UUID_PATTERN = r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"


class ChatRequest(BaseModel):
    """聊天请求：sessionId、obId、taskId、SQL 命令与用户需求。"""

    session_id: str = Field(alias="sessionId", pattern=UUID_PATTERN)
    ob_id: str = Field(alias="obId", pattern=r"^[0-9]{1,20}$")
    task_id: int = Field(alias="taskId", gt=0)
    execution_id: Optional[int] = Field(default=None, alias="executionId", gt=0)
    version_no: Optional[int] = Field(default=None, alias="versionNo", gt=0)
    command: Optional[str] = None
    message: str = Field(min_length=1, max_length=200_000)

    model_config = {"populate_by_name": True}

class CancelRequest(BaseModel):
    """取消当前会话正在运行的请求。"""

    session_id: str = Field(alias="sessionId", pattern=UUID_PATTERN)

    model_config = {"populate_by_name": True}


class PermissionDecision(BaseModel):
    """工具权限决策：由 Web 页面确认后回传。"""

    decision: str = Field(pattern="^(allow|deny)$")


class UserQuestionAnswer(BaseModel):
    """业务澄清问题答案：由 Web 页面提交真实选择值。"""

    answers: Optional[List[Dict[str, Any]]] = None
    cancelled: Optional[bool] = False
    reason: Optional[str] = None
