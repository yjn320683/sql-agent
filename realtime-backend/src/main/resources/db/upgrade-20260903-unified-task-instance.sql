-- 将旧版同步运行实例命名升级为参考项目的统一任务实例命名。
SET NAMES utf8mb4;
DELIMITER $$
DROP PROCEDURE IF EXISTS upgrade_unified_task_instance$$
CREATE PROCEDURE upgrade_unified_task_instance()
BEGIN
  DECLARE old_table_count INT DEFAULT 0;
  DECLARE new_table_count INT DEFAULT 0;
  DECLARE old_column_count INT DEFAULT 0;
  DECLARE index_count INT DEFAULT 0;
  DECLARE replacement_index_count INT DEFAULT 0;

  SELECT COUNT(*) INTO old_table_count FROM information_schema.tables
   WHERE table_schema=DATABASE() AND table_name='rt_job_instance';
  SELECT COUNT(*) INTO new_table_count FROM information_schema.tables
   WHERE table_schema=DATABASE() AND table_name='rt_task_instance';
  IF old_table_count=1 AND new_table_count=1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='rt_job_instance 与 rt_task_instance 同时存在，请先人工确认数据';
  END IF;
  IF old_table_count=1 AND new_table_count=0 THEN
    RENAME TABLE rt_job_instance TO rt_task_instance;
  END IF;

  SELECT COUNT(*) INTO old_column_count FROM information_schema.columns
   WHERE table_schema=DATABASE() AND table_name='rt_task_operation' AND column_name='job_instance_id';
  IF old_column_count=1 THEN
    ALTER TABLE rt_task_operation CHANGE COLUMN job_instance_id task_instance_id BIGINT NULL;
  END IF;

  SELECT COUNT(*) INTO old_column_count FROM information_schema.columns
   WHERE table_schema=DATABASE() AND table_name='rt_task_change_log' AND column_name='job_instance_id';
  IF old_column_count=1 THEN
    ALTER TABLE rt_task_change_log CHANGE COLUMN job_instance_id task_instance_id BIGINT NULL;
  END IF;

  SELECT COUNT(*) INTO index_count FROM information_schema.statistics
   WHERE table_schema=DATABASE() AND table_name='rt_task_instance'
     AND index_name='idx_task_instance_task_mode_status';
  IF index_count=0 THEN
    ALTER TABLE rt_task_instance ADD KEY idx_task_instance_task_mode_status
      (task_id, execution_mode, status);
  END IF;

  SELECT COUNT(*) INTO index_count FROM information_schema.statistics
   WHERE table_schema=DATABASE() AND table_name='rt_task_instance'
     AND index_name='idx_job_managed_status';
  SELECT COUNT(*) INTO replacement_index_count FROM information_schema.statistics
   WHERE table_schema=DATABASE() AND table_name='rt_task_instance'
     AND index_name='idx_task_instance_managed_status';
  IF index_count>0 AND replacement_index_count=0 THEN
    ALTER TABLE rt_task_instance RENAME INDEX idx_job_managed_status TO idx_task_instance_managed_status;
  END IF;

  SELECT COUNT(*) INTO index_count FROM information_schema.statistics
   WHERE table_schema=DATABASE() AND table_name='rt_task_instance'
     AND index_name='idx_job_task_mode_create';
  SELECT COUNT(*) INTO replacement_index_count FROM information_schema.statistics
   WHERE table_schema=DATABASE() AND table_name='rt_task_instance'
     AND index_name='idx_task_instance_task_mode_create';
  IF index_count>0 AND replacement_index_count=0 THEN
    ALTER TABLE rt_task_instance RENAME INDEX idx_job_task_mode_create TO idx_task_instance_task_mode_create;
  END IF;

  SELECT COUNT(*) INTO index_count FROM information_schema.statistics
   WHERE table_schema=DATABASE() AND table_name='rt_task_instance'
     AND index_name='idx_job_yarn_application';
  SELECT COUNT(*) INTO replacement_index_count FROM information_schema.statistics
   WHERE table_schema=DATABASE() AND table_name='rt_task_instance'
     AND index_name='idx_task_instance_yarn_application';
  IF index_count>0 AND replacement_index_count=0 THEN
    ALTER TABLE rt_task_instance RENAME INDEX idx_job_yarn_application TO idx_task_instance_yarn_application;
  END IF;

  SELECT COUNT(*) INTO index_count FROM information_schema.statistics
   WHERE table_schema=DATABASE() AND table_name='rt_task_operation'
     AND index_name='idx_task_operation_instance';
  IF index_count=0 THEN
    ALTER TABLE rt_task_operation ADD KEY idx_task_operation_instance
      (task_instance_id, create_time);
  END IF;

  SELECT COUNT(*) INTO index_count FROM information_schema.statistics
   WHERE table_schema=DATABASE() AND table_name='rt_task_change_log'
     AND index_name='idx_task_change_log_instance';
  IF index_count=0 THEN
    ALTER TABLE rt_task_change_log ADD KEY idx_task_change_log_instance
      (task_instance_id);
  END IF;
END$$
DELIMITER ;
CALL upgrade_unified_task_instance();
DROP PROCEDURE upgrade_unified_task_instance;
