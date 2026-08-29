from app.domain.sql.commands import DEFAULT_SQL_COMMAND, normalize_command


def test_normalize_command_uses_structured_value():
    result = normalize_command("sql_optimize", "帮我看看")

    assert result.command == "sql_optimize"
    assert result.message == "帮我看看"
    assert result.matched_prefix == ""


def test_normalize_command_parses_chinese_prefix():
    result = normalize_command(None, "/sql修复 select * from t")

    assert result.command == "sql_fix"
    assert result.message == "select * from t"
    assert result.matched_prefix == "/sql修复"


def test_normalize_command_defaults_to_generate():
    result = normalize_command("", "写一个订单汇总")

    assert result.command == DEFAULT_SQL_COMMAND
    assert result.message == "写一个订单汇总"
