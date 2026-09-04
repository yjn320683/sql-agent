#!/usr/bin/env python3
"""Generate local config files with safe sample defaults."""

from __future__ import annotations

import argparse
import json
import os
import re
import secrets
import stat
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_ENV_OUTPUT = PROJECT_ROOT / "agent/.env"
DEFAULT_BACKEND_OUTPUT = PROJECT_ROOT / "backend/config/application-local.yml"

BACKEND_YAML_SOURCES: list[Path] = []
AGENT_ENV_SOURCES: list[Path] = []
MCP_ENV_SOURCES: list[Path] = []

AGENT_ENV_SECTIONS: list[tuple[str, list[tuple[str, str]]]] = [
    (
        "service",
        [
            ("AGENT_PORT", "8285"),
            ("AGENT_SERVICE_TOKEN", ""),
            ("HOST_LOG_DIR", "./logs"),
            ("TASK_EXECUTION_LOG_DIR", "./logs/task-executions"),
            ("HOST_CLAUDE_DIR", "./.claude"),
            ("APP_ENV_FILE", "agent/.env"),
        ],
    ),
    (
        "claude code",
        [
            ("AGENT_CWD", "./"),
            ("ANTHROPIC_AUTH_TOKEN", "example_anthropic_token"),
            ("ANTHROPIC_BASE_URL", "http://127.0.0.1:8000"),
            ("ANTHROPIC_MODEL", "example_model"),
            ("ANTHROPIC_DEFAULT_HAIKU_MODEL", "example_haiku_model"),
            ("ANTHROPIC_DEFAULT_SONNET_MODEL", "example_sonnet_model"),
            ("ANTHROPIC_DEFAULT_OPUS_MODEL", "example_opus_model"),
            ("CLAUDE_CODE_SUBAGENT_MODEL", "example_subagent_model"),
            ("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1"),
            ("CLAUDE_CODE_DEBUG_FILE", "./logs/claude-debug.log"),
        ],
    ),
    (
        "observability",
        [
            ("LANGSMITH_TRACING", "false"),
            ("LANGSMITH_API_KEY", "example_langsmith_api_key"),
            ("LANGSMITH_PROJECT", "sql-agent-example"),
        ],
    ),
    (
        "mcp",
        [
            ("HIVE_METASTORE_URI", "thrift://127.0.0.1:9083"),
            ("HIVE_METASTORE_DB_URI", "mysql+pymysql://example_user:example_password@127.0.0.1:3306/hive_metastore"),
            ("DATA_MAP_DB_URI", "mysql+pymysql://example_user:example_password@127.0.0.1:3306/data_map"),
            ("SQL_AGENT_DB_URI", "mysql+pymysql://example_user:example_password@127.0.0.1:3306/sql_agent"),
            ("SQL_AGENT_OB_ID", "100000"),
            ("HIVE_SERVER2_URI", "hive://127.0.0.1:10000/default?user=example_user"),
            ("WEBHDFS_URLS", "http://127.0.0.1:9870"),
            ("YARN_RESOURCE_MANAGER_URLS", "http://127.0.0.1:8088"),
            ("MAPREDUCE_JOB_HISTORY_URLS", "http://127.0.0.1:19888"),
            ("HADOOP_HTTP_USER", "example_user"),
            ("MCP_TOOL_TIMEOUT_SECONDS", "10"),
            ("MCP_LOG_LEVEL", "INFO"),
            ("MCP_LOG_FILE", "./logs/sql-agent-mcp-server.log"),
            ("MCP_LOG_BACKUP_COUNT", "7"),
            ("MCP_HTTP_HOST", "127.0.0.1"),
            ("MCP_HTTP_PORT", "8820"),
            ("MCP_HTTP_PATH", "/mcp"),
            ("SQL_AGENT_MCP_PROJECT_ROOT", "./mcpserver"),
            ("APP_HOME", "./"),
        ],
    ),
    (
        "deploy",
        [
            ("SKIP_GIT_PULL", "false"),
        ],
    ),
]

