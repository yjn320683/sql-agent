from pathlib import Path


def _skill_dir() -> Path:
    return Path(__file__).resolve().parents[2] / "agent/.claude/skills/sql-agent-rule"


def test_sql_skill_has_entry_and_references():
    skill_dir = _skill_dir()
    for path in [
        "SKILL.md",
        "references/sql-generate.md",
        "references/sql-optimize.md",
        "references/sql-fix.md",
        "references/sql-explain.md",
        "references/sql-static-check.md",
        "references/mcp-interfaces.md",
        "references/sql-optimize-data.md",
        "references/sql-optimize-rules.md",
        "references/sql-equivalence-checklist.md",
    ]:
        assert (skill_dir / path).is_file()


def test_sql_skill_documents_command_flows_and_hive_on_mapreduce_tools():
    repo_root = Path(__file__).resolve().parents[2]
    skill_dir = _skill_dir()
    skill_content = (skill_dir / "SKILL.md").read_text(encoding="utf-8")
    mcp_reference = (skill_dir / "references/mcp-interfaces.md").read_text(encoding="utf-8")
    docs_content = (repo_root / "docs/sql-agent-mcp-interfaces.md").read_text(encoding="utf-8")

    for required_section in [
        "## 能力边界与无关问题提示",
        "## 结构化澄清问题",
        "## 通用工作流程",
        "## 输出质量闸门",
        "## 失败处理",
    ]:
        assert required_section in skill_content

    for tool_name in [
        "platform_dependency_health_get",
        "hive_task_sql_validate",
        "hive_task_sql_explain",
        "hive_sql_validate",
        "hive_sql_explain",
        "hive_function_search",
        "hive_function_get",
        "hive_table_statistics_get",
        "hive_storage_layout_get",
        "yarn_application_diagnostics_get",
        "mapreduce_job_search",
        "mapreduce_job_diagnostics_get",
        "mapreduce_job_compare",
    ]:
        assert tool_name in mcp_reference
        assert tool_name in docs_content

    combined = "\n".join(
        path.read_text(encoding="utf-8")
        for path in [skill_dir / "SKILL.md", *(skill_dir / "references").glob("*.md")]
    )
    assert "Hive on MapReduce" in combined
    assert "一条 Hive SQL 可能" in combined
    assert "planSource=predicted" in combined
    assert "EXPLAIN ANALYZE" in combined
    for unsupported in ["Tez", "DAG ID", "Vertex"]:
        assert unsupported not in combined


def test_optimize_skill_keeps_evidence_and_equivalence_gates():
    skill_dir = _skill_dir()
    optimize_content = (skill_dir / "references/sql-optimize.md").read_text(encoding="utf-8")
    data_content = (skill_dir / "references/sql-optimize-data.md").read_text(encoding="utf-8")
    rules_content = (skill_dir / "references/sql-optimize-rules.md").read_text(encoding="utf-8")
    equivalence_content = (skill_dir / "references/sql-equivalence-checklist.md").read_text(encoding="utf-8")

    for required in [
        "## 输入判断", "## 取数流程", "## 诊断流程", "## 改写边界", "## 输出格式", "## 质量闸门",
        "references/sql-optimize-rules.md", "references/sql-optimize-data.md", "references/sql-equivalence-checklist.md",
        "不擅自添加 `limit`", "`select *` 的完整输出字段未确认时不得臆造字段",
    ]:
        assert required in optimize_content
    for required in [
        "## 证据层次", "## 三种模式", "## 标识链", "## 分阶段取数", "## 数据质量", "## 原因链", "## 验证",
        "memory/vcore-seconds", "min/p50/p95/max", "shuffle", "spill", "NDV", "文件数",
    ]:
        assert required in data_content
    assert "## Hive on MapReduce 诊断规则" in rules_content
    assert "outer join" in rules_content
    assert "## 结果契约" in equivalence_content
    assert "NULL" in equivalence_content


def test_other_command_skills_keep_safety_contracts():
    skill_dir = _skill_dir()
    generate = (skill_dir / "references/sql-generate.md").read_text(encoding="utf-8")
    fix = (skill_dir / "references/sql-fix.md").read_text(encoding="utf-8")
    explain = (skill_dir / "references/sql-explain.md").read_text(encoding="utf-8")
    static = (skill_dir / "references/sql-static-check.md").read_text(encoding="utf-8")

    assert "不生成自动 DDL" in generate
    assert "不自动建表" in fix
    assert "mapreduce_job_diagnostics_get" in fix
    assert "逻辑语义、预测计划和实际运行事实" in explain
    assert "完整 MR Job 集合" in explain
    assert "函数或 UDF 不确定时调用 `hive_function_search/get`" in static
    assert "通过不代表 SQL 可执行、性能良好或可以上线" in static
    for rule_code in ["EMPTY_SQL", "SELECT_STAR", "NO_WHERE", "DANGEROUS_DDL", "FUNCTION_ON_PARTITION_FIELD"]:
        assert rule_code in static
