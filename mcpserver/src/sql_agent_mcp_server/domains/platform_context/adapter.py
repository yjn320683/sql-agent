"""从业务库读取脱敏后的页面上下文。"""

from __future__ import annotations

import json
import re
from typing import Any
from urllib.parse import urlparse

from sqlalchemy import create_engine, text
from sqlalchemy.engine import Engine

from sql_agent_mcp_server.common.errors import McpDomainError, McpErrorCode
from sql_agent_mcp_server.common.http import ReadonlyJsonHttpClient


ENTITY_QUERIES: dict[str, tuple[str, str]] = {
    "OFFLINE_SCHEDULE": (
        "SELECT task_id,schedule_type,cron_expression,timezone,enabled,concurrency_policy,"
        "max_retries,retry_interval_seconds,next_trigger_time,last_trigger_time,last_run_status,revision "
        "FROM sql_task_schedule WHERE task_id=:id",
        "SELECT task_id,schedule_type,cron_expression,timezone,enabled,concurrency_policy,"
        "max_retries,retry_interval_seconds,last_run_status,revision FROM sql_task_schedule "
        "ORDER BY update_time DESC LIMIT 20",
    ),
    "DATA_COMPARE": (
        "SELECT id,compare_type,task_id,baseline_version_no,candidate_version_no,union_id,baseline_table,"
        "candidate_table,status,error_message,operator_ob_id,started_at,finished_at,create_time,update_time "
        "FROM data_compare_job_detail WHERE id=:id",
        "SELECT id,compare_type,task_id,baseline_version_no,candidate_version_no,union_id,baseline_table,"
        "candidate_table,status,error_message,operator_ob_id,started_at,finished_at,create_time,update_time "
        "FROM data_compare_job_detail ORDER BY id DESC LIMIT 20",
    ),
    "REALTIME_SYNC_TASK": (
        "SELECT t.id,t.task_name,t.task_type,t.flink_version,t.owner,t.description,t.status,t.update_time,"
        "c.source_type,c.source_server_id,c.target_database FROM rt_task t "
        "LEFT JOIN rt_sync_task_config c ON c.task_id=t.id WHERE t.id=:id",
        "SELECT id,task_name,task_type,flink_version,owner,description,status,update_time FROM rt_task "
        "WHERE task_type='sync' ORDER BY update_time DESC LIMIT 20",
    ),
    "REALTIME_COMPUTE_TASK": (
        "SELECT t.id,t.task_name,t.task_type,t.flink_version,t.owner,t.description,t.status,t.update_time,"
        "c.default_database,c.sql_text FROM rt_task t LEFT JOIN rt_compute_task_config c ON c.task_id=t.id "
        "WHERE t.id=:id",
        "SELECT id,task_name,task_type,flink_version,owner,description,status,update_time FROM rt_task "
        "WHERE task_type='compute' ORDER BY update_time DESC LIMIT 20",
    ),
    "REALTIME_EXPORT_TASK": (
        "SELECT t.id,t.task_name,t.task_type,t.flink_version,t.owner,t.description,t.status,t.update_time,"
        "c.source_database,c.target_server_id FROM rt_task t LEFT JOIN rt_export_task_config c ON c.task_id=t.id "
        "WHERE t.id=:id",
        "SELECT id,task_name,task_type,flink_version,owner,description,status,update_time FROM rt_task "
        "WHERE task_type='export' ORDER BY update_time DESC LIMIT 20",
    ),
    "REALTIME_INSTANCE": (
        "SELECT id,task_id,version_id,job_id,yarn_application_id,status,execution_mode,managed_flag,"
        "savepoint_path,tracking_url,failure_message,started_at,ended_at,update_time "
        "FROM rt_task_instance WHERE id=:id",
        "SELECT id,task_id,version_id,job_id,yarn_application_id,status,execution_mode,managed_flag,"
        "failure_message,started_at,ended_at,update_time FROM rt_task_instance ORDER BY id DESC LIMIT 20",
    ),
    "REALTIME_TABLE": (
        "SELECT id,catalog_name,database_name,table_name,table_comment,table_type,creation_source,"
        "producer_task_id,physical_status,table_options_json,last_error,last_synced_at,update_time "
        "FROM rt_realtime_table WHERE id=:id",
        "SELECT id,catalog_name,database_name,table_name,table_comment,table_type,creation_source,"
        "producer_task_id,physical_status,last_error,last_synced_at,update_time "
        "FROM rt_realtime_table ORDER BY update_time DESC LIMIT 20",
    ),
    "REALTIME_SERVER": (
        "SELECT id,name,type,database_name,database_prefix,description,operator,update_time "
        "FROM rt_server WHERE id=:id",
        "SELECT id,name,type,database_name,database_prefix,description,operator,update_time "
        "FROM rt_server ORDER BY update_time DESC LIMIT 20",
    ),
    "REALTIME_ALERT": (
        "SELECT id,task_id,severity,status,title,detail,create_time,update_time FROM rt_alert WHERE id=:id",
        "SELECT id,task_id,severity,status,title,detail,create_time,update_time FROM rt_alert "
        "ORDER BY create_time DESC LIMIT 20",
    ),
}


