"""Data map Schema。"""

from __future__ import annotations

from pydantic import BaseModel, Field

from sql_agent_mcp_server.common.schemas import FactResponse


class GetTablePrimaryKeysRequest(BaseModel):
    db: str = Field(min_length=1)
    table: str = Field(min_length=1)


class TablePrimaryKeys(BaseModel):
    db: str
    table: str
    primary_keys: list[str] = Field(default_factory=list, alias="primaryKeys")


class TablePrimaryKeysResponse(FactResponse):
    result: TablePrimaryKeys
