from __future__ import annotations

from pathlib import Path

from sql_agent_mcp_server import settings
from sql_agent_mcp_server.settings import get_settings


def test_settings_http_defaults(monkeypatch) -> None:
    monkeypatch.setattr("sql_agent_mcp_server.settings.load_project_env", lambda: None)
    monkeypatch.delenv("MCP_HTTP_HOST", raising=False)
    monkeypatch.delenv("MCP_HTTP_PORT", raising=False)
    monkeypatch.delenv("MCP_HTTP_PATH", raising=False)
    get_settings.cache_clear()

    settings = get_settings()

    assert settings.mcp_http_host == "127.0.0.1"
    assert settings.mcp_http_port == 8820
    assert settings.mcp_http_path == "/mcp"
    assert settings.mcp_log_file == "logs/sql_agent_mcp_server.log"
    assert settings.mcp_log_backup_count == 7


def test_settings_http_env_overrides(monkeypatch) -> None:
    monkeypatch.setattr("sql_agent_mcp_server.settings.load_project_env", lambda: None)
    monkeypatch.setenv("HIVE_METASTORE_DB_URI", "sqlite:///:memory:")
    monkeypatch.setenv("SQL_AGENT_DB_URI", "sqlite:///tasks.db")
    monkeypatch.setenv("MCP_HTTP_HOST", "0.0.0.0")
    monkeypatch.setenv("MCP_HTTP_PORT", "8100")
    monkeypatch.setenv("MCP_HTTP_PATH", "/sql-agent-mcp")
    get_settings.cache_clear()

    settings = get_settings()

    assert settings.mcp_http_host == "0.0.0.0"
    assert settings.mcp_http_port == 8100
    assert settings.mcp_http_path == "/sql-agent-mcp"
    assert settings.hive_metastore_db_uri == "sqlite:///:memory:"
    assert settings.sql_agent_db_uri == "sqlite:///tasks.db"
    assert settings.mcp_log_file == "logs/sql_agent_mcp_server.log"


def test_settings_loads_compact_hadoop_connection_parameters(monkeypatch) -> None:
    monkeypatch.setattr("sql_agent_mcp_server.settings.load_project_env", lambda: None)
    monkeypatch.setenv("HIVE_SERVER2_URI", "hive://hs2:10000/default")
    monkeypatch.setenv("WEBHDFS_URLS", "http://nn1:9870, http://nn2:9870/")
    monkeypatch.setenv("YARN_RESOURCE_MANAGER_URLS", "http://rm:8088")
    monkeypatch.setenv("MAPREDUCE_JOB_HISTORY_URLS", "http://jhs:19888")
    monkeypatch.setenv("HADOOP_HTTP_USER", "sql-agent")
    get_settings.cache_clear()

    resolved = get_settings()

    assert resolved.hive_server2_uri == "hive://hs2:10000/default"
    assert resolved.webhdfs_urls == ("http://nn1:9870", "http://nn2:9870")
    assert resolved.yarn_resource_manager_urls == ("http://rm:8088",)
    assert resolved.mapreduce_job_history_urls == ("http://jhs:19888",)
    assert resolved.hadoop_http_user == "sql-agent"


def test_is_project_root_only_requires_pyproject(tmp_path: Path) -> None:
    project_root = tmp_path / "demo-project"
    project_root.mkdir()
    (project_root / "pyproject.toml").write_text("[project]\nname = 'demo'\n", encoding="utf-8")

    assert settings._is_project_root(project_root) is True


def test_find_project_root_does_not_require_env_example(tmp_path: Path) -> None:
    project_root = tmp_path / "demo-project"
    nested_dir = project_root / "src" / "pkg"
    nested_dir.mkdir(parents=True)
    (project_root / "pyproject.toml").write_text("[project]\nname = 'demo'\n", encoding="utf-8")
    module_file = nested_dir / "module.py"
    module_file.write_text("pass\n", encoding="utf-8")

    assert settings._find_project_root(module_file) == project_root


def test_get_project_root_falls_back_to_cwd_when_source_markers_missing(monkeypatch, tmp_path: Path) -> None:
    monkeypatch.delenv("SQL_AGENT_MCP_PROJECT_ROOT", raising=False)
    monkeypatch.delenv("APP_HOME", raising=False)
    monkeypatch.chdir(tmp_path)
    monkeypatch.setattr(settings, "_find_project_root", lambda _start: (_ for _ in ()).throw(RuntimeError("missing")))

    assert settings.get_project_root() == tmp_path.resolve()


def test_get_project_root_prefers_explicit_env(monkeypatch, tmp_path: Path) -> None:
    explicit_root = tmp_path / "deploy-root"
    explicit_root.mkdir()
    monkeypatch.setenv("SQL_AGENT_MCP_PROJECT_ROOT", str(explicit_root))
    monkeypatch.setenv("APP_HOME", str(tmp_path / "ignored-root"))

    assert settings.get_project_root() == explicit_root.resolve()


def test_load_project_env_skips_missing_env_file(monkeypatch, tmp_path: Path) -> None:
    monkeypatch.setenv("SQL_AGENT_MCP_PROJECT_ROOT", str(tmp_path))

    settings.load_project_env()
