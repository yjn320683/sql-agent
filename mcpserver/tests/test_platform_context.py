from sqlalchemy import create_engine, text

from sql_agent_mcp_server.common.http import ReadonlyJsonHttpClient
from sql_agent_mcp_server.domains.platform_context.adapter import PlatformContextAdapter


def test_server_context_never_returns_address_account_or_password(tmp_path):
    db = tmp_path / "platform.db"
    engine = create_engine(f"sqlite:///{db}")
    with engine.begin() as conn:
        conn.execute(text("""
            CREATE TABLE rt_server (
              id INTEGER PRIMARY KEY, name TEXT, type TEXT, address TEXT, database_name TEXT,
              database_prefix TEXT, account TEXT, password TEXT, description TEXT, operator TEXT, update_time TEXT
            )
        """))
        conn.execute(text("""
            INSERT INTO rt_server VALUES
            (1,'demo','mysql','jdbc:mysql://10.0.0.8/db','db','ods','real_user','real_secret','test','admin','2026-09-08')
        """))
    result = PlatformContextAdapter(f"sqlite:///{db}", timeout_seconds=2).get_context("REALTIME_SERVER", "1")
    entity = result["entity"]
    assert entity["name"] == "demo"
    assert "address" not in entity
    assert "account" not in entity
    assert "password" not in entity


def test_realtime_table_context_contains_columns(tmp_path):
    db = tmp_path / "table.db"
    engine = create_engine(f"sqlite:///{db}")
    with engine.begin() as conn:
        conn.execute(text("""
            CREATE TABLE rt_realtime_table (
              id INTEGER PRIMARY KEY,catalog_name TEXT,database_name TEXT,table_name TEXT,table_comment TEXT,
              table_type TEXT,creation_source TEXT,producer_task_id INTEGER,physical_status TEXT,
              table_options_json TEXT,last_error TEXT,last_synced_at TEXT,update_time TEXT
            )
        """))
        conn.execute(text("""
            CREATE TABLE rt_realtime_table_column (
              realtime_table_id INTEGER,column_name TEXT,data_type TEXT,nullable_flag INTEGER,
              primary_key_flag INTEGER,partition_key_flag INTEGER,column_comment TEXT,sort_order INTEGER
            )
        """))
        conn.execute(text("INSERT INTO rt_realtime_table VALUES (2,'paimon','ods','orders','', 'primary_key','manual',NULL,'declared','{\"bucket\":\"2\"}',NULL,NULL,'2026-09-08')"))
        conn.execute(text("INSERT INTO rt_realtime_table_column VALUES (2,'id','BIGINT',0,1,0,'key',0)"))
    result = PlatformContextAdapter(f"sqlite:///{db}", timeout_seconds=2).get_context("REALTIME_TABLE", "2")
    assert result["entity"]["table_options_json"] == {"bucket": "2"}
    assert result["related"]["columns"][0]["column_name"] == "id"


def test_data_compare_context_uses_numeric_job_and_returns_bounded_results(tmp_path):
    db = tmp_path / "compare.db"
    engine = create_engine(f"sqlite:///{db}")
    with engine.begin() as conn:
        conn.execute(text("""
            CREATE TABLE data_compare_job_detail (
              id INTEGER PRIMARY KEY,compare_type TEXT,task_id INTEGER,baseline_version_no INTEGER,
              candidate_version_no INTEGER,union_id INTEGER,baseline_table TEXT,candidate_table TEXT,status TEXT,
              error_message TEXT,operator_ob_id TEXT,started_at TEXT,finished_at TEXT,create_time TEXT,update_time TEXT
            )
        """))
        conn.execute(text("""
            CREATE TABLE data_compare_tbl_verify (
              id INTEGER,job_id INTEGER,original_tbl_name TEXT,baseline_source_tbl_name TEXT,
              candidate_source_tbl_name TEXT,baseline_tbl_name TEXT,candidate_tbl_name TEXT,compare_rule TEXT,
              rule_revision INTEGER,verified_rule_revision INTEGER,result_stale INTEGER,rerun_count INTEGER,
              force_pass INTEGER,force_reason TEXT,is_part_tab INTEGER,part_nums INTEGER,partition_scope TEXT,
              meta_data_is_same INTEGER,meta_data_diff TEXT,row_num_is_same INTEGER,row_nums TEXT,
              crc32_value_is_same INTEGER,crc32_values TEXT,col_probe_detail TEXT,diff_detail TEXT,status TEXT,
              error_message TEXT,started_at TEXT,finished_at TEXT
            )
        """))
        conn.execute(text("INSERT INTO data_compare_job_detail VALUES (12,'TABLE',8,3,4,NULL,'ods.a','ods.b','FAILED','count mismatch','1',NULL,NULL,'2026-09-08','2026-09-08')"))
        conn.execute(text("""INSERT INTO data_compare_tbl_verify VALUES
            (31,12,'orders','ods.a','ods.b','tmp.a','tmp.b',:rule,1,1,0,0,0,NULL,0,0,:empty,1,:empty,0,
             :counts,0,:empty,:empty,:empty,'FAILED','row mismatch',NULL,NULL)"""), {
            "rule": '{"mode":"full"}', "empty": "{}", "counts": '{"baseline":2,"candidate":3}',
        })

    result = PlatformContextAdapter(f"sqlite:///{db}", timeout_seconds=2).get_context("DATA_COMPARE", "12")

    assert result["entity"]["id"] == 12
    assert result["related"]["tableResults"][0]["compare_rule_json"] == {"mode": "full"}
    assert result["related"]["tableResults"][0]["row_nums_json"]["candidate"] == 3


