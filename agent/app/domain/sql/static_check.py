"""基于 SQL AST 的静态检查规则。"""

from __future__ import annotations

from dataclasses import dataclass, field

import sqlglot
from sqlglot import exp
from sqlglot.errors import ParseError


@dataclass(frozen=True)
class SqlStaticCheckResult:
    """SQL 静态检查结果。"""

    passed: bool
    issues: list[dict[str, str]] = field(default_factory=list)


def static_check_sql(sql: str | None, dialect: str = "hive") -> SqlStaticCheckResult:
    """解析 SQL 后执行跨语句、可重复的结构检查。"""

    if not sql or not sql.strip():
        return SqlStaticCheckResult(
            passed=False,
            issues=[_issue("error", "EMPTY_SQL", "SQL 为空。", "请提供需要检查的 SQL。")],
        )

    try:
        statements = sqlglot.parse(sql, read=_normalize_dialect(dialect))
    except (ParseError, ValueError) as exc:
        return SqlStaticCheckResult(
            passed=False,
            issues=[_issue("error", "PARSE_ERROR", _parse_error_message(exc), "请先修复语法或确认 SQL 方言。")],
        )

    issues: list[dict[str, str]] = []
    if any(_has_projection_star(statement) for statement in statements):
        issues.append(_issue("warning", "SELECT_STAR", "SQL 投影中存在 *。", "建议只选择需要的字段，减少传输和下游处理成本。"))
    if any(_select_without_where(statement) for statement in statements):
        issues.append(_issue("warning", "NO_WHERE", "至少一个查询块没有 WHERE 条件。", "请确认是否需要分区或时间过滤条件。"))
    if any(_is_dangerous_ddl(statement) for statement in statements):
        issues.append(_issue("error", "DANGEROUS_DDL", "SQL 包含 DROP TABLE 或 TRUNCATE TABLE。", "执行前必须获得明确审批并确认影响范围。"))
    if any(_has_date_wrapped_column(statement) for statement in statements):
        issues.append(
            _issue(
                "warning",
                "FUNCTION_ON_PARTITION_FIELD",
                "SQL 中存在 DATE(字段) 表达式。",
                "如果该字段是分区字段，请确认函数包裹是否影响分区裁剪。",
            )
        )
    if any(_has_cartesian_join(statement) for statement in statements):
        issues.append(
            _issue(
                "error",
                "CARTESIAN_JOIN",
                "SQL 存在 CROSS JOIN 或缺少有效关联条件的 JOIN。",
                "请补充明确的 ON/USING 条件；若确需笛卡尔积，应先确认数据规模与资源影响。",
            )
        )
    if any(_has_global_order_without_limit(statement) for statement in statements):
        issues.append(
            _issue(
                "warning",
                "GLOBAL_ORDER_WITHOUT_LIMIT",
                "SQL 存在没有 LIMIT 的全局 ORDER BY。",
                "Hive 全局排序通常汇聚到单个 Reducer，请确认是否可改用 SORT BY、DISTRIBUTE BY 或限制结果集。",
            )
        )
    if any(_has_union_distinct(statement) for statement in statements):
        issues.append(
            _issue(
                "warning",
                "UNION_DISTINCT",
                "SQL 使用 UNION 去重。",
                "如果业务允许重复记录，请改用 UNION ALL，避免额外的全局去重阶段。",
            )
        )
    if any(_has_count_distinct(statement) for statement in statements):
        issues.append(
            _issue(
                "warning",
                "COUNT_DISTINCT",
                "SQL 使用 COUNT(DISTINCT ...)。",
                "请结合实际基数和执行计划确认聚合倾斜、内存与 Shuffle 成本。",
            )
        )
    if any(_has_unpartitioned_window(statement) for statement in statements):
        issues.append(
            _issue(
                "warning",
                "WINDOW_WITHOUT_PARTITION",
                "SQL 存在未设置 PARTITION BY 的窗口函数。",
                "请确认是否确实需要全量窗口；大表上可能产生单分区排序和长尾任务。",
            )
        )
    join_count = sum(1 for statement in statements for _ in statement.find_all(exp.Join))
    if join_count >= 8:
        issues.append(
            _issue(
                "warning",
                "HIGH_JOIN_COUNT",
                f"SQL 包含 {join_count} 个 JOIN。",
                "请检查关联顺序、过滤下推、维表大小与数据倾斜，并结合实际 Job 指标确认瓶颈。",
            )
        )
    return SqlStaticCheckResult(
        passed=not any(item["level"] == "error" for item in issues),
        issues=issues,
    )


def _normalize_dialect(dialect: str) -> str:
    value = (dialect or "hive").strip().lower()
    aliases = {"spark-sql": "spark", "sparksql": "spark"}
    return aliases.get(value, value)


def _has_projection_star(statement: exp.Expression) -> bool:
    return any(projection.is_star for select in statement.find_all(exp.Select) for projection in select.expressions)


def _select_without_where(statement: exp.Expression) -> bool:
    return any(select.args.get("from_") is not None and select.args.get("where") is None for select in statement.find_all(exp.Select))


def _is_dangerous_ddl(statement: exp.Expression) -> bool:
    if isinstance(statement, exp.TruncateTable):
        return True
    return isinstance(statement, exp.Drop) and str(statement.args.get("kind") or "").upper() == "TABLE"


def _has_date_wrapped_column(statement: exp.Expression) -> bool:
    return any(function.find(exp.Column) is not None for function in statement.find_all(exp.Date))


def _has_cartesian_join(statement: exp.Expression) -> bool:
    for join in statement.find_all(exp.Join):
        if str(join.args.get("kind") or "").upper() == "CROSS":
            return True
        condition = join.args.get("on")
        if isinstance(condition, exp.Boolean) and condition.this is True:
            return True
    return False


def _has_global_order_without_limit(statement: exp.Expression) -> bool:
    queries = [statement, *statement.find_all(exp.Select), *statement.find_all(exp.SetOperation)]
    return any(
        query.args.get("order") is not None and query.args.get("limit") is None
        for query in queries
    )


def _has_union_distinct(statement: exp.Expression) -> bool:
    return any(union.args.get("distinct") is not False for union in statement.find_all(exp.Union))


def _has_count_distinct(statement: exp.Expression) -> bool:
    return any(isinstance(count.this, exp.Distinct) for count in statement.find_all(exp.Count))


def _has_unpartitioned_window(statement: exp.Expression) -> bool:
    return any(not window.args.get("partition_by") for window in statement.find_all(exp.Window))


def _parse_error_message(exc: Exception) -> str:
    first_line = str(exc).splitlines()[0].strip()
    return f"SQL 解析失败：{first_line[:300]}"


def _issue(level: str, code: str, message: str, suggestion: str) -> dict[str, str]:
    return {"level": level, "code": code, "message": message, "suggestion": suggestion}
