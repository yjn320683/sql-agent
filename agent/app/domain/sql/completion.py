"""基于真实 Hive 元数据的 SQL 编辑器补全。"""

from __future__ import annotations

import re
import threading
import time
from dataclasses import dataclass
from typing import Any, Callable

import sqlglot
from sqlglot import exp

from sql_agent_mcp_server.domains.hive_execution.schemas import HiveFunctionSearchRequest
from sql_agent_mcp_server.domains.hive_execution.service import HiveExecutionService
from sql_agent_mcp_server.domains.hive_metadata.schemas import (
    GetColumnsRequest,
    ListDatabasesRequest,
    SearchTablesRequest,
)
from sql_agent_mcp_server.domains.hive_metadata.service import HiveMetadataService

_WORD = re.compile(r"[A-Za-z_][A-Za-z0-9_$]*$")
_QUALIFIED = re.compile(r"([A-Za-z_][A-Za-z0-9_$]*)\.([A-Za-z0-9_$]*)$")


@dataclass(frozen=True)
class CompletionContext:
    start: int
    end: int
    prefix: str
    qualifier: str | None


class SqlCompletionService:
    def __init__(self, metadata: HiveMetadataService, execution: HiveExecutionService) -> None:
        self.metadata = metadata
        self.execution = execution
        self._cache: dict[tuple[Any, ...], tuple[float, Any]] = {}
        self._lock = threading.Lock()

    def complete(self, sql: str, cursor: int, default_db: str | None, limit: int) -> dict[str, Any]:
        if cursor < 0 or cursor > len(sql):
            raise ValueError("cursor 超出 SQL 长度")
        context = _completion_context(sql, cursor)
        parseable_sql = sql[:context.start] + "__sql_agent_completion__" + sql[context.end:]
        aliases, tables = _table_context(parseable_sql, default_db)
        warnings: list[str] = []
        items: list[dict[str, Any]] = []

        if context.qualifier:
            target = aliases.get(context.qualifier.lower())
            if target:
                items.extend(self._columns(target[0], target[1], context.prefix, context.qualifier, warnings))
            else:
                databases = self._databases(warnings)
                if context.qualifier.lower() in {item.lower() for item in databases}:
                    items.extend(self._tables(context.qualifier, context.prefix, warnings))
                else:
                    target = next((item for item in tables if item[1].lower() == context.qualifier.lower()), None)
                    if target:
                        items.extend(self._columns(target[0], target[1], context.prefix, context.qualifier, warnings))
        else:
            items.extend({"label": db, "type": "database", "detail": "Hive 数据库"}
                         for db in self._databases(warnings) if _matches(db, context.prefix))
            items.extend(self._tables(default_db, context.prefix, warnings))
            for db, table in tables[:8]:
                items.extend(self._columns(db, table, context.prefix, None, warnings))
            items.extend(self._functions(context.prefix, default_db, warnings))

        deduplicated: list[dict[str, Any]] = []
        seen: set[tuple[str, str, str]] = set()
        for item in items:
            key = (str(item.get("label", "")).lower(), str(item.get("type", "")), str(item.get("detail", "")))
            if key in seen:
                continue
            seen.add(key)
            deduplicated.append(item)
            if len(deduplicated) >= limit:
                break
        return {
            "ok": True,
            "source": "sqlglot+hive-metastore+hiveserver2",
            "from": context.start,
            "to": context.end,
            "items": deduplicated,
            "warnings": list(dict.fromkeys(warnings)),
            "complete": len(deduplicated) < limit and not warnings,
            "missingReasons": ["dependency_partial"] if warnings else [],
        }

    def _databases(self, warnings: list[str]) -> list[str]:
        try:
            response = self._cached(("databases",), 30, lambda: self.metadata.list_databases(ListDatabasesRequest()))
            return list(response.databases)
        except Exception as exc:  # noqa: BLE001
            warnings.append(f"Hive 数据库补全不可用：{type(exc).__name__}")
            return []

    def _tables(self, db: str | None, prefix: str, warnings: list[str]) -> list[dict[str, Any]]:
        try:
            response = self._cached(
                ("tables", db or "", prefix.lower()), 15,
                lambda: self.metadata.search_tables(SearchTablesRequest(
                    db=db, pattern=prefix, limit=100, offset=0,
                )),
            )
            return [{
                "label": table.table,
                "type": "table",
                "detail": f"{table.db}.{table.table}",
                "db": table.db,
                "table": table.table,
            } for table in response.items]
        except Exception as exc:  # noqa: BLE001
            warnings.append(f"Hive 表补全不可用：{type(exc).__name__}")
            return []

    def _columns(
        self, db: str, table: str, prefix: str, qualifier: str | None, warnings: list[str],
    ) -> list[dict[str, Any]]:
        if not db:
            return []
        try:
            response = self._cached(
                ("columns", db.lower(), table.lower()), 30,
                lambda: self.metadata.get_columns(GetColumnsRequest(db=db, table=table)),
            )
            return [{
                "label": column.name,
                "apply": column.name,
                "type": "column",
                "detail": f"{qualifier or table} · {column.data_type}",
                "db": db,
                "table": table,
                "dataType": column.data_type,
                "comment": column.comment,
            } for column in response.columns if _matches(column.name, prefix)]
        except Exception as exc:  # noqa: BLE001
            warnings.append(f"字段补全不可用 {db}.{table}：{type(exc).__name__}")
            return []

    def _functions(self, prefix: str, default_db: str | None, warnings: list[str]) -> list[dict[str, Any]]:
        try:
            response = self._cached(
                ("functions", default_db or "", prefix.lower()), 30,
                lambda: self.execution.search_functions(HiveFunctionSearchRequest(
                    keyword=prefix, limit=100, offset=0, defaultDb=default_db,
                )),
            )
            return [{"label": name, "type": "function", "detail": "HiveServer2 函数"}
                    for name in response.items]
        except Exception as exc:  # noqa: BLE001
            warnings.append(f"Hive 函数补全不可用：{type(exc).__name__}")
            return []

    def _cached(self, key: tuple[Any, ...], ttl: int, loader: Callable[[], Any]) -> Any:
        now = time.monotonic()
        with self._lock:
            cached = self._cache.get(key)
            if cached and cached[0] > now:
                return cached[1]
        value = loader()
        with self._lock:
            self._cache[key] = (now + ttl, value)
            if len(self._cache) > 512:
                expired = [item for item, cached in self._cache.items() if cached[0] <= now]
                for item in expired[:256]:
                    self._cache.pop(item, None)
        return value


