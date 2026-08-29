"""任务执行实例与Step的业务库访问。"""

from __future__ import annotations

import json
from typing import Any

from sqlalchemy import create_engine, text
from sqlalchemy.engine import Engine


class TaskExecutionRepository:
    def __init__(self, db_uri: str, timeout_seconds: int = 10) -> None:
        if not db_uri:
            raise RuntimeError("SQL_AGENT_DB_URI is not configured")
        connect_args = {
            "connect_timeout": timeout_seconds,
            "read_timeout": timeout_seconds,
            "write_timeout": timeout_seconds,
        } if db_uri.startswith("mysql") else {}
        self.engine: Engine = create_engine(db_uri, pool_pre_ping=True, connect_args=connect_args)

    def get(self, execution_id: int) -> dict[str, Any] | None:
        with self.engine.connect() as conn:
            row = conn.execute(text("""
                SELECT id, task_id, task_name_snapshot, sql_snapshot, parameter_schema_snapshot,
                       rendered_sql_snapshot, parameter_values, business_date, source_type,
                       task_version_no, task_revision, status, requested_by, executor_instance_id,
                       query_id, application_ids, job_ids, error_message, total_steps,
                       succeeded_steps, current_step_no, failed_step_no
                FROM sql_task_execution WHERE id = :id
            """), {"id": execution_id}).mappings().first()
            return dict(row) if row else None

    def claim(self, execution_id: int, instance_id: str) -> bool:
        with self.engine.begin() as conn:
            result = conn.execute(text("""
                UPDATE sql_task_execution
                SET status = 'QUEUED', executor_instance_id = :instance_id,
                    heartbeat_at = NOW(), error_message = NULL
                WHERE id = :id AND status = 'PENDING'
            """), {"id": execution_id, "instance_id": instance_id})
            return result.rowcount == 1

    def start_running(self, execution_id: int, instance_id: str) -> bool:
        with self.engine.begin() as conn:
            result = conn.execute(text("""
                UPDATE sql_task_execution
                SET status = 'RUNNING', started_at = NOW(), heartbeat_at = NOW()
                WHERE id = :id AND status = 'QUEUED' AND executor_instance_id = :instance_id
            """), {"id": execution_id, "instance_id": instance_id})
            return result.rowcount == 1

    def prepare_steps(self, execution_id: int, rendered_sql: str, steps: list[dict[str, Any]]) -> None:
        with self.engine.begin() as conn:
            conn.execute(text("DELETE FROM sql_task_execution_step WHERE execution_id = :id"), {"id": execution_id})
            if steps:
                conn.execute(text("""
                    INSERT INTO sql_task_execution_step(
                        execution_id, task_id, step_no, step_order, step_name,
                        source_sql_snapshot, rendered_sql_snapshot, status, log_file
                    ) VALUES(
                        :execution_id, :task_id, :step_no, :step_order, :step_name,
                        :source_sql, :rendered_sql, 'PENDING', :log_file
                    )
                """), steps)
            conn.execute(text("""
                UPDATE sql_task_execution
                SET rendered_sql_snapshot = :rendered_sql, total_steps = :total,
                    succeeded_steps = 0, current_step_no = NULL, failed_step_no = NULL
                WHERE id = :id
            """), {"id": execution_id, "rendered_sql": rendered_sql, "total": len(steps)})

    def start_step(self, execution_id: int, step_no: int) -> None:
        with self.engine.begin() as conn:
            conn.execute(text("""
                UPDATE sql_task_execution_step
                SET status = 'RUNNING', started_at = NOW(), error_message = NULL
                WHERE execution_id = :id AND step_no = :step_no AND status = 'PENDING'
            """), {"id": execution_id, "step_no": step_no})
            conn.execute(text("""
                UPDATE sql_task_execution SET current_step_no = :step_no, heartbeat_at = NOW()
                WHERE id = :id
            """), {"id": execution_id, "step_no": step_no})

    def finish_step(
        self,
        execution_id: int,
        step_no: int,
        status: str,
        *,
        query_id: str | None = None,
        application_ids: list[str] | None = None,
        job_ids: list[str] | None = None,
        error_message: str | None = None,
    ) -> None:
        with self.engine.begin() as conn:
            conn.execute(text("""
                UPDATE sql_task_execution_step
                SET status = :status, query_id = :query_id, application_ids = :application_ids,
                    job_ids = :job_ids, error_message = :error_message, finished_at = NOW()
                WHERE execution_id = :id AND step_no = :step_no
            """), {
                "id": execution_id, "step_no": step_no, "status": status, "query_id": query_id,
                "application_ids": json.dumps(application_ids or []), "job_ids": json.dumps(job_ids or []),
                "error_message": error_message,
            })
            if status == "SUCCEEDED":
                conn.execute(text("""
                    UPDATE sql_task_execution SET succeeded_steps = succeeded_steps + 1
                    WHERE id = :id
                """), {"id": execution_id})
            elif status == "FAILED":
                conn.execute(text("""
                    UPDATE sql_task_execution SET failed_step_no = :step_no WHERE id = :id
                """), {"id": execution_id, "step_no": step_no})

    def skip_remaining(self, execution_id: int, after_order: int, status: str = "SKIPPED") -> None:
        with self.engine.begin() as conn:
            conn.execute(text("""
                UPDATE sql_task_execution_step SET status = :status, finished_at = NOW()
                WHERE execution_id = :id AND step_order > :step_order AND status = 'PENDING'
            """), {"id": execution_id, "step_order": after_order, "status": status})

    def heartbeat(self, execution_id: int, instance_id: str) -> None:
        with self.engine.begin() as conn:
            conn.execute(text("""
                UPDATE sql_task_execution SET heartbeat_at = NOW()
                WHERE id = :id AND executor_instance_id = :instance_id
                  AND status IN ('RUNNING', 'CANCELLING')
            """), {"id": execution_id, "instance_id": instance_id})

    def request_cancel(self, execution_id: int) -> str | None:
        with self.engine.begin() as conn:
            row = conn.execute(text(
                "SELECT status FROM sql_task_execution WHERE id = :id FOR UPDATE"
            ), {"id": execution_id}).mappings().first()
            if not row:
                return None
            status = str(row["status"])
            if status == "QUEUED":
                conn.execute(text("""
                    UPDATE sql_task_execution SET status = 'CANCELLED', finished_at = NOW()
                    WHERE id = :id
                """), {"id": execution_id})
                conn.execute(text("""
                    UPDATE sql_task_execution_step SET status = 'CANCELLED', finished_at = NOW()
                    WHERE execution_id = :id AND status = 'PENDING'
                """), {"id": execution_id})
                return "CANCELLED"
            if status == "RUNNING":
                conn.execute(text(
                    "UPDATE sql_task_execution SET status = 'CANCELLING' WHERE id = :id"
                ), {"id": execution_id})
                conn.execute(text("""
                    UPDATE sql_task_execution_step SET status = 'CANCELLING'
                    WHERE execution_id = :id AND status = 'RUNNING'
                """), {"id": execution_id})
                return "CANCELLING"
            return status

    def finish(
        self,
        execution_id: int,
        status: str,
        *,
        query_id: str | None = None,
        application_ids: list[str] | None = None,
        job_ids: list[str] | None = None,
        error_message: str | None = None,
    ) -> None:
        with self.engine.begin() as conn:
            conn.execute(text("""
                UPDATE sql_task_execution
                SET status = :status, query_id = :query_id, application_ids = :application_ids,
                    job_ids = :job_ids, error_message = :error_message, finished_at = NOW(),
                    heartbeat_at = NOW(), current_step_no = NULL
                WHERE id = :id
            """), {
                "id": execution_id, "status": status, "query_id": query_id,
                "application_ids": json.dumps(application_ids or []), "job_ids": json.dumps(job_ids or []),
                "error_message": error_message,
            })

    def fail_orphaned(self, current_instance_id: str) -> int:
        with self.engine.begin() as conn:
            conn.execute(text("""
                UPDATE sql_task_execution_step s
                JOIN sql_task_execution e ON e.id = s.execution_id
                SET s.status = 'FAILED', s.finished_at = NOW(),
                    s.error_message = 'Agent执行进程已重启，原Step无法恢复'
                WHERE e.status IN ('RUNNING', 'CANCELLING')
                  AND (e.executor_instance_id IS NULL OR e.executor_instance_id != :instance_id)
                  AND s.status IN ('RUNNING', 'CANCELLING', 'PENDING')
            """), {"instance_id": current_instance_id})
            result = conn.execute(text("""
                UPDATE sql_task_execution SET status = 'FAILED', finished_at = NOW(),
                    error_message = 'Agent执行进程已重启，原执行无法恢复'
                WHERE status IN ('RUNNING', 'CANCELLING')
                  AND (executor_instance_id IS NULL OR executor_instance_id != :instance_id)
            """), {"instance_id": current_instance_id})
            return int(result.rowcount)

    def queued(self) -> list[int]:
        with self.engine.connect() as conn:
            rows = conn.execute(text(
                "SELECT id FROM sql_task_execution WHERE status = 'QUEUED' ORDER BY id"
            )).scalars().all()
            return [int(item) for item in rows]

    def adopt_queued(self, instance_id: str) -> None:
        with self.engine.begin() as conn:
            conn.execute(text("""
                UPDATE sql_task_execution SET executor_instance_id = :instance_id, heartbeat_at = NOW()
                WHERE status = 'QUEUED'
            """), {"instance_id": instance_id})
