"""HiveServer2 PyHive 只读访问。"""

from __future__ import annotations

import re
import time
from datetime import date, datetime
from decimal import Decimal
from contextlib import closing
from dataclasses import dataclass
from urllib.parse import parse_qs, urlparse

from sql_agent_mcp_server.common.errors import McpDomainError, McpErrorCode

MAX_PLAN_CHARS = 36_000
MAX_FUNCTION_DETAIL_CHARS = 16_000
FUNCTION_NAME_PATTERN = re.compile(r"^[A-Za-z_][A-Za-z0-9_.$]{0,255}$")
PLAN_SIGNAL_PATTERN = re.compile(
    r"(?i)(stage dependencies|stage:|conditional operator|map reduce|table ?scan|alias:|statistics:|"
    r"join operator|condition map|outer join|inner join|semi join|anti join|keys:|key expressions:|"
    r"partition columns|sort order:|reduce operator tree|group by operator|ptf operator|window|"
    r"partition by:|order by:|filter operator|predicate:|file output operator|fetch operator|"
    r"limit:|execution mode|input format:|output format:)"
)


@dataclass(frozen=True)
class ExplainResult:
    plan_text: str
    default_db: str
    compilation_ms: int
    truncated: bool


@dataclass(frozen=True)
class FunctionSearchResult:
    items: list[str]
    total: int
    limit: int
    offset: int
    elapsed_ms: int
    default_db: str


@dataclass(frozen=True)
class FunctionDetailResult:
    name: str
    lines: list[str]
    properties: dict[str, str]
    elapsed_ms: int
    default_db: str
    truncated: bool


@dataclass(frozen=True)
class PreviewResult:
    columns: list[dict[str, str | None]]
    rows: list[list[object | None]]
    default_db: str
    elapsed_ms: int
    truncated: bool


class HiveCompilationError(Exception):
    """目标引擎返回的编译错误。"""

    def __init__(self, message: str, *, compilation_ms: int = 0, default_db: str = "default") -> None:
        super().__init__(message)
        self.compilation_ms = compilation_ms
        self.default_db = default_db


def _is_describable_function(name: str) -> bool:
    """过滤 SHOW FUNCTIONS 中无法用于 DESCRIBE FUNCTION 的运算符。"""
    return FUNCTION_NAME_PATTERN.fullmatch(name) is not None


