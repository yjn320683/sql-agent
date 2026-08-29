"""SQL Agent Skill 配置校验。"""

from pathlib import Path

SQL_AGENT_SKILL_NAME = "sql-agent-rule"


def ensure_project_skill(agent_cwd: str) -> None:
    """确认 Claude Code 能从当前工作目录发现 SQL Agent Skill。"""

    target = Path(agent_cwd) / ".claude" / "skills" / SQL_AGENT_SKILL_NAME / "SKILL.md"
    if target.is_file():
        return
    raise FileNotFoundError(f"未找到 SQL Agent Skill：{target}")