class PlatformContextAdapter:
    source = "sql-agent-db"

    def __init__(self, db_uri: str | None, *, timeout_seconds: int) -> None:
        self.db_uri = db_uri
        self.timeout_seconds = timeout_seconds
        self._engine: Engine | None = None

    def get_context(self, context_type: str, entity_id: str | None) -> dict[str, Any]:
        if context_type == "PLATFORM_STATUS":
            self._engine_or_raise().connect().close()
            return {"contextType": context_type, "databaseReachable": True}
        query_pair = ENTITY_QUERIES.get(context_type)
        if query_pair is None:
            raise McpDomainError(
                McpErrorCode.INVALID_REQUEST,
                "Unsupported platform context type.",
                details={"contextType": context_type},
            )
        sql = query_pair[0] if entity_id else query_pair[1]
        try:
            with self._engine_or_raise().connect() as conn:
                rows = conn.execute(text(sql), {"id": entity_id} if entity_id else {}).mappings().all()
                if entity_id and not rows:
                    raise McpDomainError(
                        McpErrorCode.NOT_FOUND,
                        "Platform context entity was not found.",
                        details={"contextType": context_type, "entityId": entity_id},
                    )
                items = [_serialize(dict(row)) for row in rows]
                result: dict[str, Any] = {
                    "contextType": context_type,
                    "entityId": entity_id,
                    "entity": items[0] if entity_id else None,
                    "items": [] if entity_id else items,
                }
                if entity_id:
                    result["related"] = self._related(context_type, entity_id, conn)
                    if context_type == "REALTIME_INSTANCE":
                        result["related"]["liveRuntime"] = self._live_runtime(items[0])
                return result
        except McpDomainError:
            raise
        except Exception as exc:  # noqa: BLE001
            raise McpDomainError(
                McpErrorCode.DEPENDENCY_UNAVAILABLE,
                "Platform context query failed.",
                details={"errorType": type(exc).__name__},
            ) from exc

    def _related(self, context_type: str, entity_id: str, conn) -> dict[str, Any]:
        queries: dict[str, dict[str, str]] = {
            "OFFLINE_SCHEDULE": {
                "dependencies": "SELECT upstream_task_id,dependency_type FROM sql_task_dependency WHERE task_id=:id",
                "recentRuns": "SELECT id,trigger_type,business_date,status,attempt_no,execution_id,message,create_time "
                "FROM sql_task_schedule_run WHERE task_id=:id ORDER BY id DESC LIMIT 10",
            },
            "DATA_COMPARE": {
                "tableResults": "SELECT id,original_tbl_name,baseline_source_tbl_name,candidate_source_tbl_name,"
                "baseline_tbl_name,candidate_tbl_name,compare_rule compare_rule_json,rule_revision,"
                "verified_rule_revision,result_stale,rerun_count,force_pass,force_reason,is_part_tab,"
                "part_nums,partition_scope partition_scope_json,meta_data_is_same,meta_data_diff meta_data_diff_json,"
                "row_num_is_same,row_nums row_nums_json,crc32_value_is_same,crc32_values crc32_values_json,"
                "col_probe_detail col_probe_detail_json,diff_detail diff_detail_json,status,error_message,"
                "started_at,finished_at FROM data_compare_tbl_verify WHERE job_id=:id ORDER BY id LIMIT 100",
            },
            "REALTIME_SYNC_TASK": {
                "tableMappings": "SELECT source_database,source_table,target_database,target_table,sort_order "
                "FROM rt_sync_task_table_mapping WHERE task_id=:id ORDER BY sort_order",
                "latestProgress": "SELECT task_instance_id,snapshot_finished,snapshot_remaining,snapshot_progress,"
                "source_lag_ms,source_idle_ms,dirty_record_count,offset_summary,observed_at "
                "FROM rt_sync_progress_snapshot WHERE task_id=:id ORDER BY observed_at DESC LIMIT 5",
                "unresolvedDirtyRecords": "SELECT id,task_instance_id,source_database,source_table,operation_type,"
                "error_code,error_message,create_time FROM rt_sync_dirty_record "
                "WHERE task_id=:id AND resolved_flag=0 ORDER BY id DESC LIMIT 20",
                "schemaChanges": "SELECT id,realtime_table_id,source_database,source_table,target_database,target_table,"
                "change_type,status,change_payload change_payload_json,detected_at,message "
                "FROM rt_schema_change_event WHERE task_id=:id ORDER BY id DESC LIMIT 20",
            },
            "REALTIME_INSTANCE": {
                "progress": "SELECT task_id,task_instance_id,snapshot_finished,snapshot_remaining,snapshot_progress,"
                "source_lag_ms,source_idle_ms,dirty_record_count,offset_summary,observed_at "
                "FROM rt_sync_progress_snapshot WHERE task_instance_id=:id LIMIT 1",
                "dirtyRecords": "SELECT id,source_database,source_table,operation_type,error_code,error_message,"
                "resolved_flag,create_time FROM rt_sync_dirty_record WHERE task_instance_id=:id "
                "ORDER BY id DESC LIMIT 20",
                "schemaChanges": "SELECT id,realtime_table_id,source_database,source_table,target_database,target_table,"
                "change_type,status,change_payload change_payload_json,detected_at,message "
                "FROM rt_schema_change_event WHERE task_id=(SELECT task_id FROM rt_task_instance WHERE id=:id) "
                "ORDER BY id DESC LIMIT 20",
                "alerts": "SELECT id,severity,status,title,detail,create_time FROM rt_alert "
                "WHERE task_id=(SELECT task_id FROM rt_task_instance WHERE id=:id) ORDER BY id DESC LIMIT 20",
            },
            "REALTIME_EXPORT_TASK": {
                "tableMappings": "SELECT realtime_table_id,target_database,target_table,column_mapping_json,"
                "primary_keys_json,write_mode,sort_order FROM rt_export_task_table_mapping WHERE task_id=:id ORDER BY sort_order",
            },
            "REALTIME_TABLE": {
                "columns": "SELECT column_name,data_type,nullable_flag,primary_key_flag,partition_key_flag,column_comment,sort_order "
                "FROM rt_realtime_table_column WHERE realtime_table_id=:id ORDER BY sort_order",
            },
        }
        selected_queries = dict(queries.get(context_type, {}))
        if context_type in {"REALTIME_SYNC_TASK", "REALTIME_COMPUTE_TASK", "REALTIME_EXPORT_TASK"}:
            selected_queries.update({
                "versions": "SELECT id,version_no,operator,create_time,update_time FROM rt_task_version "
                "WHERE task_id=:id ORDER BY version_no DESC LIMIT 20",
                "instances": "SELECT id,version_id,job_id,yarn_application_id,status,execution_mode,managed_flag,"
                "failure_message,started_at,ended_at,update_time FROM rt_task_instance "
                "WHERE task_id=:id ORDER BY id DESC LIMIT 20",
                "alerts": "SELECT id,severity,status,title,detail,create_time FROM rt_alert "
                "WHERE task_id=:id ORDER BY id DESC LIMIT 20",
                "changeLogs": "SELECT id,task_instance_id,operator,action,detail,create_time "
                "FROM rt_task_change_log WHERE task_id=:id ORDER BY id DESC LIMIT 20",
            })
        if context_type == "REALTIME_ALERT":
            selected_queries.update({
                "task": "SELECT id,task_name,task_type,status,owner,description,update_time FROM rt_task "
                "WHERE id=(SELECT task_id FROM rt_alert WHERE id=:id)",
                "relatedAlerts": "SELECT id,severity,status,title,detail,create_time FROM rt_alert "
                "WHERE task_id=(SELECT task_id FROM rt_alert WHERE id=:id) ORDER BY id DESC LIMIT 20",
            })
        result: dict[str, Any] = {}
        for key, sql in selected_queries.items():
            rows = conn.execute(text(sql), {"id": entity_id}).mappings().all()
            result[key] = [_serialize(dict(row)) for row in rows]
        return result

    def _live_runtime(self, instance: dict[str, Any]) -> dict[str, Any]:
        """从实例 Tracking URL 读取有界、只读的 Flink 运行事实。"""

        tracking_url = str(instance.get("tracking_url") or "").strip().rstrip("/")
        job_id = str(instance.get("job_id") or "").strip()
        parsed = urlparse(tracking_url)
        if not tracking_url or not job_id or parsed.scheme not in {"http", "https"} or not parsed.netloc:
            return {"available": False, "reason": "tracking_url_or_job_id_unavailable"}
        if parsed.username or parsed.password:
            return {"available": False, "reason": "tracking_url_contains_credentials"}

        client = ReadonlyJsonHttpClient(
            (tracking_url,),
            dependency_name="Flink runtime",
            timeout_seconds=self.timeout_seconds,
            max_response_bytes=2 * 1024 * 1024,
        )
        endpoints = {
            "clusterOverview": "/overview",
            "jobOverview": f"/jobs/{job_id}",
            "checkpoints": f"/jobs/{job_id}/checkpoints",
        }
        facts: dict[str, Any] = {"available": True}
        for key, path in endpoints.items():
            try:
                facts[key] = _compact_runtime_payload(key, client.get_json(path))
            except McpDomainError as exc:
                facts[key] = {"available": False, "code": exc.code.value}
        return facts

    def _engine_or_raise(self) -> Engine:
        if self._engine is not None:
            return self._engine
        if not self.db_uri:
            raise McpDomainError(
                McpErrorCode.DEPENDENCY_UNAVAILABLE,
                "SQL Agent DB URI is not configured.",
                details={"required": "SQL_AGENT_DB_URI"},
            )
        connect_args = {
            "connect_timeout": self.timeout_seconds,
            "read_timeout": self.timeout_seconds,
            "write_timeout": self.timeout_seconds,
        } if self.db_uri.startswith("mysql") else {}
        self._engine = create_engine(self.db_uri, pool_pre_ping=True, connect_args=connect_args)
        return self._engine


