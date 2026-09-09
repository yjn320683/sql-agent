"""Claude SDK options 构造。"""

import os
import sys
from pathlib import Path

from claude_agent_sdk import ClaudeAgentOptions

from app.domain.sql.skill import SQL_AGENT_SKILL_NAME, ensure_project_skill
from app.domain.sql.system_prompt import build_system_prompt
from app.settings import get_settings

MAX_AGENT_TURNS = 30
AGENT_PACKAGE_ROOT = Path(__file__).resolve().parents[2]
REPO_ROOT = AGENT_PACKAGE_ROOT.parent
MCPSERVER_SRC = REPO_ROOT / "mcpserver" / "src"


def build_options(ob_id: str, command: str, **kwargs) -> ClaudeAgentOptions:
    """构造最小权限 SQL Agent options。"""
    settings = get_settings()
    ensure_project_skill(settings.agent_cwd)
    env = dict(os.environ)
    env["PYTHONPATH"] = _prepend_pythonpath(str(AGENT_PACKAGE_ROOT), env.get("PYTHONPATH", ""))
    if MCPSERVER_SRC.exists():
        env["PYTHONPATH"] = _prepend_pythonpath(str(MCPSERVER_SRC), env.get("PYTHONPATH", ""))
    env["SQL_AGENT_OB_ID"] = ob_id
    env["MCP_TOOL_TIMEOUT_SECONDS"] = str(settings.mcp_tool_timeout_seconds)
    extra_args = _build_extra_args(env)
    kwargs.pop("max_turns", None)
    context_type = kwargs.pop("context_type", None)
    intent = kwargs.pop("intent", None)
    return ClaudeAgentOptions(
        system_prompt=build_system_prompt(ob_id, command, context_type=context_type, intent=intent),
        mcp_servers={
            "sql_agent": {
                "type": "stdio",
                "command": sys.executable,
                "args": ["-m", "sql_agent_mcp_server.server", "--transport", "stdio"],
                "env": env,
            }
        },
        strict_mcp_config=False,
        setting_sources=["project"],
        tools=["Skill", "AskUserQuestion", "Read"],
        cwd=settings.agent_cwd,
        skills=[SQL_AGENT_SKILL_NAME],
        extra_args=extra_args,
        max_turns=MAX_AGENT_TURNS,
        **kwargs,
    )


def _prepend_pythonpath(path: str, current: str) -> str:
    """确保子进程能导入 agent app 和本地 mcpserver 包。"""
    if not current:
        return path
    parts = current.split(os.pathsep)
    if path in parts:
        return current
    return path + os.pathsep + current


def _build_extra_args(env: dict[str, str]) -> dict[str, str]:
    """把可选 Claude Code CLI debug 参数透传给 SDK。"""
    debug_file = env.get("CLAUDE_CODE_DEBUG_FILE", "").strip()
    if not debug_file:
        return {}
    return {"debug-file": debug_file}
