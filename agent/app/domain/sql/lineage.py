"""基于 SQL AST 提取任务级静态表血缘。"""

from __future__ import annotations

from typing import Any

import sqlglot
from sqlglot import exp
from sqlglot.errors import ParseError

from app.execution.sql_script import split_sql_steps


def analyze_sql_lineage(sql: str | None, default_db: str | None = None) -> dict[str, Any]:
    """提取输入表、输出表和 CTE；不执行 SQL。"""

    value = (sql or "").strip()
    if not value:
        raise ValueError("SQL 为空，无法解析血缘。")
    statements: list[exp.Expression] = []
    try:
        step_parts = split_sql_steps(value)
        for step_no, _, step_sql in step_parts:
            try:
                statements.extend(item for item in sqlglot.parse(step_sql, read="hive") if item is not None)
            except (ParseError, ValueError) as exc:
                first_line = str(exc).splitlines()[0].strip()
                prefix = f"Step {step_no} " if len(step_parts) > 1 or step_no != 0 else ""
                raise ValueError(f"Hive SQL 解析失败：{prefix}{first_line[:300]}") from exc
    except ValueError as exc:
        if str(exc).startswith("Hive SQL 解析失败："):
            raise
        raise ValueError(f"Hive SQL 解析失败：{str(exc)[:300]}") from exc
    if not statements:
        raise ValueError("SQL 中没有可解析的语句。")

    normalized_default_db = (default_db or "").strip() or None
    outputs: list[dict[str, Any]] = []
    inputs: list[dict[str, Any]] = []
    ctes: list[dict[str, Any]] = []
    warnings: list[str] = []

    if len(statements) > 1:
        warnings.append(f"任务包含 {len(statements)} 条语句，静态血缘已合并展示。")

    for statement in statements:
        statement_ctes = {
            cte.alias_or_name.lower(): cte
            for cte in statement.find_all(exp.CTE)
            if cte.alias_or_name
        }
        statement_outputs = _statement_outputs(statement, normalized_default_db)
        output_node = statement.this if isinstance(statement, exp.Insert) else None
        outputs.extend(statement_outputs)

        for cte_name, cte in statement_ctes.items():
            dependencies = []
            for table in cte.this.find_all(exp.Table):
                if _is_cte_reference(table, statement_ctes):
                    dependencies.append({"kind": "cte", "name": table.name})
                else:
                    dependencies.append({"kind": "table", **_table_reference(table, normalized_default_db)})
            ctes.append({"name": cte_name, "dependencies": _deduplicate_dependencies(dependencies)})

        for table in statement.find_all(exp.Table):
            if _is_cte_reference(table, statement_ctes):
                continue
            if table is output_node:
                continue
            reference = _table_reference(table, normalized_default_db)
            inputs.append(reference)

    inputs = _deduplicate(inputs)
    outputs = _deduplicate(outputs)
    ctes = _deduplicate(ctes, key_fields=("name",))
    unresolved = [item for item in [*inputs, *outputs] if not item.get("db")]
    if unresolved:
        warnings.append("部分表未限定数据库且未选择默认库，无法完成 Metastore 对象校验。")
    if "${" in value or "{{" in value:
        warnings.append("SQL 包含模板变量，静态血缘可能在运行参数替换后发生变化。")

    return {
        "source": "sqlglot-static-lineage",
        "defaultDb": normalized_default_db,
        "statementCount": len(statements),
        "inputs": inputs,
        "outputs": outputs,
        "ctes": ctes,
        "warnings": warnings,
        "complete": not unresolved and "${" not in value and "{{" not in value,
        "missingReasons": ["unqualified_database"] if unresolved else [],
    }


def _statement_outputs(statement: exp.Expression, default_db: str | None) -> list[dict[str, Any]]:
    outputs: list[dict[str, Any]] = []
    if isinstance(statement, exp.Insert) and isinstance(statement.this, exp.Table):
        outputs.append(_table_reference(statement.this, default_db))
    return outputs


def _is_cte_reference(table: exp.Table, ctes: dict[str, exp.CTE]) -> bool:
    return not table.db and not table.catalog and table.name.lower() in ctes


def _table_reference(table: exp.Table, default_db: str | None) -> dict[str, Any]:
    db = table.db or default_db
    catalog = table.catalog or None
    qualified = ".".join(item for item in (catalog, db, table.name) if item)
    return {
        "catalog": catalog,
        "db": db,
        "table": table.name,
        "qualifiedName": qualified,
    }


def _table_key(reference: dict[str, Any]) -> tuple[str, str, str]:
    return (
        str(reference.get("catalog") or "").lower(),
        str(reference.get("db") or "").lower(),
        str(reference.get("table") or "").lower(),
    )


def _deduplicate(
    items: list[dict[str, Any]],
    key_fields: tuple[str, ...] | None = None,
) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    seen: set[tuple[str, ...]] = set()
    for item in items:
        key = (
            tuple(str(item.get(field) or "").lower() for field in key_fields)
            if key_fields
            else tuple(str(value).lower() for value in _table_key(item))
        )
        if key in seen:
            continue
        seen.add(key)
        result.append(item)
    return result


def _deduplicate_dependencies(items: list[dict[str, Any]]) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    seen: set[tuple[str, ...]] = set()
    for item in items:
        if item.get("kind") == "cte":
            key = ("cte", str(item.get("name") or "").lower())
        else:
            key = ("table", *_table_key(item))
        if key in seen:
            continue
        seen.add(key)
        result.append(item)
    return result