def test_realtime_instance_context_contains_observability_without_raw_payload_or_stack(tmp_path, monkeypatch):
    db = tmp_path / "instance.db"
    engine = create_engine(f"sqlite:///{db}")
    with engine.begin() as conn:
        conn.execute(text("""
            CREATE TABLE rt_task_instance (
              id INTEGER PRIMARY KEY,task_id INTEGER,version_id INTEGER,job_id TEXT,yarn_application_id TEXT,
              status TEXT,execution_mode TEXT,managed_flag INTEGER,savepoint_path TEXT,tracking_url TEXT,
              failure_message TEXT,started_at TEXT,ended_at TEXT,update_time TEXT
            )
        """))
        conn.execute(text("""
            CREATE TABLE rt_sync_progress_snapshot (
              task_id INTEGER,task_instance_id INTEGER,snapshot_finished INTEGER,snapshot_remaining INTEGER,
              snapshot_progress REAL,source_lag_ms INTEGER,source_idle_ms INTEGER,dirty_record_count INTEGER,
              offset_summary TEXT,observed_at TEXT
            )
        """))
        conn.execute(text("""
            CREATE TABLE rt_sync_dirty_record (
              id INTEGER,task_id INTEGER,task_instance_id INTEGER,source_database TEXT,source_table TEXT,
              operation_type TEXT,error_code TEXT,error_message TEXT,raw_payload TEXT,resolved_flag INTEGER,create_time TEXT
            )
        """))
        conn.execute(text("""
            CREATE TABLE rt_schema_change_event (
              id INTEGER,task_id INTEGER,realtime_table_id INTEGER,source_database TEXT,source_table TEXT,
              target_database TEXT,target_table TEXT,change_type TEXT,status TEXT,change_payload TEXT,
              detected_at TEXT,message TEXT
            )
        """))
        conn.execute(text("""
            CREATE TABLE rt_alert (
              id INTEGER,task_id INTEGER,severity TEXT,status TEXT,title TEXT,detail TEXT,create_time TEXT
            )
        """))
        conn.execute(text("INSERT INTO rt_task_instance VALUES (9,3,2,'job-1','app-1','failed','PRODUCTION',1,NULL,'http://flink.local/proxy/app-1','boom\\n  at com.example.Secret.run(Secret.java:1)','2026-09-08',NULL,'2026-09-08')"))
        conn.execute(text("INSERT INTO rt_sync_progress_snapshot VALUES (3,9,8,2,.8,1200,10,1,'binlog.42','2026-09-08')"))
        conn.execute(text("INSERT INTO rt_sync_dirty_record VALUES (7,3,9,'src','orders','UPDATE','CAST','bad row','{\"password\":\"secret\"}',0,'2026-09-08')"))
        conn.execute(text("INSERT INTO rt_schema_change_event VALUES (8,3,4,'src','orders','ods','orders','ADD_COLUMN','PENDING','{\"column\":\"memo\"}','2026-09-08','compatible')"))
        conn.execute(text("INSERT INTO rt_alert VALUES (6,3,'HIGH','OPEN','lag','source lag\\n at com.example.Job.run(Job.java:2)','2026-09-08')"))

    requested_paths = []

    def fake_get_json(self, path, **kwargs):
        requested_paths.append(path)
        if path.endswith("/checkpoints"):
            return {"counts": {"completed": 2}, "latest": {"completed": {"id": 7}}}
        if path == "/overview":
            return {"taskmanagers": 2, "slots-total": 8}
        return {"jid": "job-1", "state": "FAILED", "exception": "boom\\n at com.secret.Stack.run(Stack.java:1)"}

    monkeypatch.setattr(ReadonlyJsonHttpClient, "get_json", fake_get_json)
    result = PlatformContextAdapter(f"sqlite:///{db}", timeout_seconds=2).get_context("REALTIME_INSTANCE", "9")

    assert result["related"]["progress"][0]["snapshot_progress"] == 0.8
    assert "raw_payload" not in result["related"]["dirtyRecords"][0]
    assert result["related"]["schemaChanges"][0]["change_payload_json"] == {"column": "memo"}
    assert "at com.example" not in result["entity"]["failure_message"]
    assert "at com.example" not in result["related"]["alerts"][0]["detail"]
    assert requested_paths == ["/overview", "/jobs/job-1", "/jobs/job-1/checkpoints"]
    assert result["related"]["liveRuntime"]["checkpoints"]["counts"]["completed"] == 2
    assert "exception" not in result["related"]["liveRuntime"]["jobOverview"]