class HiveServer2Adapter:
    source = "hive-server2"

    def __init__(self, uri: str | None, *, timeout_seconds: int) -> None:
        self.uri = uri
        self.timeout_seconds = timeout_seconds

    def health(self) -> int:
        return self.explain("SELECT 1", default_db=None, extended=False).compilation_ms

    def preview_query(
        self,
        sql: str,
        *,
        default_db: str | None,
        limit: int,
        timeout_seconds: int = 30,
    ) -> PreviewResult:
        _validate_preview_input(sql, limit)
        connection_info = self._connection_info(default_db)
        started_at = time.perf_counter()
        connection = self._connect(connection_info, timeout_seconds=timeout_seconds)
        try:
            with closing(connection):
                with closing(connection.cursor()) as cursor:
                    cursor.execute(sql)
                    description = list(cursor.description or [])
                    raw_rows = list(cursor.fetchmany(limit + 1))
        except Exception as exc:  # noqa: BLE001 - PyHive/Thrift 异常类型随版本变化
            raise McpDomainError(
                McpErrorCode.DEPENDENCY_UNAVAILABLE,
                "HiveServer2 preview query failed.",
                details={"dependency": "HIVE_SERVER2_URI", "errorType": type(exc).__name__},
            ) from exc
        truncated = len(raw_rows) > limit
        rows = [[_json_value(value) for value in row] for row in raw_rows[:limit]]
        columns = [
            {"name": str(item[0]), "type": str(item[1]) if len(item) > 1 and item[1] is not None else None}
            for item in description
        ]
        return PreviewResult(
            columns=columns,
            rows=rows,
            default_db=str(connection_info["database"]),
            elapsed_ms=int((time.perf_counter() - started_at) * 1000),
            truncated=truncated,
        )

    def search_functions(
        self,
        *,
        keyword: str,
        limit: int,
        offset: int,
        default_db: str | None,
    ) -> FunctionSearchResult:
        connection_info = self._connection_info(default_db)
        started_at = time.perf_counter()
        connection = self._connect(connection_info)
        try:
            with closing(connection):
                with closing(connection.cursor()) as cursor:
                    cursor.execute("SHOW FUNCTIONS")
                    rows = cursor.fetchall()
        except Exception as exc:  # noqa: BLE001 - PyHive/Thrift 异常类型随版本变化
            raise McpDomainError(
                McpErrorCode.DEPENDENCY_UNAVAILABLE,
                "HiveServer2 function catalog query failed.",
                details={"dependency": "HIVE_SERVER2_URI", "errorType": type(exc).__name__},
            ) from exc

        normalized_keyword = keyword.strip().lower()
        names = sorted({
            str(row[0]).strip()
            for row in rows
            if row and _is_describable_function(str(row[0]).strip())
        })
        if normalized_keyword:
            names = [name for name in names if normalized_keyword in name.lower()]
        return FunctionSearchResult(
            items=names[offset : offset + limit],
            total=len(names),
            limit=limit,
            offset=offset,
            elapsed_ms=int((time.perf_counter() - started_at) * 1000),
            default_db=str(connection_info["database"]),
        )
    def get_function(self, name: str, *, default_db: str | None) -> FunctionDetailResult:
        normalized_name = name.strip()
        if not FUNCTION_NAME_PATTERN.fullmatch(normalized_name):
            raise McpDomainError(
                McpErrorCode.INVALID_REQUEST,
                "Hive function name is invalid.",
                details={"requiredFormat": "letters, digits, underscore, dot or dollar sign"},
            )

        connection_info = self._connection_info(default_db)
        started_at = time.perf_counter()
        connection = self._connect(connection_info)
        try:
            with closing(connection):
                with closing(connection.cursor()) as cursor:
                    cursor.execute(f"DESCRIBE FUNCTION EXTENDED {normalized_name}")
                    rows = cursor.fetchall()
        except Exception as exc:  # noqa: BLE001 - PyHive/Thrift 异常类型随版本变化
            raise McpDomainError(
                McpErrorCode.DEPENDENCY_UNAVAILABLE,
                "HiveServer2 function description query failed.",
                details={"dependency": "HIVE_SERVER2_URI", "errorType": type(exc).__name__},
            ) from exc

        raw_lines = [
            "\t".join(str(value) for value in row if value is not None).strip()
            for row in rows
            if row
        ]
        lines = [line for line in raw_lines if line]
        detail_text = "\n".join(lines)
        if not lines or re.search(r"(?i)(function.+(?:does not exist|not found)|no function)", detail_text):
            raise McpDomainError(
                McpErrorCode.NOT_FOUND,
                "Hive function was not found.",
                details={"function": normalized_name},
            )

        compacted, truncated = _truncate_function_lines(lines)
        return FunctionDetailResult(
            name=normalized_name,
            lines=compacted,
            properties=_parse_function_properties(compacted),
            elapsed_ms=int((time.perf_counter() - started_at) * 1000),
            default_db=str(connection_info["database"]),
            truncated=truncated,
        )

    def explain(self, sql: str, *, default_db: str | None, extended: bool) -> ExplainResult:
        _validate_explain_input(sql)
        connection_info = self._connection_info(default_db)
        started_at = time.perf_counter()
        connection = self._connect(connection_info)

        try:
            from pyhive.exc import DatabaseError, OperationalError
        except ImportError as exc:
            raise McpDomainError(
                McpErrorCode.DEPENDENCY_UNAVAILABLE,
                "HiveServer2 client dependency is not installed.",
                details={"required": "PyHive"},
            ) from exc

        try:
            with closing(connection):
                with closing(connection.cursor()) as cursor:
                    prefix = "EXPLAIN EXTENDED" if extended else "EXPLAIN"
                    cursor.execute(f"{prefix} {sql.strip().rstrip(';')}")
                    rows = cursor.fetchall()
        except (OperationalError, DatabaseError) as exc:
            raise HiveCompilationError(
                _sanitize_compilation_error(str(exc)),
                compilation_ms=int((time.perf_counter() - started_at) * 1000),
                default_db=str(connection_info["database"]),
            ) from exc

        raw_plan = "\n".join(str(row[0]) if len(row) == 1 else "\t".join(map(str, row)) for row in rows)
        plan, truncated = _compact_plan(raw_plan)
        return ExplainResult(
            plan_text=plan,
            default_db=connection_info["database"],
            compilation_ms=int((time.perf_counter() - started_at) * 1000),
            truncated=truncated,
        )

    def _connect(
        self,
        connection_info: dict[str, str | int | None],
        *,
        timeout_seconds: int | None = None,
    ):
        try:
            from pyhive import hive
            from pyhive.exc import OperationalError
        except ImportError as exc:
            raise McpDomainError(
                McpErrorCode.DEPENDENCY_UNAVAILABLE,
                "HiveServer2 client dependency is not installed.",
                details={"required": "PyHive"},
            ) from exc

        try:
            transport = _build_transport(
                str(connection_info["host"]),
                int(connection_info["port"]),
                connection_info["username"] if isinstance(connection_info["username"], str) else None,
                str(connection_info["auth"]),
                timeout_seconds if timeout_seconds is not None else self.timeout_seconds,
            )
            return hive.connect(
                database=str(connection_info["database"]),
                username=(
                    connection_info["username"]
                    if isinstance(connection_info["username"], str)
                    else None
                ),
                thrift_transport=transport,
            )
        except OperationalError as exc:
            raise McpDomainError(
                McpErrorCode.DEPENDENCY_UNAVAILABLE,
                "HiveServer2 connection failed.",
                details={"dependency": "HIVE_SERVER2_URI", "errorType": type(exc).__name__},
            ) from exc
        except Exception as exc:  # noqa: BLE001 - PyHive/Thrift 异常在不同版本下类型不稳定
            raise McpDomainError(
                McpErrorCode.DEPENDENCY_UNAVAILABLE,
                "HiveServer2 connection failed.",
                details={"dependency": "HIVE_SERVER2_URI", "errorType": type(exc).__name__},
            ) from exc

    def _connection_info(self, default_db: str | None) -> dict[str, str | int | None]:
        if not self.uri:
            raise McpDomainError(
                McpErrorCode.DEPENDENCY_UNAVAILABLE,
                "HiveServer2 URI is not configured.",
                details={"required": "HIVE_SERVER2_URI"},
            )
        parsed = urlparse(self.uri if "://" in self.uri else f"hive://{self.uri}")
        if not parsed.hostname:
            raise McpDomainError(
                McpErrorCode.INVALID_REQUEST,
                "HiveServer2 URI is invalid.",
                details={"requiredFormat": "hive://host:10000/database"},
            )
        query = parse_qs(parsed.query)
        auth = query.get("auth", ["NONE"])[0].upper()
        if auth not in {"NONE", "NOSASL"}:
            raise McpDomainError(
                McpErrorCode.INVALID_REQUEST,
                "Only unauthenticated HiveServer2 connections are supported.",
                details={"supportedAuth": ["NONE", "NOSASL"]},
            )
        return {
            "host": parsed.hostname,
            "port": parsed.port or 10000,
            "database": default_db or parsed.path.strip("/") or "default",
            "username": query.get("user", [None])[0],
            "auth": auth,
        }