BACKEND_DEFAULTS = {
    "server.port": "8382",
    "spring.datasource.url": "jdbc:mysql://127.0.0.1:3306/sql_agent?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai",
    "spring.datasource.driver-class-name": "com.mysql.cj.jdbc.Driver",
    "spring.datasource.username": "example_user",
    "spring.datasource.password": "example_password",
    "sql-agent.agent.base-url": "http://127.0.0.1:8285",
    "sql-agent.agent.service-token": "",
    "sql-agent.agent.history-dir": "",
    "sql-agent.auth.local-login-enabled": "true",
    "data-compare.hive-jdbc-url": "jdbc:hive2://127.0.0.1:10000/default",
    "data-compare.hive-jdbc-user": "example_user",
    "data-compare.hive-temp-database": "sql_agent_verify",
    "data-compare.log-dir": "../logs/data-compare",
    "app.sql-log.enabled": "false",
    "app.sql-log.log-all": "false",
    "app.realtime.yarn-web-url": "http://127.0.0.1:8088",
    "app.realtime.submission-uri-prefix": "hdfs://127.0.0.1:8020/realtime-platform/task-submit/submissions",
    "app.realtime.paimon-warehouse": "hdfs://127.0.0.1:8020/data/warehouse/paimon",
    "app.realtime.paimon-debug-warehouse": "hdfs://127.0.0.1:8020/data/warehouse/paimon_debug",
    "app.realtime.checkpoint-dir": "hdfs://127.0.0.1:8020/flink/checkpoints/sql-agent",
    "app.realtime.savepoint-dir": "hdfs://127.0.0.1:8020/flink/savepoints/sql-agent",
    "app.realtime.catalog-uri": "thrift://127.0.0.1:9083",
}

AGENT_ENV_KEYS = {key for _, items in AGENT_ENV_SECTIONS for key, _ in items}
REFERENCE_EXCLUDED_KEYS = {
    "AGENT_CWD",
    "HOST_LOG_DIR",
    "HOST_CLAUDE_DIR",
    "CLAUDE_CODE_DEBUG_FILE",
    "MCP_LOG_FILE",
    "SQL_AGENT_MCP_PROJECT_ROOT",
    "APP_HOME",
}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--output",
        "--env-output",
        type=Path,
        default=DEFAULT_ENV_OUTPUT,
        help="agent/docker .env output path",
    )
    parser.add_argument(
        "--backend-output",
        type=Path,
        default=DEFAULT_BACKEND_OUTPUT,
        help="backend local application-local.yml output path",
    )
    parser.add_argument("--force", action="store_true", help="overwrite existing output files")
    parser.add_argument("--dry-run", action="store_true", help="print a redacted report without writing")
    args = parser.parse_args()

    env_output = args.output.resolve()
    backend_output = args.backend_output.resolve()
    assert_writable(env_output, args.force, args.dry_run)
    assert_writable(backend_output, args.force, args.dry_run)

    env_values, env_sources = collect_agent_env_values()
    backend_values, backend_sources = collect_backend_values()
    service_token = env_values.get("AGENT_SERVICE_TOKEN") or secrets.token_hex(32)
    env_values["AGENT_SERVICE_TOKEN"] = service_token
    backend_values["sql-agent.agent.service-token"] = service_token
    host_claude_dir = Path(env_values.get("HOST_CLAUDE_DIR") or "./.claude")
    if not host_claude_dir.is_absolute():
        host_claude_dir = (PROJECT_ROOT / host_claude_dir).resolve()
    backend_values["sql-agent.agent.history-dir"] = str(host_claude_dir / "projects" / "-app")

    if not args.dry_run:
        write_private_file(env_output, render_agent_env(env_values))
        write_private_file(backend_output, render_backend_config(backend_values))

    print_report("agent env", env_output, env_values, env_sources, dry_run=args.dry_run)
    print_report("backend config", backend_output, backend_values, backend_sources, dry_run=args.dry_run)
    return 0


