from pathlib import Path
from types import SimpleNamespace

import pytest
from TCLIService import ttypes

from app.execution.logs import ExecutionLogWriter, read_log_tail
from app.execution.manager import TaskExecutionManager
from app.execution.sql_policy import validate_executable_sql
from app.execution.sql_script import parse_and_render_script


@pytest.mark.parametrize("sql", [
    "select 1",
    "with source as (select 1 as id) select id from source",
    "insert overwrite table target select * from source",
])
def test_execution_policy_allows_query_and_insert(sql: str) -> None:
    assert validate_executable_sql(sql)


@pytest.mark.parametrize("sql", [
    "drop table target",
    "create table target(id bigint)",
    "update target set id = 1",
    "select 1; select 2",
])
def test_execution_policy_rejects_ddl_mutation_and_multiple_statements(sql: str) -> None:
    with pytest.raises(ValueError):
        validate_executable_sql(sql)


def test_execution_log_redacts_credentials(tmp_path: Path) -> None:
    writer = ExecutionLogWriter(str(tmp_path), 12)

    writer.append("password=hello token:abc mysql://user:pass@db:3306/sql_agent")

    content = read_log_tail(str(tmp_path), 12)
    assert "hello" not in content
    assert "token=<redacted>" in content
    assert "mysql://<redacted>@" in content


def test_multi_step_script_renders_typed_and_legacy_date_parameters() -> None:
    script = """====step:0:prepare====
select ${limit_value} as amount, ${enabled} as enabled, '${yyyy-MM-dd,-1,day}' as dt
====step:2:write====
insert overwrite table result select ${name} as name"""
    schema = """[
      {"name":"limit_value","type":"INTEGER","required":true},
      {"name":"enabled","type":"BOOLEAN","required":true},
      {"name":"name","type":"STRING","required":true}
    ]"""

    rendered, steps, values = parse_and_render_script(
        script, schema, '{"limit_value":12,"enabled":true,"name":"O\'Reilly"}', "2026-08-25"
    )

    assert [step.number for step in steps] == [0, 2]
    assert "12 as amount" in rendered
    assert "TRUE as enabled" in rendered
    assert "2026-08-24" in rendered
    assert "'O''Reilly'" in rendered
    assert values["limit_value"] == 12


def test_script_rejects_unknown_parameter_and_non_increasing_steps() -> None:
    with pytest.raises(ValueError, match="未声明"):
        parse_and_render_script("select ${missing}", "[]", "{}")
    with pytest.raises(ValueError, match="递增"):
        parse_and_render_script(
            "====step:2====\nselect 1\n====step:1====\nselect 2", "[]", "{}"
        )


@pytest.mark.parametrize(
    ("current_status", "expected_status"),
    [("RUNNING", "FAILED"), ("CANCELLING", "CANCELLED")],
)
def test_hive_poll_error_keeps_runtime_ids_and_cancel_state(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    current_status: str,
    expected_status: str,
) -> None:
    repository = _FakeRepository(current_status)
    cursor = _FailingCursor()
    connection = _FakeConnection(cursor)
    monkeypatch.setattr(
        "app.execution.manager.HiveServer2Adapter",
        lambda *_args, **_kwargs: SimpleNamespace(
            _connection_info=lambda _database: {
                "host": "hive", "port": 10000, "username": None, "auth": "NONE", "database": "default",
            }
        ),
    )
    monkeypatch.setattr("app.execution.manager._build_transport", lambda *_args: object())
    monkeypatch.setattr("app.execution.manager.hive.connect", lambda **_kwargs: connection)
    monkeypatch.setattr("app.execution.manager.time.sleep", lambda _seconds: None)
    manager = TaskExecutionManager(repository, "hive://hive:10000/default", str(tmp_path), 5)

    manager._execute_sync(12)

    assert repository.finished["status"] == expected_status
    if expected_status == "FAILED":
        assert repository.finished["query_id"] == "query_20260821_1"
        assert repository.finished["application_ids"] == ["application_1_2"]
        assert repository.finished["job_ids"] == ["job_1_2"]
    else:
        assert repository.finished["query_id"] is None
        assert repository.finished["application_ids"] == []
        assert repository.finished["job_ids"] == []
    assert (repository.finished.get("error_message") is None) == (expected_status == "CANCELLED")


def test_hive_connection_retries_only_before_sql_submission(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    repository = _FakeRepository("RUNNING")
    connection = _FakeConnection(_SuccessfulCursor())
    attempts = 0

    monkeypatch.setattr(
        "app.execution.manager.HiveServer2Adapter",
        lambda *_args, **_kwargs: SimpleNamespace(
            _connection_info=lambda _database: {
                "host": "hive", "port": 10000, "username": "root", "auth": "NONE", "database": "default",
            }
        ),
    )
    monkeypatch.setattr("app.execution.manager._build_transport", lambda *_args: object())

    def connect(**_kwargs):
        nonlocal attempts
        attempts += 1
        if attempts < 3:
            raise ConnectionError("temporary unavailable")
        return connection

    monkeypatch.setattr("app.execution.manager.hive.connect", connect)
    monkeypatch.setattr("app.execution.manager.time.sleep", lambda _seconds: None)
    manager = TaskExecutionManager(repository, "hive://hive:10000/default?user=root", str(tmp_path), 5)

    manager._execute_sync(12)

    assert attempts == 3
    assert repository.finished["status"] == "SUCCEEDED"


class _FakeRepository:
    def __init__(self, status: str) -> None:
        self.status = status
        self.finished: dict = {}

    def get(self, _execution_id: int) -> dict:
        return {
            "task_id": 9,
            "sql_snapshot": "select 1",
            "status": self.status,
        }

    def heartbeat(self, _execution_id: int, _instance_id: str) -> None:
        return None

    def prepare_steps(self, _execution_id: int, _rendered_sql: str, steps: list[dict]) -> None:
        self.steps = steps

    def start_step(self, _execution_id: int, _step_no: int) -> None:
        return None

    def finish_step(self, _execution_id: int, _step_no: int, _status: str, **_kwargs) -> None:
        return None

    def skip_remaining(self, _execution_id: int, _after_order: int, _status: str = "SKIPPED") -> None:
        return None

    def finish(self, _execution_id: int, status: str, **kwargs) -> None:
        self.finished = {"status": status, **kwargs}


class _FailingCursor:
    def __init__(self) -> None:
        self.poll_count = 0

    def execute(self, _sql: str, async_: bool) -> None:
        assert async_ is True

    def poll(self):
        self.poll_count += 1
        if self.poll_count > 1:
            raise RuntimeError("poll failed")
        return SimpleNamespace(operationState=ttypes.TOperationState.RUNNING_STATE)

    def fetch_logs(self) -> list[str]:
        return ["Query ID = query_20260821_1 application_1_2 job_1_2"]

    def close(self) -> None:
        return None


class _SuccessfulCursor:
    def execute(self, _sql: str, async_: bool) -> None:
        assert async_ is True

    def poll(self):
        return SimpleNamespace(operationState=ttypes.TOperationState.FINISHED_STATE)

    def fetch_logs(self) -> list[str]:
        return []

    def close(self) -> None:
        return None


class _FakeConnection:
    def __init__(self, cursor: _FailingCursor) -> None:
        self._cursor = cursor

    def cursor(self) -> _FailingCursor:
        return self._cursor

    def close(self) -> None:
        return None
