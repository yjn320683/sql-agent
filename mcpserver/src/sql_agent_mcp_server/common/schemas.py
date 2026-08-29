"""公共 Schema。"""

from __future__ import annotations

from datetime import datetime, timezone

from pydantic import BaseModel, Field


class PageRequest(BaseModel):
    limit: int = Field(default=20, ge=1, le=100)
    offset: int = Field(default=0, ge=0)


class FactResponse(BaseModel):
    ok: bool = Field(default=True)
    source: str
    fetched_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc), alias="fetchedAt")
    warnings: list[str] = Field(default_factory=list)


class DiagnosticFactResponse(FactResponse):
    """可能因历史保留、采样或依赖能力而不完整的诊断事实。"""

    complete: bool = True
    missing_reasons: list[str] = Field(default_factory=list, alias="missingReasons")


def dump_response(model: BaseModel) -> dict:
    """用 camelCase alias 输出 JSON 兼容结构。"""

    return model.model_dump(mode="json", by_alias=True)
