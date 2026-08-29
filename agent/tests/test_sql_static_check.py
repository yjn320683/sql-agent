from app.domain.sql.static_check import static_check_sql


def test_static_check_reports_empty_sql():
    result = static_check_sql("")

    assert result.passed is False
    assert result.issues[0]["code"] == "EMPTY_SQL"


def test_static_check_reports_select_star_and_missing_where():
    result = static_check_sql("select * from dw.orders")

    codes = {item["code"] for item in result.issues}
    assert "SELECT_STAR" in codes
    assert "NO_WHERE" in codes


def test_static_check_rejects_drop_table():
    result = static_check_sql("drop table dw.orders")

    assert result.passed is False
    assert any(item["code"] == "DANGEROUS_DDL" for item in result.issues)


def test_static_check_reports_parse_error():
    result = static_check_sql("select from")

    assert result.passed is False
    assert any(item["code"] == "PARSE_ERROR" for item in result.issues)


def test_static_check_detects_dangerous_statement_in_multi_statement_sql():
    result = static_check_sql("select id from dw.orders where dt='20260820'; truncate table dw.tmp")

    assert result.passed is False
    assert any(item["code"] == "DANGEROUS_DDL" for item in result.issues)


def test_static_check_does_not_match_keywords_inside_literals_or_comments():
    result = static_check_sql("select 'drop table x' as note from dw.orders where dt='20260820' -- truncate table y")

    assert result.passed is True
    assert not any(item["code"] == "DANGEROUS_DDL" for item in result.issues)


def test_static_check_supports_spark_sql_alias():
    result = static_check_sql("select id from dw.orders where dt='20260820'", dialect="spark-sql")

    assert result.passed is True


def test_static_check_reports_cartesian_join_as_error():
    result = static_check_sql("select a.id from dw.a a cross join dw.b b")

    assert result.passed is False
    assert any(item["code"] == "CARTESIAN_JOIN" for item in result.issues)


def test_static_check_reports_expensive_global_operations():
    result = static_check_sql(
        "select count(distinct id) from dw.a "
        "union select row_number() over(order by id) from dw.b order by id"
    )

    codes = {item["code"] for item in result.issues}
    assert {
        "COUNT_DISTINCT",
        "UNION_DISTINCT",
        "WINDOW_WITHOUT_PARTITION",
        "GLOBAL_ORDER_WITHOUT_LIMIT",
    }.issubset(codes)


def test_static_check_does_not_report_union_all_as_distinct():
    result = static_check_sql("select id from dw.a union all select id from dw.b")

    assert not any(item["code"] == "UNION_DISTINCT" for item in result.issues)


def test_static_check_reports_high_join_count_once():
    joins = " ".join(f"join dw.t{i} on base.id = t{i}.id" for i in range(8))
    result = static_check_sql(f"select base.id from dw.base base {joins}")

    issues = [item for item in result.issues if item["code"] == "HIGH_JOIN_COUNT"]
    assert len(issues) == 1
    assert "8 个 JOIN" in issues[0]["message"]
