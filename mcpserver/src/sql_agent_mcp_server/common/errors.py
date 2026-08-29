"""统一错误模型。"""

from __future__ import annotations

from enum import StrEnum
from typing import Any

from pydantic import BaseModel, Field
from pydantic import ValidationError


class McpErrorCode(StrEnum):
    INVALID_REQUEST = "invalid_request"
    NOT_FOUND = "not_found"
    PERMISSION_DENIED = "permission_denied"
    DEPENDENCY_UNAVAILABLE = "dependency_unavailable"
    INTERNAL_ERROR = "internal_error"


class McpDomainError(Exception):
    """领域层可预期异常。"""

    def __init__(self, code: McpErrorCode, message: str, *, details: dict[str, Any] | None = None) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.details = details or {}


class ErrorResponse(BaseModel):
    ok: bool = Field(default=False)
    code: McpErrorCode
    message: str
    details: dict[str, Any] = Field(default_factory=dict)


def to_error_response(exc: Exception) -> dict[str, Any]:
    """把异常转换为稳定的 MCP tool 返回结构。"""

    if isinstance(exc, McpDomainError):
        return ErrorResponse(code=exc.code, message=exc.message, details=exc.details).model_dump(mode="json")
    if isinstance(exc, ValidationError):
        return ErrorResponse(
            code=McpErrorCode.INVALID_REQUEST,
            message="MCP tool request validation failed.",
            details={"errors": exc.errors(include_url=False, include_context=False, include_input=False)},
        ).model_dump(mode="json")
    return ErrorResponse(
        code=McpErrorCode.INTERNAL_ERROR,
        message="MCP tool execution failed.",
        details={"errorType": type(exc).__name__},
    ).model_dump(mode="json")