def assert_writable(path: Path, force: bool, dry_run: bool) -> None:
    if path.exists() and not force and not dry_run:
        raise SystemExit(f"{path} already exists; pass --force to overwrite it")


def write_private_file(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    os.chmod(path, stat.S_IRUSR | stat.S_IWUSR)


def collect_agent_env_values() -> tuple[dict[str, str], dict[str, Path]]:
    values = {key: default for _, items in AGENT_ENV_SECTIONS for key, default in items}
    source_by_key: dict[str, Path] = {}
    load_env_sources(AGENT_ENV_SOURCES, values, source_by_key)
    load_env_sources(MCP_ENV_SOURCES, values, source_by_key)
    return values, source_by_key


def collect_backend_values() -> tuple[dict[str, str], dict[str, Path]]:
    values = dict(BACKEND_DEFAULTS)
    source_by_key: dict[str, Path] = {}
    mapping = {
        "url": "spring.datasource.url",
        "username": "spring.datasource.username",
        "password": "spring.datasource.password",
        "driver-class-name": "spring.datasource.driver-class-name",
    }
    for source in BACKEND_YAML_SOURCES:
        datasource = parse_spring_datasource(source)
        for yaml_key, config_key in mapping.items():
            value = datasource.get(yaml_key, "")
            if value and not source_by_key.get(config_key):
                values[config_key] = value
                source_by_key[config_key] = source
    return values, source_by_key


def load_env_sources(
    sources: list[Path], values: dict[str, str], source_by_key: dict[str, Path]
) -> None:
    for source in sources:
        env_values = parse_env_file(source)
        for key, value in env_values.items():
            if key in REFERENCE_EXCLUDED_KEYS:
                continue
            if key in AGENT_ENV_KEYS and value and not source_by_key.get(key):
                values[key] = value
                source_by_key[key] = source


def parse_env_file(path: Path) -> dict[str, str]:
    if not path.exists():
        return {}
    result: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key):
            continue
        result[key] = strip_quotes(value.strip())
    return result


def parse_spring_datasource(path: Path) -> dict[str, str]:
    if not path.exists():
        return {}
    result: dict[str, str] = {}
    stack: list[tuple[int, str]] = []
    pattern = re.compile(r"^(?P<indent>\s*)(?P<key>[-\w.]+):(?:\s*(?P<value>.*))?$")
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        stripped = raw_line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        match = pattern.match(raw_line)
        if not match:
            continue
        indent = len(match.group("indent").replace("\t", "    "))
        key = match.group("key")
        value = match.group("value")
        while stack and indent <= stack[-1][0]:
            stack.pop()
        current_path = tuple(item_key for _, item_key in stack) + (key,)
        if current_path[:2] == ("spring", "datasource") and len(current_path) == 3:
            if value is not None:
                result[key] = strip_quotes(value.strip())
        if value is None or value == "":
            stack.append((indent, key))
    return result


def strip_quotes(value: str) -> str:
    if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
        return value[1:-1]
    return value


