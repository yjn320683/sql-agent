SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS rt_sync_progress_snapshot (
  id BIGINT NOT NULL AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  task_instance_id BIGINT NOT NULL,
  snapshot_finished INT NULL,
  snapshot_remaining INT NULL,
  snapshot_progress DECIMAL(8,5) NULL,
  source_lag_ms BIGINT NULL,
  source_idle_ms BIGINT NULL,
  dirty_record_count BIGINT NOT NULL DEFAULT 0,
  offset_summary VARCHAR(1024) NULL,
  metric_payload LONGTEXT NULL,
  observed_at DATETIME NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_sync_progress_instance (task_instance_id),
  KEY idx_sync_progress_task_time (task_id,observed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='实时同步实例最近进度快照';

CREATE TABLE IF NOT EXISTS rt_sync_dirty_record (
  id BIGINT NOT NULL AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  task_instance_id BIGINT NULL,
  source_database VARCHAR(128) NULL,
  source_table VARCHAR(128) NULL,
  operation_type VARCHAR(16) NULL,
  primary_key_value VARCHAR(1024) NULL,
  error_code VARCHAR(128) NULL,
  error_message VARCHAR(2000) NOT NULL,
  raw_payload LONGTEXT NULL,
  resolved_flag TINYINT(1) NOT NULL DEFAULT 0,
  resolved_by VARCHAR(64) NULL,
  resolved_at DATETIME NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_dirty_task_status (task_id,resolved_flag,create_time),
  KEY idx_dirty_instance (task_instance_id,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='实时同步脏数据记录';

CREATE TABLE IF NOT EXISTS rt_schema_change_event (
  id BIGINT NOT NULL AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  realtime_table_id BIGINT NOT NULL,
  source_database VARCHAR(128) NOT NULL,
  source_table VARCHAR(128) NOT NULL,
  target_database VARCHAR(128) NOT NULL,
  target_table VARCHAR(128) NOT NULL,
  change_type VARCHAR(32) NOT NULL COMMENT 'ADD_COLUMNS、INCOMPATIBLE',
  status VARCHAR(16) NOT NULL COMMENT 'PENDING、APPLIED、BLOCKED、IGNORED',
  change_signature CHAR(64) NOT NULL,
  change_payload LONGTEXT NOT NULL,
  detected_at DATETIME NOT NULL,
  applied_by VARCHAR(64) NULL,
  applied_at DATETIME NULL,
  message VARCHAR(2000) NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_schema_change_signature (task_id,realtime_table_id,change_signature),
  KEY idx_schema_change_task_status (task_id,status,detected_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='实时同步Schema演进事件';