def _serialize(row: dict[str, Any]) -> dict[str, Any]:
    for key, value in list(row.items()):
        if hasattr(value, "isoformat"):
            row[key] = value.isoformat()
        elif key.endswith("_json") and isinstance(value, str):
            try:
                row[key] = _sanitize_value(json.loads(value))
            except json.JSONDecodeError:
                row[key] = None
        elif isinstance(value, str):
            row[key] = _safe_text(value)
        elif isinstance(value, (dict, list)):
            row[key] = _sanitize_value(value)
    return row


def _sanitize_value(value: Any) -> Any:
    """递归清理只读接口响应，避免凭据和完整异常栈进入模型。"""

    if isinstance(value, dict):
        result: dict[str, Any] = {}
        for key, item in value.items():
            normalized = re.sub(r"[^a-z0-9]", "", str(key).lower())
            if any(marker in normalized for marker in (
                "password", "passwd", "token", "secret", "credential", "connectionstring", "jdbcurl",
            )):
                continue
            result[str(key)] = _sanitize_value(item)
        return result
    if isinstance(value, list):
        return [_sanitize_value(item) for item in value[:200]]
    if isinstance(value, str):
        return _safe_text(value)
    if hasattr(value, "isoformat"):
        return value.isoformat()
    return value


def _compact_runtime_payload(kind: str, payload: dict[str, Any]) -> dict[str, Any]:
    """只保留诊断所需的 Flink 字段，并限制数组规模。"""

    if kind == "clusterOverview":
        keys = {
            "taskmanagers", "slots-total", "slots-available", "jobs-running", "jobs-finished",
            "jobs-cancelled", "jobs-failed", "flink-version",
        }
        return _sanitize_value({key: payload[key] for key in keys if key in payload})
    if kind == "jobOverview":
        keys = {"jid", "name", "state", "start-time", "end-time", "duration", "now", "timestamps", "status-counts"}
        result = {key: payload[key] for key in keys if key in payload}
        vertices = payload.get("vertices")
        if isinstance(vertices, list):
            vertex_keys = {"id", "name", "status", "start-time", "end-time", "duration", "parallelism", "metrics"}
            result["vertices"] = [
                {key: vertex[key] for key in vertex_keys if key in vertex}
                for vertex in vertices[:100] if isinstance(vertex, dict)
            ]
        return _sanitize_value(result)
    if kind == "checkpoints":
        result = {key: payload[key] for key in ("counts", "summary", "latest") if key in payload}
        history = payload.get("history")
        if isinstance(history, list):
            checkpoint_keys = {
                "id", "status", "is_savepoint", "trigger_timestamp", "latest_ack_timestamp",
                "state_size", "end_to_end_duration", "alignment_buffered", "num_subtasks",
                "num_acknowledged_subtasks", "checkpoint_type",
            }
            result["history"] = [
                {key: item[key] for key in checkpoint_keys if key in item}
                for item in history[:20] if isinstance(item, dict)
            ]
        return _sanitize_value(result)
    return {}


def _safe_text(value: str) -> str:
    """保留诊断摘要，但移除凭据、连接串认证段和 Java/Python 调用栈。"""

    text_value = re.sub(r"(?i)(bearer\s+)[a-z0-9._~+/-]+=*", r"\1[REDACTED]", value)
    text_value = re.sub(
        r"(?i)([a-z][a-z0-9+.-]*://)[^/@\s:]+:[^/@\s]+@",
        r"\1[REDACTED]@",
        text_value,
    )
    # 兼容数据库中按字面量保存的 ``\\n`` 日志。
    normalized = text_value.replace("\\r\\n", "\n").replace("\\n", "\n")
    lines = []
    for line in normalized.splitlines() or [normalized]:
        stripped = line.strip()
        if re.match(r"^(at\s+[\w.$]+\(|\.\.\.\s+\d+\s+more$|File\s+\".*\",\s+line\s+\d+)", stripped):
            continue
        lines.append(line)
        if len(lines) >= 8:
            break
    return "\n".join(lines)[:2000]