def _completion_context(sql: str, cursor: int) -> CompletionContext:
    before = sql[:cursor]
    qualified = _QUALIFIED.search(before)
    if qualified:
        return CompletionContext(qualified.start(2), cursor, qualified.group(2), qualified.group(1))
    word = _WORD.search(before)
    return CompletionContext(word.start() if word else cursor, cursor, word.group(0) if word else "", None)


def _table_context(sql: str, default_db: str | None) -> tuple[dict[str, tuple[str, str]], list[tuple[str, str]]]:
    aliases: dict[str, tuple[str, str]] = {}
    tables: list[tuple[str, str]] = []
    try:
        statements = sqlglot.parse(sql, read="hive")
    except Exception:  # noqa: BLE001 - 输入中的 SQL 经常处于未完成状态
        return aliases, tables
    for statement in statements:
        if statement is None:
            continue
        ctes = {item.alias_or_name.lower() for item in statement.find_all(exp.CTE) if item.alias_or_name}
        for table in statement.find_all(exp.Table):
            if not table.name or (not table.db and table.name.lower() in ctes):
                continue
            db = table.db or default_db or ""
            pair = (db, table.name)
            if pair not in tables:
                tables.append(pair)
            if table.alias:
                aliases[table.alias.lower()] = pair
            aliases.setdefault(table.name.lower(), pair)
    return aliases, tables


def _matches(value: str, prefix: str) -> bool:
    return not prefix or value.lower().startswith(prefix.lower())
