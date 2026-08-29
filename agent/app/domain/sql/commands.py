"""SQL 命令解析与规范化。"""

from __future__ import annotations

from dataclasses import dataclass

SqlCommand = str
DEFAULT_SQL_COMMAND: SqlCommand = "sql_generate"

COMMAND_PREFIXES: dict[str, SqlCommand] = {
    "/sql生成": "sql_generate",
    "/sql优化": "sql_optimize",
    "/sql修复": "sql_fix",
    "/sql解释": "sql_explain",
    "/sql静态检查": "sql_static_check",
}

VALID_COMMANDS = set(COMMAND_PREFIXES.values())


@dataclass(frozen=True)
class CommandParseResult:
    """规范化后的 SQL 命令和去掉命令前缀的用户消息。"""

    command: SqlCommand
    message: str
    matched_prefix: str = ""


def normalize_command(command: str | None, message: str) -> CommandParseResult:
    """优先使用结构化 command；没有时从中文斜杠前缀解析。"""

    structured = str(command or "").strip()
    if structured in VALID_COMMANDS:
        return CommandParseResult(command=structured, message=message)

    trimmed = message.lstrip()
    for prefix, resolved in COMMAND_PREFIXES.items():
        if trimmed == prefix or trimmed.startswith(prefix + " ") or trimmed.startswith(prefix + "\n"):
            return CommandParseResult(
                command=resolved,
                message=trimmed[len(prefix):].lstrip(),
                matched_prefix=prefix,
            )
    return CommandParseResult(command=DEFAULT_SQL_COMMAND, message=message)
