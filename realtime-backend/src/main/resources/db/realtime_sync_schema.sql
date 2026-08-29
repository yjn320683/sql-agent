SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS rt_project (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '项目ID',
  project_name VARCHAR(128) NOT NULL COMMENT '项目名称',
  enabled_flag TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_rt_project_name (project_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='实时同步项目';

CREATE TABLE IF NOT EXISTS rt_task (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '任务ID',
  project_id BIGINT NOT NULL COMMENT '所属项目ID',
  task_name VARCHAR(180) NOT NULL COMMENT '任务名称',
  task_type VARCHAR(16) NOT NULL DEFAULT 'sync' COMMENT '首期固定为sync',
  flink_version VARCHAR(32) NULL,
  owner VARCHAR(64) NOT NULL,
  description VARCHAR(1024) NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'not_running',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_rt_task_name (project_id, task_name),
  KEY idx_rt_task_list (task_type, status, update_time),
  KEY idx_rt_task_owner (owner)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='实时同步任务';

CREATE TABLE IF NOT EXISTS rt_task_version (
  id BIGINT NOT NULL AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  version_no INT NOT NULL,
  config LONGTEXT NOT NULL COMMENT '完整同步配置JSON',
  operator VARCHAR(64) NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_rt_task_version (task_id, version_no),
  KEY idx_rt_task_version_current (task_id, version_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同步任务版本';

CREATE TABLE IF NOT EXISTS rt_sync_task_config (
  task_id BIGINT NOT NULL,
  source_type VARCHAR(32) NOT NULL DEFAULT 'mysql-cdc',
  source_server_id BIGINT NOT NULL,
  target_database VARCHAR(128) NOT NULL,
  config_json LONGTEXT NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (task_id),
  KEY idx_sync_config_server (source_server_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='实时同步配置';

CREATE TABLE IF NOT EXISTS rt_sync_task_table_mapping (
  id BIGINT NOT NULL AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  source_server_id BIGINT NOT NULL,
  source_database VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  source_table VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  target_database VARCHAR(128) NOT NULL,
  target_table VARCHAR(128) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_sync_mapping_task_order (task_id, sort_order),
  UNIQUE KEY uk_sync_mapping_source (source_server_id, source_table),
  KEY idx_sync_mapping_lookup (task_id, source_table, target_table)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='源表与Paimon表映射';

CREATE TABLE IF NOT EXISTS rt_task_param (
  id BIGINT NOT NULL AUTO_INCREMENT,
  task_type VARCHAR(16) NOT NULL,
  param_type VARCHAR(32) NOT NULL,
  param_key VARCHAR(128) NOT NULL,
  key_desc VARCHAR(255) NOT NULL,
  param_value LONGTEXT NULL,
  value_type VARCHAR(16) NOT NULL,
  input_type VARCHAR(32) NOT NULL DEFAULT 'select',
  min_value DECIMAL(18,6) NULL,
  max_value DECIMAL(18,6) NULL,
  step_value DECIMAL(18,6) NULL,
  precision_value INT NULL,
  required_flag TINYINT(1) NOT NULL DEFAULT 0,
  enabled_flag TINYINT(1) NOT NULL DEFAULT 1,
  sort_order DECIMAL(10,4) NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_task_param_key (task_type, param_type, param_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同步动态参数';

CREATE TABLE IF NOT EXISTS rt_server (
  id BIGINT NOT NULL AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL,
  type VARCHAR(32) NOT NULL DEFAULT 'mysql',
  address VARCHAR(512) NOT NULL,
  database_name VARCHAR(128) NULL,
  database_abbr VARCHAR(16) NOT NULL,
  account VARCHAR(128) NULL,
  password VARCHAR(512) NULL,
  description VARCHAR(512) NULL,
  operator VARCHAR(64) NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_server_database_abbr (database_abbr)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同步MySQL Server';

CREATE TABLE IF NOT EXISTS rt_job_instance (
  id BIGINT NOT NULL AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  version_id BIGINT NULL,
  job_id VARCHAR(128) NULL,
  yarn_application_id VARCHAR(128) NULL,
  status VARCHAR(32) NULL,
  execution_mode VARCHAR(16) NOT NULL DEFAULT 'PRODUCTION',
  managed_flag TINYINT(1) NOT NULL DEFAULT 1 COMMENT '0=历史导入只读，1=本平台管理',
  startup_log LONGTEXT NULL,
  effective_config_snapshot_json LONGTEXT NULL,
  savepoint_path VARCHAR(1024) NULL,
  tracking_url VARCHAR(1024) NULL,
  failure_message VARCHAR(2000) NULL,
  last_runtime_log LONGTEXT NULL,
  started_at DATETIME NULL,
  ended_at DATETIME NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_job_managed_status (managed_flag, status, task_id),
  KEY idx_job_task_mode_create (task_id, execution_mode, create_time),
  KEY idx_job_yarn_application (yarn_application_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同步任务运行实例';

CREATE TABLE IF NOT EXISTS rt_task_operation (
  id BIGINT NOT NULL AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  job_instance_id BIGINT NULL,
  operation_type VARCHAR(32) NOT NULL,
  operation_status VARCHAR(32) NOT NULL,
  operator VARCHAR(64) NOT NULL,
  request_json LONGTEXT NULL,
  result_json LONGTEXT NULL,
  error_message LONGTEXT NULL,
  start_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deadline_at DATETIME NOT NULL,
  end_time DATETIME NULL,
  active_flag TINYINT(1) NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_task_active_operation (task_id, active_flag),
  KEY idx_task_operation_task (task_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同步任务操作上下文';

CREATE TABLE IF NOT EXISTS rt_task_change_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  task_id BIGINT NULL,
  operation_id BIGINT NULL,
  before_version_id BIGINT NULL,
  after_version_id BIGINT NULL,
  job_instance_id BIGINT NULL,
  operator VARCHAR(64) NOT NULL,
  action VARCHAR(64) NULL,
  detail TEXT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_task_change_log_task (task_id, create_time),
  KEY idx_task_change_log_operation (operation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同步任务变更记录';

CREATE TABLE IF NOT EXISTS rt_alert (
  id BIGINT NOT NULL AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  severity VARCHAR(32) NULL,
  status VARCHAR(32) NULL,
  title VARCHAR(255) NULL,
  detail TEXT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_alert_status (status, severity),
  KEY idx_alert_task (task_id, status, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同步任务告警';

CREATE TABLE IF NOT EXISTS rt_paimon_business_domain (
  id BIGINT NOT NULL AUTO_INCREMENT,
  domain_code VARCHAR(32) NOT NULL,
  domain_name VARCHAR(64) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  enabled_flag TINYINT(1) NOT NULL DEFAULT 1,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_paimon_business_domain_code (domain_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同步业务域';
