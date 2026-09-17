"""HiveServer2 请求与响应模型。"""

from __future__ import annotations

from pydantic import BaseModel, Field

from sql_agent_mcp_server.common.schemas import DiagnosticFactResponse


class HiveSqlRequest(BaseModel):
    sql: str = Field(min_length=1, max_length=1_000_000)
    default_db: str | None = Field(default=None, alias="defaultDb", min_length=1, max_length=256)


class HiveExplainRequest(HiveSqlRequest):
    extended: bool = False


class HivePreviewRequest(HiveSqlRequest):
    limit: int = Field(default=100, ge=1, le=200)
    timeout_seconds: int = Field(default=30, alias="timeoutSeconds", ge=1, le=30)


class HiveValidationResponse(DiagnosticFactResponse):
    valid: bool
    default_db: str = Field(alias="defaultDb")
    compilation_ms: int = Field(alias="compilationMs")
    errors: list[dict[str, str]] = Field(default_factory=list)


class HiveExplainResponse(DiagnosticFactResponse):
    plan_source: str = Field(default="predicted", alias="planSource")
    plan_text: str = Field(alias="planText")
    default_db: str = Field(alias="defaultDb")
    compilation_ms: int = Field(alias="compilationMs")
    truncated: bool = False


class HivePreviewResponse(DiagnosticFactResponse):
    default_db: str = Field(alias="defaultDb")
    columns: list[dict[str, str | None]]
    rows: list[list[object | None]]
    row_count: int = Field(alias="rowCount")
    truncated: bool = False
    elapsed_ms: int = Field(alias="elapsedMs")


class HiveFunctionSearchRequest(BaseModel):
    keyword: str = Field(default="", max_length=256)
    limit: int = Field(default=50, ge=1, le=200)
    offset: int = Field(default=0, ge=0)
    default_db: str | None = Field(default=None, alias="defaultDb", min_length=1, max_length=256)


class HiveFunctionSearchResponse(DiagnosticFactResponse):
    items: list[str]
    total: int
    limit: int
    offset: int
    keyword: str
    default_db: str = Field(alias="defaultDb")
    elapsed_ms: int = Field(alias="elapsedMs")


class HiveFunctionDetailRequest(BaseModel):
    name: str = Field(min_length=1, max_length=256)
    default_db: str | None = Field(default=None, alias="defaultDb", min_length=1, max_length=256)


class HiveFunctionDetailResponse(DiagnosticFactResponse):
    name: str
    lines: list[str]
    properties: dict[str, str]
    default_db: str = Field(alias="defaultDb")
    elapsed_ms: int = Field(alias="elapsedMs")
    truncated: bool = False
