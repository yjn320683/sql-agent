"""Hive Metastore 后端库搜索适配。"""

from __future__ import annotations

import re
from typing import Any

from sqlalchemy import create_engine, text
from sqlalchemy.engine import Engine

from sql_agent_mcp_server.common.errors import McpDomainError, McpErrorCode
from sql_agent_mcp_server.domains.hive_metadata.schemas import ColumnMetadata, ColumnUsage


class MetastoreSqlSearchAdapter:
    """通过 Metastore DB 做反向检索，不替代 Thrift 对象查询。"""

    def __init__(
        self, db_uri: str | None, *, engine: Engine | None = None, timeout_seconds: int = 10
    ) -> None:
        self.db_uri = db_uri
        self._engine = engine
        self.timeout_seconds = timeout_seconds

    def find_column_usage(
        self,
        *,
        catalog: str,
        column: str,
        db: str | None,
        table_pattern: str,
        limit: int,
        offset: int,
    ) -> tuple[list[ColumnUsage], int]:
        engine = self._get_engine()
        params = {
            "column": column,
            "db": db,
            "table_pattern": self._to_like_pattern(table_pattern) if table_pattern else None,
            "limit": limit,
            "offset": offset,
        }
        try:
            with engine.connect() as conn:
                total = conn.execute(text(self._count_sql()), params).scalar_one()
                rows = conn.execute(text(self._query_sql()), params).mappings().all()
        except McpDomainError:
            raise
        except Exception as exc:  # noqa: BLE001 - 数据库驱动异常类型不稳定
            raise McpDomainError(
                McpErrorCode.DEPENDENCY_UNAVAILABLE,
                "Hive Metastore DB query failed.",
                details={"errorType": type(exc).__name__, "errorMessage": _sanitize_error_message(str(exc))},
            ) from exc

        usages = [self._to_column_usage(catalog, row) for row in rows]
        return usages, int(total)

    def _get_engine(self) -> Engine:
        if self._engine is not None:
            return self._engine
        if not self.db_uri:
            raise McpDomainError(
                McpErrorCode.DEPENDENCY_UNAVAILABLE,
                "Hive Metastore DB URI is not configured.",
                details={"required": "HIVE_METASTORE_DB_URI"},
            )
        connect_args = {}
        if self.db_uri.startswith("mysql"):
            connect_args = {
                "connect_timeout": self.timeout_seconds,
                "read_timeout": self.timeout_seconds,
                "write_timeout": self.timeout_seconds,
            }
        self._engine = create_engine(self.db_uri, pool_pre_ping=True, connect_args=connect_args)
        return self._engine

    @staticmethod
    def _to_like_pattern(pattern: str) -> str:
        sql_pattern = pattern.replace("*", "%")
        if "%" in sql_pattern or "_" in sql_pattern:
            return sql_pattern
        return f"%{sql_pattern}%"

    @staticmethod
    def _to_column_usage(catalog: str, row: dict[str, Any]) -> ColumnUsage:
        return ColumnUsage(
            catalog=catalog,
            db=row["db_name"],
            table=row["table_name"],
            column=ColumnMetadata(
                name=row["column_name"],
                dataType=row["data_type"],
                comment=row["comment"],
                partitionKey=bool(row["partition_key"]),
            ),
        )

    @staticmethod
    def _base_sql() -> str:
        return """
            SELECT
              d.NAME AS db_name,
              t.TBL_NAME AS table_name,
              c.COLUMN_NAME AS column_name,
              c.TYPE_NAME AS data_type,
              c.COMMENT AS comment,
              0 AS partition_key
            FROM DBS d
            JOIN TBLS t ON t.DB_ID = d.DB_ID
            JOIN SDS s ON s.SD_ID = t.SD_ID
            JOIN COLUMNS_V2 c ON c.CD_ID = s.CD_ID
            WHERE c.COLUMN_NAME = :column
              AND (:db IS NULL OR d.NAME = :db)
              AND (:table_pattern IS NULL OR t.TBL_NAME LIKE :table_pattern)
            UNION ALL
            SELECT
              d.NAME AS db_name,
              t.TBL_NAME AS table_name,
              p.PKEY_NAME AS column_name,
              p.PKEY_TYPE AS data_type,
              p.PKEY_COMMENT AS comment,
              1 AS partition_key
            FROM DBS d
            JOIN TBLS t ON t.DB_ID = d.DB_ID
            JOIN PARTITION_KEYS p ON p.TBL_ID = t.TBL_ID
            WHERE p.PKEY_NAME = :column
              AND (:db IS NULL OR d.NAME = :db)
              AND (:table_pattern IS NULL OR t.TBL_NAME LIKE :table_pattern)
        """

    @classmethod
    def _query_sql(cls) -> str:
        return (
            "SELECT * FROM ("
            + cls._base_sql()
            + ") usage_rows ORDER BY db_name, table_name, partition_key, column_name LIMIT :limit OFFSET :offset"
        )

    @classmethod
    def _count_sql(cls) -> str:
        return "SELECT COUNT(*) FROM (" + cls._base_sql() + ") usage_rows"


def _sanitize_error_message(message: str) -> str:
    """隐藏错误信息中 URL 形式连接串的密码。"""

    return re.sub(r"([a-zA-Z][a-zA-Z0-9+.-]*://[^:\s/@]+:)([^@\s/]+)(@)", r"\1******\3", message)
