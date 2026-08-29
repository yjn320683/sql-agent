"""Hive SQL Step 解析与类型化运行参数渲染。"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from decimal import Decimal, InvalidOperation
from typing import Any

from app.execution.sql_policy import validate_executable_sql

STEP_MARKER = re.compile(
    r"^\s*====\s*step\s*:\s*(\d+)(?:\s*:\s*([^=\r\n]+))?\s*====\s*$",
    re.IGNORECASE | re.MULTILINE,
)
NAMED_PARAMETER = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)\}")
LEGACY_DATE_PARAMETER = re.compile(
    r"\$\{(yyyy(?:[-/]MM(?:[-/]dd)?)?|yyyyMMdd|MM[-/]dd|dd)(?:,(-?\d+),(day|month|year))?\}",
    re.IGNORECASE,
)
SUPPORTED_TYPES = {"STRING", "INTEGER", "DECIMAL", "DATE", "DATETIME", "BOOLEAN"}


@dataclass(frozen=True)
class SqlStep:
    number: int
    order: int
    name: str
    source_sql: str
    rendered_sql: str


def parse_and_render_script(
    sql: str,
    parameter_schema_json: str | None,
    parameter_values_json: str | None,
    business_date: Any = None,
) -> tuple[str, list[SqlStep], dict[str, Any]]:
    """渲染参数并返回可顺序执行的 Step。"""
    definitions = _definitions(parameter_schema_json)
    supplied = _object(parameter_values_json)
    values = _resolve_values(definitions, supplied)
    base_date = _parse_date(business_date) if business_date else None
    rendered_script = _render(str(sql or ""), definitions, values, base_date)
    source_parts = split_sql_steps(str(sql or ""))
    rendered_parts = split_sql_steps(rendered_script)
    if len(source_parts) != len(rendered_parts):
        raise ValueError("参数渲染改变了Step结构")

    steps: list[SqlStep] = []
    previous = -1
    seen: set[int] = set()
    for order, (source, rendered) in enumerate(zip(source_parts, rendered_parts)):
        number, name, source_sql = source
        rendered_number, _, rendered_sql = rendered
        if number != rendered_number:
            raise ValueError("参数渲染改变了Step编号")
        if number in seen:
            raise ValueError(f"Step编号重复：{number}")
        if number <= previous:
            raise ValueError("Step编号必须按脚本顺序递增")
        seen.add(number)
        previous = number
        validated = validate_executable_sql(rendered_sql)
        steps.append(SqlStep(number, order, name or f"Step {number}", source_sql, validated))
    return rendered_script, steps, values


def split_sql_steps(sql: str) -> list[tuple[int, str | None, str]]:
    """按任务 Step 标记拆分脚本，供执行与静态分析共享。"""
    source = sql.strip()
    if not source:
        raise ValueError("SQL不能为空")
    matches = list(STEP_MARKER.finditer(source))
    if not matches:
        return [(0, "Step 0", source)]
    if source[: matches[0].start()].strip():
        raise ValueError("首个Step标记前不能包含SQL")
    result: list[tuple[int, str | None, str]] = []
    for index, marker in enumerate(matches):
        end = matches[index + 1].start() if index + 1 < len(matches) else len(source)
        step_sql = source[marker.end() : end].strip()
        number = int(marker.group(1))
        if not step_sql:
            raise ValueError(f"Step {number} 不能为空")
        result.append((number, (marker.group(2) or "").strip() or None, step_sql))
    return result


def _definitions(raw: str | None) -> dict[str, dict[str, Any]]:
    if not raw or not raw.strip():
        return {}
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise ValueError("任务参数定义不是有效JSON") from exc
    if not isinstance(parsed, list):
        raise ValueError("任务参数定义必须是数组")
    result: dict[str, dict[str, Any]] = {}
    for item in parsed:
        if not isinstance(item, dict):
            raise ValueError("任务参数定义格式非法")
        name = str(item.get("name") or "").strip()
        kind = str(item.get("type") or "").strip().upper()
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]{0,63}", name):
            raise ValueError(f"参数名称非法：{name}")
        if name in result:
            raise ValueError(f"参数名称重复：{name}")
        if kind not in SUPPORTED_TYPES:
            raise ValueError(f"参数 {name} 的类型非法")
        result[name] = {**item, "name": name, "type": kind}
    return result


def _object(raw: str | None) -> dict[str, Any]:
    if not raw or not raw.strip():
        return {}
    try:
        value = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise ValueError("执行参数不是有效JSON") from exc
    if not isinstance(value, dict):
        raise ValueError("执行参数必须是对象")
    return value


def _resolve_values(definitions: dict[str, dict[str, Any]], supplied: dict[str, Any]) -> dict[str, Any]:
    unknown = sorted(set(supplied) - set(definitions))
    if unknown:
        raise ValueError("存在未声明的运行参数：" + ", ".join(unknown))
    result: dict[str, Any] = {}
    for name, definition in definitions.items():
        value = supplied.get(name, definition.get("defaultValue"))
        if value is None or value == "":
            if definition.get("required", True):
                raise ValueError(f"缺少必填运行参数：{name}")
            result[name] = None
        else:
            result[name] = value
    return result


def _render(
    sql: str,
    definitions: dict[str, dict[str, Any]],
    values: dict[str, Any],
    business_date: date | None,
) -> str:
    def legacy(match: re.Match[str]) -> str:
        if business_date is None:
            raise ValueError("SQL包含日期偏移表达式，执行时必须填写业务日期")
        current = business_date
        offset = int(match.group(2) or 0)
        unit = (match.group(3) or "day").lower()
        if unit == "day":
            current += timedelta(days=offset)
        elif unit == "month":
            current = _add_months(current, offset)
        else:
            current = _add_months(current, offset * 12)
        return _format_legacy_date(current, match.group(1))

    rendered = LEGACY_DATE_PARAMETER.sub(legacy, sql)

    def named(match: re.Match[str]) -> str:
        name = match.group(1)
        definition = definitions.get(name)
        if definition is None:
            raise ValueError(f"SQL引用了未声明的运行参数：{name}")
        return _sql_literal(definition["type"], values.get(name))

    rendered = NAMED_PARAMETER.sub(named, rendered)
    unresolved = re.findall(r"\$\{[^}]+\}", rendered)
    if unresolved:
        raise ValueError("存在无法识别的参数表达式：" + ", ".join(sorted(set(unresolved))))
    return rendered


def _sql_literal(kind: str, value: Any) -> str:
    if value is None:
        return "NULL"
    if kind == "STRING":
        return "'" + str(value).replace("'", "''") + "'"
    if kind == "INTEGER":
        if isinstance(value, bool):
            raise ValueError("INTEGER参数不能使用布尔值")
        try:
            return str(int(str(value)))
        except ValueError as exc:
            raise ValueError(f"INTEGER参数值非法：{value}") from exc
    if kind == "DECIMAL":
        try:
            decimal = Decimal(str(value))
        except InvalidOperation as exc:
            raise ValueError(f"DECIMAL参数值非法：{value}") from exc
        if not decimal.is_finite():
            raise ValueError("DECIMAL参数必须是有限数值")
        return format(decimal, "f")
    if kind == "DATE":
        return "'" + _parse_date(value).isoformat() + "'"
    if kind == "DATETIME":
        try:
            parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        except ValueError as exc:
            raise ValueError(f"DATETIME参数值非法：{value}") from exc
        return "'" + parsed.strftime("%Y-%m-%d %H:%M:%S") + "'"
    if kind == "BOOLEAN":
        if isinstance(value, bool):
            return "TRUE" if value else "FALSE"
        normalized = str(value).strip().lower()
        if normalized in {"true", "1"}:
            return "TRUE"
        if normalized in {"false", "0"}:
            return "FALSE"
        raise ValueError(f"BOOLEAN参数值非法：{value}")
    raise ValueError(f"不支持的参数类型：{kind}")


def _parse_date(value: Any) -> date:
    if isinstance(value, datetime):
        return value.date()
    if isinstance(value, date):
        return value
    try:
        return date.fromisoformat(str(value))
    except ValueError as exc:
        raise ValueError(f"DATE参数值非法：{value}") from exc


def _add_months(value: date, months: int) -> date:
    month_index = value.year * 12 + value.month - 1 + months
    year, month_zero = divmod(month_index, 12)
    month = month_zero + 1
    days = [31, 29 if year % 4 == 0 and (year % 100 != 0 or year % 400 == 0) else 28,
            31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
    return date(year, month, min(value.day, days[month - 1]))


def _format_legacy_date(value: date, pattern: str) -> str:
    tokens = {
        "yyyy-MM-dd": "%Y-%m-%d", "yyyy/MM/dd": "%Y/%m/%d", "yyyyMMdd": "%Y%m%d",
        "yyyy-MM": "%Y-%m", "yyyy/MM": "%Y/%m", "yyyy": "%Y",
        "MM-dd": "%m-%d", "MM/dd": "%m/%d", "dd": "%d",
    }
    selected = next((fmt for key, fmt in tokens.items() if key.lower() == pattern.lower()), None)
    if selected is None:
        raise ValueError(f"不支持的日期格式：{pattern}")
    return value.strftime(selected)
