"""Data Map 表详情查询适配器。"""

from __future__ import annotations

from sqlalchemy import create_engine, text
from sqlalchemy.engine import Engine

from sql_agent_mcp_server.common.errors import McpDomainError, McpErrorCode
from sql_agent_mcp_server.domains.data_map.schemas import TablePrimaryKeys


class TableDetailInfoAdapter:
    """通过 Data Map MySQL 查询表主键信息。"""

    source = "data_map_mysql"

    def __init__(
        self, db_uri: str | None, *, engine: Engine | None = None, timeout_seconds: int = 10
    ) -> None:
        self.db_uri = db_uri
        self._engine = engine
        self.timeout_seconds = timeout_seconds

    def get_table_primary_keys(self, *, db_name: str, table_name: str) -> TablePrimaryKeys:
        engine = self._get_engine()
        params = {"db_name": db_name, "table_name": table_name}
        try:
            with engine.connect() as conn:
                row = conn.execute(text(self._query_sql()), params).mappings().first()
        except McpDomainError:
            raise
        except Exception as exc:  # noqa: BLE001 - 数据库驱动异常类型不稳定
            raise McpDomainError(
                McpErrorCode.DEPENDENCY_UNAVAILABLE,
                "Data Map DB query failed.",
                details={"errorType": type(exc).__name__},
            ) from exc

        if row is None:
            raise McpDomainError(
                McpErrorCode.NOT_FOUND,
                "Table detail info not found.",
                details={"db": db_name, "table": table_name},
            )

        return TablePrimaryKeys(
            db=db_name,
            table=table_name,
            primaryKeys=self._parse_primary_keys(row["key_info"]),
        )

    def _get_engine(self) -> Engine:
        if self._engine is not None:
            return self._engine
        if not self.db_uri:
            raise McpDomainError(
                McpErrorCode.DEPENDENCY_UNAVAILABLE,
                "Data Map DB URI is not configured.",
                details={"required": "DATA_MAP_DB_URI"},
            )
        connect_args = {
            "connect_timeout": self.timeout_seconds,
            "read_timeout": self.timeout_seconds,
            "write_timeout": self.timeout_seconds,
        }
        self._engine = create_engine(self.db_uri, pool_pre_ping=True, connect_args=connect_args)
        return self._engine

    @staticmethod
    def _query_sql() -> str:
        return """
            SELECT key_info
            FROM table_detail_info
            WHERE db_name = :db_name AND table_name = :table_name
            LIMIT 1
        """

    @staticmethod
    def _parse_primary_keys(key_info: str | None) -> list[str]:
        if not key_info:
            return []
        return [item.strip() for item in key_info.split(",") if item.strip()]