def _validate_explain_input(sql: str) -> None:
    stripped = sql.strip()
    if re.match(r"(?is)^explain\s+analyze\b", stripped):
        raise McpDomainError(
            McpErrorCode.INVALID_REQUEST,
            "EXPLAIN ANALYZE is not allowed.",
        )
    if _has_multiple_statements(stripped):
        raise McpDomainError(
            McpErrorCode.INVALID_REQUEST,
            "Only one SQL statement can be explained at a time.",
        )


def _validate_preview_input(sql: str, limit: int) -> None:
    stripped = sql.strip()
    if limit < 1 or limit > 200:
        raise McpDomainError(McpErrorCode.INVALID_REQUEST, "Preview limit must be between 1 and 200.")
    if _has_multiple_statements(stripped):
        raise McpDomainError(McpErrorCode.INVALID_REQUEST, "Only one SQL statement can be previewed.")
    if not re.match(r"(?is)^select\s+\*\s+from\s*\(", stripped):
        raise McpDomainError(McpErrorCode.INVALID_REQUEST, "Only a server-limited read-only preview query is allowed.")
    if not re.search(rf"(?is)\blimit\s+{limit}\s*;?\s*$", stripped):
        raise McpDomainError(McpErrorCode.INVALID_REQUEST, "Preview query must include the enforced row limit.")


