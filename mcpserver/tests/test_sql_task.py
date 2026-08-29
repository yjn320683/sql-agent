from pathlib import Path

import pytest
from sqlalchemy import create_engine, text

from sql_agent_mcp_server.common.errors import McpDomainError, McpErrorCode
from sql_agent_mcp_server.domains.sql_task.adapter import SqlTaskAdapter
from sql_agent_mcp_server.domains.sql_task.service import SqlTaskService


def test_sql_task_tools_read_task_and_bounded_execution_log(tmp_path: Path) -> None:
    db_path = tmp_path / "tasks.db"
    uri = f"sqlite:///{db_path}"
    engine = create_engine(uri)
    with engine.begin() as conn:
        conn.execute(text("""
            CREATE TABLE sql_task (
              id INTEGER PRIMARY KEY, name TEXT, description TEXT, task_type TEXT,
                  execution_frequency TEXT, owner TEXT, enabled INTEGER, sql_content TEXT, ddl_content TEXT,
                  parameter_schema TEXT, sql_checksum TEXT, revision INTEGER,
                  effective_version_no INTEGER,
                  archived INTEGER, created_by TEXT, updated_by TEXT,
              create_time TEXT, update_time TEXT
            )
        """))
        conn.execute(text("""
            CREATE TABLE sql_task_version (
              task_id INTEGER, version_no INTEGER, status TEXT, name TEXT, description TEXT,
                  sql_content TEXT, ddl_content TEXT, parameter_schema TEXT, sql_checksum TEXT, revision INTEGER,
              base_effective_version_no INTEGER, base_effective_checksum TEXT, version_note TEXT,
              PRIMARY KEY (task_id, version_no)
            )
        """))
        conn.execute(text("""
            CREATE TABLE sql_task_version_step (
              task_id INTEGER, version_no INTEGER, step_no INTEGER, step_order INTEGER,
              step_name TEXT, step_sql TEXT, statement_type TEXT, input_tables TEXT,
              output_tables TEXT, sql_checksum TEXT
            )
        """))
        conn.execute(text("""
            CREATE TABLE sql_task_execution (
              id INTEGER PRIMARY KEY, task_id INTEGER, task_name_snapshot TEXT, status TEXT,
              source_type TEXT, task_version_no INTEGER, task_revision INTEGER,
              current_step_no INTEGER, total_steps INTEGER, succeeded_steps INTEGER, failed_step_no INTEGER,
              requested_by TEXT, query_id TEXT, application_ids TEXT, job_ids TEXT,
              error_message TEXT, submitted_at TEXT, started_at TEXT, finished_at TEXT
            )
        """))
        conn.execute(text("""
            CREATE TABLE sql_task_execution_step (
              execution_id INTEGER, step_no INTEGER, step_order INTEGER, step_name TEXT, status TEXT,
              query_id TEXT, application_ids TEXT, job_ids TEXT, error_message TEXT, log_file TEXT,
              started_at TEXT, finished_at TEXT
            )
        """))
        conn.execute(text("""
            INSERT INTO sql_task
              (id, name, description, task_type, execution_frequency, owner, enabled,
                       sql_content, ddl_content, parameter_schema, sql_checksum, revision, archived,
                   effective_version_no, created_by, updated_by, create_time, update_time)
                VALUES
                  (42, 'orders', 'daily orders', 'RUN_HIVE', '每天 07:00', '1', 1,
                       'select count(*) from effective_orders', 'ALTER TABLE orders ADD COLUMNS (source STRING)', '[]', 'effective42', 2, 0, 2, '1', '1', 'now', 'now'),
                  (43, 'summary', 'daily summary', 'RUN_HIVE', '手动执行', '2', 0,
                       'insert into summary select * from orders', NULL, '[]', 'sum43', 1, 0, NULL, '2', '2', 'now', 'now')
        """))
        conn.execute(text("""
            INSERT INTO sql_task_version
                  (task_id, version_no, status, name, description, sql_content, ddl_content, parameter_schema,
               sql_checksum, revision, base_effective_version_no, base_effective_checksum, version_note)
            VALUES
                  (42, 1, 'DRAFT', 'orders v1', 'draft version', 'select * from draft_orders', 'ALTER TABLE orders ADD COLUMNS (source STRING)',
               '[{"name":"dt","type":"DATE"}]', 'draft1', 3, 7, 'effective7', 'working'),
                  (42, 2, 'EFFECTIVE', 'orders v2', 'effective version', 'select * from effective_orders', NULL,
               '[]', 'effective2', 5, 1, 'draft1', 'activated'),
                  (42, 3, 'HISTORICAL', 'orders v3', 'historical version', 'select * from old_orders', NULL,
               '[]', 'history3', 2, 2, 'effective2', 'previous'),
                  (42, 4, 'STALE', 'orders v4', 'stale version', 'select * from stale_orders', NULL,
               '[]', 'stale4', 4, 2, 'effective2', 'outdated base')
        """))
        conn.execute(text("""
            INSERT INTO sql_task_version_step
              (task_id, version_no, step_no, step_order, step_name, step_sql, statement_type,
               input_tables, output_tables, sql_checksum)
            VALUES
              (42, 1, 0, 0, 'load orders', 'select * from draft_orders', 'SELECT',
               '["draft_orders"]', '[]', 'step1')
        """))
        conn.execute(text("""
            INSERT INTO sql_task_execution
              (id, task_id, task_name_snapshot, status, source_type, task_version_no, task_revision,
               current_step_no, total_steps, succeeded_steps, failed_step_no, requested_by, query_id,
               application_ids, job_ids, error_message, submitted_at, started_at, finished_at)
            VALUES
                  (99, 42, 'orders', 'FAILED', 'EFFECTIVE', 2, 2, 0, 1, 0, 0, '1', 'query_1',
               '["application_1_1"]', '["job_1_1"]', 'failed', 'now', 'now', 'now')
        """))
    log_dir = tmp_path / "logs"
    log_dir.mkdir()
    (log_dir / "99.log").write_text("safe log", encoding="utf-8")
    service = SqlTaskService(SqlTaskAdapter(uri, timeout_seconds=5, log_dir=log_dir))

    task = service.get_task(42)
    assert task["task"]["ddl"] == "ALTER TABLE orders ADD COLUMNS (source STRING)"
    versions = [service.get_task(42, version_no)["task"] for version_no in range(1, 5)]
    tasks = service.list_tasks(10)
    execution = service.get_execution(99)
    executions = service.list_executions(42, 10)

    assert task["task"]["sql"] == "select count(*) from effective_orders"
    assert task["task"]["content_source"] == "EFFECTIVE_TASK"
    assert task["task"]["status"] == "EFFECTIVE"
    assert task["task"]["execution_frequency"] == "每天 07:00"
    assert task["task"]["enabled"] == 1
    assert [version["status"] for version in versions] == ["DRAFT", "EFFECTIVE", "HISTORICAL", "STALE"]
    assert versions[0]["sql"] == "select * from draft_orders"
    assert versions[0]["parameters"] == [{"name": "dt", "type": "DATE"}]
    assert versions[0]["revision"] == 3
    assert versions[0]["base_effective_version_no"] == 7
    assert versions[0]["base_effective_checksum"] == "effective7"
    assert versions[0]["steps"][0]["sql"] == "select * from draft_orders"
    assert versions[2]["content_source"] == "SAVED_VERSION"
    assert versions[3]["sql"] == "select * from stale_orders"
    assert tasks["total"] == 2
    assert [item["id"] for item in tasks["items"]] == [42, 43]
    assert tasks["items"][1]["sql"].startswith("insert into summary")
    assert execution["execution"]["logTail"] == "safe log"
    assert execution["execution"]["application_ids"] == ["application_1_1"]
    assert executions["items"][0]["id"] == 99
    assert "sql_snapshot" not in executions["items"][0]


def test_sql_task_version_read_rejects_invalid_or_missing_version(tmp_path: Path) -> None:
    db_path = tmp_path / "tasks.db"
    uri = f"sqlite:///{db_path}"
    engine = create_engine(uri)
    with engine.begin() as conn:
        conn.execute(text("""
            CREATE TABLE sql_task_version (
              task_id INTEGER, version_no INTEGER, status TEXT, name TEXT, description TEXT,
                  sql_content TEXT, ddl_content TEXT, parameter_schema TEXT, sql_checksum TEXT, revision INTEGER,
              base_effective_version_no INTEGER, base_effective_checksum TEXT, version_note TEXT
            )
        """))
    service = SqlTaskService(SqlTaskAdapter(uri, timeout_seconds=5, log_dir=tmp_path))

    with pytest.raises(McpDomainError) as invalid:
        service.get_task(42, 0)
    assert invalid.value.code == McpErrorCode.INVALID_REQUEST

    with pytest.raises(McpDomainError) as missing:
        service.get_task(42, 99)
    assert missing.value.code == McpErrorCode.NOT_FOUND
    assert missing.value.details == {"taskId": 42, "versionNo": 99}
