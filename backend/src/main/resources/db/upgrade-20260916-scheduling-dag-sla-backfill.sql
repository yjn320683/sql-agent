-- 第 5 批：调度 SLA 与补数明细。可重复执行。
SET @db = DATABASE();

-- 调度能力在旧环境可能尚未部署。先补齐基础表，再对已存在的旧表增量补列。
CREATE TABLE IF NOT EXISTS sql_task_schedule (
  id BIGINT NOT NULL AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  schedule_type VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
  cron_expression VARCHAR(128) NULL,
  timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
  enabled TINYINT(1) NOT NULL DEFAULT 0,
  concurrency_policy VARCHAR(16) NOT NULL DEFAULT 'FORBID',
  max_retries INT NOT NULL DEFAULT 0,
  retry_interval_seconds INT NOT NULL DEFAULT 60,
  execution_timeout_seconds INT NOT NULL DEFAULT 0,
  sla_duration_minutes INT NOT NULL DEFAULT 0,
  timeout_policy VARCHAR(16) NOT NULL DEFAULT 'ALERT_ONLY',
  parameter_values LONGTEXT NULL,
  next_trigger_time DATETIME NULL,
  last_trigger_time DATETIME NULL,
  last_run_status VARCHAR(16) NULL,
  revision BIGINT NOT NULL DEFAULT 1,
  created_by VARCHAR(20) NOT NULL,
  updated_by VARCHAR(20) NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_schedule_task (task_id),
  KEY idx_schedule_due (enabled,next_trigger_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='离线任务结构化调度配置';

CREATE TABLE IF NOT EXISTS sql_task_dependency (
  id BIGINT NOT NULL AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  upstream_task_id BIGINT NOT NULL,
  dependency_type VARCHAR(16) NOT NULL DEFAULT 'SUCCESS',
  created_by VARCHAR(20) NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_task_upstream (task_id,upstream_task_id),
  KEY idx_dependency_upstream (upstream_task_id,task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='离线任务依赖DAG';

CREATE TABLE IF NOT EXISTS sql_task_schedule_run (
  id BIGINT NOT NULL AUTO_INCREMENT,
  schedule_id BIGINT NULL,
  task_id BIGINT NOT NULL,
  trigger_type VARCHAR(16) NOT NULL,
  scheduled_time DATETIME NOT NULL,
  business_date DATE NULL,
  status VARCHAR(16) NOT NULL,
  attempt_no INT NOT NULL DEFAULT 1,
  execution_id BIGINT NULL,
  backfill_batch_id BIGINT NULL,
  parameter_values LONGTEXT NULL,
  message VARCHAR(1024) NULL,
  created_by VARCHAR(20) NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_schedule_run_task_time (task_id,scheduled_time),
  KEY idx_schedule_run_status (status,update_time),
  KEY idx_schedule_run_execution (execution_id),
  KEY idx_schedule_run_backfill (backfill_batch_id,business_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='离线任务调度运行记录';

CREATE TABLE IF NOT EXISTS sql_task_backfill_batch (
  id BIGINT NOT NULL AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  start_date DATE NOT NULL,
  end_date DATE NOT NULL,
  status VARCHAR(16) NOT NULL,
  total_count INT NOT NULL DEFAULT 0,
  submitted_count INT NOT NULL DEFAULT 0,
  succeeded_count INT NOT NULL DEFAULT 0,
  failed_count INT NOT NULL DEFAULT 0,
  max_concurrency INT NOT NULL DEFAULT 3,
  parameter_values LONGTEXT NULL,
  requested_by VARCHAR(20) NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_backfill_task_time (task_id,create_time),
  KEY idx_backfill_status (status,update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='离线任务补数批次';

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='sql_task_schedule' AND COLUMN_NAME='execution_timeout_seconds')=0,
  'ALTER TABLE sql_task_schedule ADD COLUMN execution_timeout_seconds INT NOT NULL DEFAULT 0 AFTER retry_interval_seconds', 'SELECT 1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='sql_task_schedule' AND COLUMN_NAME='sla_duration_minutes')=0,
  'ALTER TABLE sql_task_schedule ADD COLUMN sla_duration_minutes INT NOT NULL DEFAULT 0 AFTER execution_timeout_seconds', 'SELECT 1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='sql_task_schedule' AND COLUMN_NAME='timeout_policy')=0,
  "ALTER TABLE sql_task_schedule ADD COLUMN timeout_policy VARCHAR(16) NOT NULL DEFAULT 'ALERT_ONLY' AFTER sla_duration_minutes", 'SELECT 1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='sql_task_backfill_batch' AND COLUMN_NAME='max_concurrency')=0,
  'ALTER TABLE sql_task_backfill_batch ADD COLUMN max_concurrency INT NOT NULL DEFAULT 3 AFTER failed_count', 'SELECT 1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

CREATE TABLE IF NOT EXISTS sql_task_backfill_item (
  id BIGINT NOT NULL AUTO_INCREMENT,
  batch_id BIGINT NOT NULL,
  task_id BIGINT NOT NULL,
  business_date DATE NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  execution_id BIGINT NULL,
  attempt_no INT NOT NULL DEFAULT 0,
  message VARCHAR(1024) NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_backfill_batch_date (batch_id,business_date),
  KEY idx_backfill_item_claim (batch_id,status,business_date),
  KEY idx_backfill_item_execution (execution_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='离线补数逐日执行项';

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='sql_task_execution' AND INDEX_NAME='idx_task_execution_success_sample')=0,
  'ALTER TABLE sql_task_execution ADD KEY idx_task_execution_success_sample (task_id,status,id)', 'SELECT 1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