def _json_value(value):
    if isinstance(value, (datetime, date)):
        return value.isoformat()
    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, bytes):
        return value.hex()
    return value


def _has_multiple_statements(sql: str) -> bool:
    quote: str | None = None
    escaped = False
    semicolon_seen = False
    for index, char in enumerate(sql):
        if escaped:
            escaped = False
            continue
        if char == "\\" and quote:
            escaped = True
            continue
        if quote:
            if char == quote:
                quote = None
            continue
        if char in {"'", '"', "`"}:
            quote = char
            continue
        if char == ";":
            if sql[index + 1 :].strip():
                return True
            semicolon_seen = True
    return semicolon_seen and not sql.rstrip().endswith(";")


def _sanitize_compilation_error(message: str) -> str:
    compact = re.sub(r"\s+", " ", message).strip()
    compact = re.sub(r"(?i)(jdbc:hive2|hive|thrift)://\S+", "<redacted-endpoint>", compact)
    marker = "Error while compiling statement:"
    if marker in compact:
        compact = compact[compact.index(marker):]
    compact = re.split(
        r"',\s*'(?:org\.apache\.|java\.|sun\.|com\.sun\.)",
        compact,
        maxsplit=1,
    )[0]
    compact = compact.rstrip("', )]")
    if len(compact) > 800:
        compact = compact[:797].rstrip() + "..."
    return compact


def _compact_plan(plan: str, max_chars: int = MAX_PLAN_CHARS) -> tuple[str, bool]:
    """压缩超长 Explain，同时保留跨 stage 的关键执行信号。"""

    if len(plan) <= max_chars:
        return plan, False

    lines = plan.splitlines()
    selected = [line for line in lines if PLAN_SIGNAL_PATTERN.search(line)]
    header = (
        f"[PLAN COMPACTED originalChars={len(plan)} "
        f"selectedLines={len(selected)}/{len(lines)}]\n"
    )
    compacted = header + "\n".join(selected)
    if len(compacted) <= max_chars:
        return compacted, True

    marker = "\n[... COMPACTED PLAN MIDDLE OMITTED ...]\n"
    available = max_chars - len(marker)
    head_size = available // 2
    return compacted[:head_size] + marker + compacted[-(available - head_size):], True


def _truncate_function_lines(lines: list[str]) -> tuple[list[str], bool]:
    total = 0
    selected: list[str] = []
    for line in lines:
        required = len(line) + (1 if selected else 0)
        if total + required > MAX_FUNCTION_DETAIL_CHARS:
            break
        selected.append(line)
        total += required
    return selected, len(selected) < len(lines)


def _parse_function_properties(lines: list[str]) -> dict[str, str]:
    properties: dict[str, str] = {}
    aliases = {
        "function": "function",
        "class": "class",
        "function_class": "class",
        "function_type": "type",
        "usage": "usage",
        "extended_usage": "extended_usage",
    }
    for index, line in enumerate(lines):
        key, separator, value = line.partition(":")
        normalized_key = key.strip().lower().replace(" ", "_")
        target_key = aliases.get(normalized_key)
        if separator and target_key:
            properties[target_key] = value.strip()
        elif index == 0 and " - " in line:
            properties["usage"] = line
    return properties


def _build_transport(host: str, port: int, username: str | None, auth: str, timeout_seconds: int):
    """构造带连接/读取超时的 PyHive Thrift transport。"""

    from thrift.transport import TSocket, TTransport

    socket = TSocket.TSocket(host, port)
    socket.setTimeout(timeout_seconds * 1000)
    if auth == "NOSASL":
        return TTransport.TBufferedTransport(socket)

    import getpass
    import thrift_sasl
    from pyhive.hive import get_installed_sasl

    resolved_user = username or getpass.getuser()
    return thrift_sasl.TSaslClientTransport(
        lambda: get_installed_sasl(
            host=host,
            sasl_auth="PLAIN",
            service=None,
            username=resolved_user,
            password="x",
        ),
        "PLAIN",
        socket,
    )