def render_agent_env(values: dict[str, str]) -> str:
    lines: list[str] = [
        "# Generated by scripts/generate_env_from_references.py",
        "# Local only. Do not commit this file.",
        "",
    ]
    for section, items in AGENT_ENV_SECTIONS:
        lines.append(f"# {section}")
        for key, _ in items:
            lines.append(f"{key}={quote_env_value(values.get(key, ''))}")
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def render_backend_config(values: dict[str, str]) -> str:
    return "\n".join(
        [
            "# Generated by scripts/generate_env_from_references.py",
            "# Local only. Do not commit this file.",
            "server:",
            f"  port: {values['server.port']}",
            "",
            "spring:",
            "  datasource:",
            f"    url: {quote_yaml_value(values['spring.datasource.url'])}",
            f"    driver-class-name: {quote_yaml_value(values['spring.datasource.driver-class-name'])}",
            f"    username: {quote_yaml_value(values['spring.datasource.username'])}",
            f"    password: {quote_yaml_value(values['spring.datasource.password'])}",
            "",
            "sql-agent:",
            "  auth:",
            f"    local-login-enabled: {values['sql-agent.auth.local-login-enabled']}",
            "  agent:",
            f"    base-url: {quote_yaml_value(values['sql-agent.agent.base-url'])}",
            f"    service-token: {quote_yaml_value(values['sql-agent.agent.service-token'])}",
            f"    history-dir: {quote_yaml_value(values['sql-agent.agent.history-dir'])}",
            "",
            "data-compare:",
            f"  hive-jdbc-url: {quote_yaml_value(values['data-compare.hive-jdbc-url'])}",
            f"  hive-jdbc-user: {quote_yaml_value(values['data-compare.hive-jdbc-user'])}",
            f"  hive-temp-database: {quote_yaml_value(values['data-compare.hive-temp-database'])}",
            f"  log-dir: {quote_yaml_value(values['data-compare.log-dir'])}",
            "",
            "app:",
            "  realtime:",
            "    flink-bin: ${FLINK_BIN:flink}",
            "    hadoop-bin: ${HADOOP_BIN:hadoop}",
            "    yarn-bin: ${YARN_BIN:yarn}",
            f"    yarn-web-url: {quote_yaml_value(values['app.realtime.yarn-web-url'])}",
            "    submit-jar: ${REALTIME_SUBMIT_JAR:../realtime-task-submit/target/realtime-task-submit.jar}",
            "    submission-dir: ${REALTIME_SUBMISSION_DIR:/tmp/sql-agent/realtime/submissions}",
            f"    submission-uri-prefix: {quote_yaml_value(values['app.realtime.submission-uri-prefix'])}",
            "    paimon-action-jar-path: ${PAIMON_ACTION_JAR_PATH:./lib/paimon-flink-action-1.4.2.jar}",
            f"    paimon-warehouse: {quote_yaml_value(values['app.realtime.paimon-warehouse'])}",
            f"    paimon-debug-warehouse: {quote_yaml_value(values['app.realtime.paimon-debug-warehouse'])}",
            "    paimon-debug-target-database: paimon_debug",
            "    target-database: ods_real",
            f"    checkpoint-dir: {quote_yaml_value(values['app.realtime.checkpoint-dir'])}",
            f"    savepoint-dir: {quote_yaml_value(values['app.realtime.savepoint-dir'])}",
            "    catalog-conf:",
            "      metastore: hive",
            f"      uri: {quote_yaml_value(values['app.realtime.catalog-uri'])}",
            "      hive.metastore.uri.selection: SEQUENTIAL",
            "    mysql-default-conf:",
            "      server-time-zone: Asia/Shanghai",
            "  task-execution-log:",
            "    dir: ./logs/task-executions",
            "  sql-log:",
            f"    enabled: {values['app.sql-log.enabled']}",
            f"    log-all: {values['app.sql-log.log-all']}",
            "",
        ]
    )


def quote_env_value(value: str) -> str:
    if value == "":
        return ""
    if re.search(r"\s|#|'|\"", value):
        escaped = value.replace("\\", "\\\\").replace('"', '\\"')
        return f'"{escaped}"'
    return value


def quote_yaml_value(value: str) -> str:
    return json.dumps(value, ensure_ascii=False)


def print_report(
    label: str,
    output: Path,
    values: dict[str, str],
    source_by_key: dict[str, Path],
    dry_run: bool,
) -> None:
    action = "would write" if dry_run else "wrote"
    populated = sorted(key for key, value in values.items() if value)
    copied = sorted(source_by_key)
    print(f"{label}: {action} {output}")
    print(f"{label}: populated keys: {len(populated)}")
    for key in populated:
        marker = "copied" if key in source_by_key else "default"
        print(f"- {key}: {marker}")
    if copied:
        print(f"{label}: reference sources:")
        for source in sorted({source_by_key[key] for key in copied}):
            print(f"- {source}")


if __name__ == "__main__":
    raise SystemExit(main())
