"""Hive SQL 执行白名单。"""

from __future__ import annotations

import sqlglot
from sqlglot import exp
from sqlglot.errors import ParseError


def validate_executable_sql(sql: str) -> str:
    value = (sql or "").strip()
    if not value:
        raise ValueError("SQL不能为空")
    try:
        statements = sqlglot.parse(value, read="hive")
    except ParseError as exc:
        raise ValueError("Hive SQL 语法解析失败") from exc
    if len(statements) != 1 or statements[0] is None:
        raise ValueError("仅允许执行一条 Hive SQL")
    statement = statements[0]
    if not isinstance(statement, (exp.Select, exp.Union, exp.Insert)):
        raise ValueError("仅允许 SELECT、WITH 或 INSERT 类型的 Hive SQL")
    forbidden = (exp.Create, exp.Drop, exp.Alter, exp.Delete, exp.Update, exp.Merge, exp.Command)
    if any(statement.find(node_type) is not None for node_type in forbidden):
        raise ValueError("SQL包含不允许执行的 DDL、管理命令或变更语句")
    return value.rstrip(";").rstrip()
