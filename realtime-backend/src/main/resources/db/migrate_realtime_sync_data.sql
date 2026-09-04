-- 一次性从参考库 flink_paimon_research 迁移同步任务数据。
-- 执行前必须先执行 realtime_sync_schema.sql；脚本发现目标表已有数据会直接失败。
SET NAMES utf8mb4;
DELIMITER $$
DROP PROCEDURE IF EXISTS migrate_realtime_sync_data$$
CREATE PROCEDURE migrate_realtime_sync_data()
BEGIN
  DECLARE existing_rows BIGINT DEFAULT 0;
  DECLARE invalid_rows BIGINT DEFAULT 0;
  DECLARE source_rows BIGINT DEFAULT 0;
  DECLARE target_rows BIGINT DEFAULT 0;
  DECLARE EXIT HANDLER FOR SQLEXCEPTION
  BEGIN
    ROLLBACK;
    RESIGNAL;
  END;
  SELECT SUM(row_count) INTO existing_rows FROM (
    SELECT COUNT(*) row_count FROM rt_task UNION ALL
    SELECT COUNT(*) FROM rt_task_version UNION ALL
    SELECT COUNT(*) FROM rt_sync_task_config UNION ALL
    SELECT COUNT(*) FROM rt_sync_task_table_mapping UNION ALL
    SELECT COUNT(*) FROM rt_task_param UNION ALL
    SELECT COUNT(*) FROM rt_project UNION ALL
    SELECT COUNT(*) FROM rt_server UNION ALL
    SELECT COUNT(*) FROM rt_task_instance UNION ALL
    SELECT COUNT(*) FROM rt_task_operation UNION ALL
    SELECT COUNT(*) FROM rt_task_change_log UNION ALL
    SELECT COUNT(*) FROM rt_alert UNION ALL
    SELECT COUNT(*) FROM rt_paimon_business_domain
  ) checked;
  IF existing_rows > 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='目标实时同步表已有数据，禁止重复迁移';
  END IF;

  SELECT SUM(invalid_count) INTO invalid_rows FROM (
    SELECT COUNT(*) invalid_count FROM flink_paimon_research.rt_task_version v
      JOIN flink_paimon_research.rt_task t ON t.id=v.task_id AND t.task_type='sync' AND COALESCE(t.deleted_flag,0)=0
      WHERE JSON_VALID(v.config)=0
    UNION ALL
    SELECT COUNT(*) FROM flink_paimon_research.rt_sync_task_config c
      JOIN flink_paimon_research.rt_task t ON t.id=c.task_id AND t.task_type='sync' AND COALESCE(t.deleted_flag,0)=0
      WHERE JSON_VALID(c.config_json)=0
    UNION ALL
    SELECT COUNT(*) FROM flink_paimon_research.rt_task_instance i
      JOIN flink_paimon_research.rt_task t ON t.id=i.task_id AND t.task_type='sync' AND COALESCE(t.deleted_flag,0)=0
      WHERE i.effective_config_snapshot_json IS NOT NULL AND JSON_VALID(i.effective_config_snapshot_json)=0
    UNION ALL
    SELECT COUNT(*) FROM flink_paimon_research.rt_task_operation o
      JOIN flink_paimon_research.rt_task t ON t.id=o.task_id AND t.task_type='sync' AND COALESCE(t.deleted_flag,0)=0
      WHERE (o.request_json IS NOT NULL AND JSON_VALID(o.request_json)=0)
         OR (o.result_json IS NOT NULL AND JSON_VALID(o.result_json)=0)
  ) json_check;
  IF invalid_rows > 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='参考库同步任务存在非法 JSON，已取消迁移';
  END IF;

  START TRANSACTION;
  INSERT INTO rt_project (id, project_name, enabled_flag, create_time, update_time)
  SELECT p.id, p.project_name, p.enabled_flag, p.create_time, p.update_time
  FROM flink_paimon_research.rt_project p
  WHERE EXISTS (SELECT 1 FROM flink_paimon_research.rt_task t
    WHERE t.task_type='sync' AND COALESCE(t.deleted_flag,0)=0 AND t.project_id=p.id);

  INSERT INTO rt_task (id, project_id, task_name, task_type, flink_version, owner, description, status, create_time, update_time)
  SELECT id, project_id, task_name, task_type, flink_version, owner, description, status, create_time, update_time
  FROM flink_paimon_research.rt_task WHERE task_type='sync' AND COALESCE(deleted_flag,0)=0;

  INSERT INTO rt_server (id, name, type, address, database_name, database_prefix, account, password, description, operator, create_time, update_time)
  SELECT s.id, s.name, s.type, s.address, s.database_name, s.database_prefix, s.account, s.password,
         s.description, s.operator, s.create_time, s.update_time
  FROM flink_paimon_research.rt_server s
  WHERE EXISTS (SELECT 1 FROM flink_paimon_research.rt_sync_task_config c
                JOIN flink_paimon_research.rt_task t ON t.id=c.task_id AND t.task_type='sync' AND COALESCE(t.deleted_flag,0)=0
                WHERE c.source_server_id=s.id);

  INSERT INTO rt_task_version (id, task_id, version_no, config, operator, create_time, update_time)
  SELECT v.id, v.task_id, v.version_no, v.config, v.operator, v.create_time, v.update_time
  FROM flink_paimon_research.rt_task_version v
  JOIN flink_paimon_research.rt_task t ON t.id=v.task_id AND t.task_type='sync' AND COALESCE(t.deleted_flag,0)=0;

  INSERT INTO rt_sync_task_config
  SELECT c.* FROM flink_paimon_research.rt_sync_task_config c
  JOIN flink_paimon_research.rt_task t ON t.id=c.task_id AND t.task_type='sync' AND COALESCE(t.deleted_flag,0)=0;

  INSERT INTO rt_sync_task_table_mapping
  SELECT m.* FROM flink_paimon_research.rt_sync_task_table_mapping m
  JOIN flink_paimon_research.rt_task t ON t.id=m.task_id AND t.task_type='sync' AND COALESCE(t.deleted_flag,0)=0;

  INSERT INTO rt_task_param
  SELECT * FROM flink_paimon_research.rt_task_param WHERE task_type='sync';

  INSERT INTO rt_task_instance
    (id, task_id, version_id, job_id, yarn_application_id, status, execution_mode, managed_flag,
     startup_log, effective_config_snapshot_json, savepoint_path, tracking_url, failure_message,
     last_runtime_log, started_at, ended_at, create_time, update_time)
  SELECT i.id, i.task_id, i.version_id, i.job_id, i.yarn_application_id, i.status, i.execution_mode, 0,
         i.startup_log, i.effective_config_snapshot_json, i.savepoint_path, i.tracking_url, i.failure_message,
         i.last_runtime_log, i.started_at, i.ended_at, i.create_time, i.update_time
  FROM flink_paimon_research.rt_task_instance i
  JOIN flink_paimon_research.rt_task t ON t.id=i.task_id AND t.task_type='sync' AND COALESCE(t.deleted_flag,0)=0;

  INSERT INTO rt_task_operation
    (id, task_id, task_instance_id, operation_type, operation_status, operator, request_json, result_json,
     error_message, start_time, deadline_at, end_time, active_flag, create_time, update_time)
  SELECT o.id, o.task_id, o.task_instance_id, o.operation_type, o.operation_status, o.operator,
         o.request_json, o.result_json, o.error_message, o.start_time, o.deadline_at, o.end_time,
         NULL, o.create_time, o.update_time
  FROM flink_paimon_research.rt_task_operation o
  JOIN flink_paimon_research.rt_task t ON t.id=o.task_id AND t.task_type='sync' AND COALESCE(t.deleted_flag,0)=0;

  INSERT INTO rt_task_change_log
    (id, task_id, operation_id, before_version_id, after_version_id, task_instance_id,
     operator, action, detail, create_time, update_time)
  SELECT l.id, l.task_id, l.operation_id, l.before_version_id, l.after_version_id, l.task_instance_id,
         l.operator, l.action, l.detail, l.create_time, l.update_time
  FROM flink_paimon_research.rt_task_change_log l
  JOIN flink_paimon_research.rt_task t ON t.id=l.task_id AND t.task_type='sync' AND COALESCE(t.deleted_flag,0)=0;

  INSERT INTO rt_alert (id, task_id, severity, status, title, detail, create_time, update_time)
  SELECT a.id, a.object_id, a.severity, a.status, a.title, a.detail, a.create_time, a.update_time
  FROM flink_paimon_research.rt_alert a
  JOIN flink_paimon_research.rt_task t ON a.object_type='task' AND a.object_id=t.id
    AND t.task_type='sync' AND COALESCE(t.deleted_flag,0)=0;

  INSERT INTO rt_paimon_business_domain
  SELECT * FROM flink_paimon_research.rt_paimon_business_domain;

  SELECT COUNT(*) INTO source_rows FROM flink_paimon_research.rt_task
   WHERE task_type='sync' AND COALESCE(deleted_flag,0)=0;
  SELECT COUNT(*) INTO target_rows FROM rt_task;
  IF source_rows <> target_rows THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='同步任务数量对账失败';
  END IF;
  SELECT COUNT(*) INTO source_rows FROM flink_paimon_research.rt_task_instance i
    JOIN flink_paimon_research.rt_task t ON t.id=i.task_id AND t.task_type='sync' AND COALESCE(t.deleted_flag,0)=0;
  SELECT COUNT(*) INTO target_rows FROM rt_task_instance;
  IF source_rows <> target_rows THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='同步运行实例数量对账失败';
  END IF;
  SELECT COUNT(*) INTO source_rows FROM flink_paimon_research.rt_task_version v
    JOIN flink_paimon_research.rt_task t ON t.id=v.task_id AND t.task_type='sync' AND COALESCE(t.deleted_flag,0)=0;
  SELECT COUNT(*) INTO target_rows FROM rt_task_version;
  IF source_rows <> target_rows THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='同步版本数量对账失败';
  END IF;

  SELECT SUM(invalid_count) INTO invalid_rows FROM (
    SELECT COUNT(*) invalid_count FROM rt_sync_task_config c LEFT JOIN rt_task t ON t.id=c.task_id WHERE t.id IS NULL
    UNION ALL SELECT COUNT(*) FROM rt_task_version v LEFT JOIN rt_task t ON t.id=v.task_id WHERE t.id IS NULL
    UNION ALL SELECT COUNT(*) FROM rt_sync_task_table_mapping m LEFT JOIN rt_task t ON t.id=m.task_id WHERE t.id IS NULL
    UNION ALL SELECT COUNT(*) FROM rt_task_instance i LEFT JOIN rt_task t ON t.id=i.task_id WHERE t.id IS NULL
    UNION ALL SELECT COUNT(*) FROM rt_task_operation o LEFT JOIN rt_task t ON t.id=o.task_id WHERE t.id IS NULL
    UNION ALL SELECT COUNT(*) FROM rt_task_change_log l LEFT JOIN rt_task t ON t.id=l.task_id WHERE t.id IS NULL
    UNION ALL SELECT COUNT(*) FROM rt_alert a LEFT JOIN rt_task t ON t.id=a.task_id WHERE t.id IS NULL
    UNION ALL SELECT COUNT(*) FROM rt_sync_task_config c LEFT JOIN rt_server s ON s.id=c.source_server_id WHERE s.id IS NULL
  ) orphan_check;
  IF invalid_rows > 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='同步任务引用闭包校验失败';
  END IF;
  SELECT COUNT(*) INTO invalid_rows FROM rt_task_instance WHERE managed_flag<>0;
  IF invalid_rows > 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='导入实例管理标识校验失败';
  END IF;
  SELECT COUNT(*) INTO invalid_rows FROM rt_task_operation WHERE active_flag IS NOT NULL;
  IF invalid_rows > 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='导入操作锁清理校验失败';
  END IF;
  COMMIT;
END$$
DELIMITER ;
CALL migrate_realtime_sync_data();
DROP PROCEDURE migrate_realtime_sync_data;

-- 孤立引用必须全部为 0。
SELECT 'orphan_config' check_name, COUNT(*) invalid_count
FROM rt_sync_task_config c LEFT JOIN rt_task t ON t.id=c.task_id WHERE t.id IS NULL
UNION ALL
SELECT 'orphan_version', COUNT(*) FROM rt_task_version v LEFT JOIN rt_task t ON t.id=v.task_id WHERE t.id IS NULL
UNION ALL
SELECT 'orphan_instance', COUNT(*) FROM rt_task_instance i LEFT JOIN rt_task t ON t.id=i.task_id WHERE t.id IS NULL
UNION ALL
SELECT 'managed_import', COUNT(*) FROM rt_task_instance WHERE managed_flag<>0;
