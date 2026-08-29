-- 仅供现有环境人工执行。执行前请先备份并确认字段不存在。
ALTER TABLE sql_task
  ADD COLUMN parameter_schema LONGTEXT NULL COMMENT '类型化运行参数定义 JSON' AFTER sql_content,
  ADD COLUMN sql_checksum CHAR(64) NOT NULL DEFAULT '' COMMENT '当前草稿 SHA-256 校验和' AFTER parameter_schema,
  ADD COLUMN revision BIGINT NOT NULL DEFAULT 1 COMMENT '草稿乐观锁版本号' AFTER sql_checksum,
  ADD COLUMN draft_base_version_no INT NULL COMMENT '当前草稿基于的版本号' AFTER revision,
  ADD COLUMN archived TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否归档：0否，1是' AFTER draft_base_version_no,
  ADD COLUMN archived_by VARCHAR(20) NULL COMMENT '归档操作人 obId' AFTER archived,
  ADD COLUMN archived_time DATETIME NULL COMMENT '归档时间' AFTER archived_by,
  ADD KEY idx_sql_task_status_time (archived, update_time),
  ADD KEY idx_sql_task_updated_by (updated_by);

UPDATE sql_task SET sql_checksum = SHA2(CONCAT(sql_content, '\n[]'), 256) WHERE sql_checksum = '';

ALTER TABLE sql_task_version
  ADD COLUMN base_version_no INT NULL COMMENT '创建该版本时草稿所基于的版本号' AFTER version_no,
  ADD COLUMN parameter_schema_snapshot LONGTEXT NULL COMMENT '类型化运行参数定义 JSON 快照' AFTER sql_snapshot,
  ADD COLUMN sql_checksum CHAR(64) NOT NULL DEFAULT '' COMMENT '版本内容 SHA-256 校验和' AFTER parameter_schema_snapshot,
  ADD KEY idx_task_version_base (task_id, base_version_no);

UPDATE sql_task_version SET sql_checksum = SHA2(CONCAT(sql_snapshot, '\n[]'), 256) WHERE sql_checksum = '';

ALTER TABLE sql_task_execution
  ADD COLUMN parameter_schema_snapshot LONGTEXT NULL COMMENT '提交时类型化参数定义 JSON 快照' AFTER sql_snapshot,
  ADD COLUMN rendered_sql_snapshot LONGTEXT NULL COMMENT '参数渲染后的 SQL 快照' AFTER parameter_schema_snapshot,
  ADD COLUMN parameter_values LONGTEXT NULL COMMENT '本次运行参数 JSON' AFTER rendered_sql_snapshot,
  ADD COLUMN business_date DATE NULL COMMENT '旧日期表达式的计算基准日期' AFTER parameter_values,
  ADD COLUMN task_revision BIGINT NULL COMMENT '草稿执行时的revision' AFTER task_version_no,
  ADD COLUMN current_step_no INT NULL COMMENT '当前正在执行的Step编号' AFTER status,
  ADD COLUMN total_steps INT NOT NULL DEFAULT 0 COMMENT 'Step总数' AFTER current_step_no,
  ADD COLUMN succeeded_steps INT NOT NULL DEFAULT 0 COMMENT '成功Step数' AFTER total_steps,
  ADD COLUMN failed_step_no INT NULL COMMENT '失败Step编号' AFTER succeeded_steps,
  ADD KEY idx_task_execution_source (task_id, source_type, task_version_no);

CREATE TABLE IF NOT EXISTS sql_task_version_step (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '版本Step记录ID', task_id BIGINT NOT NULL COMMENT '任务ID',
  version_no INT NOT NULL COMMENT '任务版本号', step_no INT NOT NULL COMMENT 'Step编号',
  step_order INT NOT NULL COMMENT '执行顺序', step_name VARCHAR(128) NULL COMMENT 'Step显示名称',
  step_sql LONGTEXT NOT NULL COMMENT 'Step SQL快照', statement_type VARCHAR(32) NOT NULL COMMENT '语句类型',
  input_tables TEXT NULL COMMENT '输入表 JSON数组', output_tables TEXT NULL COMMENT '输出表 JSON数组',
  sql_checksum CHAR(64) NOT NULL COMMENT 'Step SQL SHA-256校验和', create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id), UNIQUE KEY uk_task_version_step_no (task_id, version_no, step_no),
  UNIQUE KEY uk_task_version_step_order (task_id, version_no, step_order), KEY idx_version_step_version (task_id, version_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SQL Agent任务版本Step快照表';

CREATE TABLE IF NOT EXISTS sql_task_execution_step (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '执行Step记录ID', execution_id BIGINT NOT NULL COMMENT '执行实例ID',
  task_id BIGINT NOT NULL COMMENT '任务ID', step_no INT NOT NULL COMMENT 'Step编号', step_order INT NOT NULL COMMENT '执行顺序',
  step_name VARCHAR(128) NULL COMMENT 'Step显示名称', source_sql_snapshot LONGTEXT NOT NULL COMMENT '参数渲染前Step SQL',
  rendered_sql_snapshot LONGTEXT NOT NULL COMMENT '实际提交Hive的Step SQL', status VARCHAR(16) NOT NULL COMMENT 'Step状态',
  query_id VARCHAR(256) NULL, application_ids TEXT NULL, job_ids TEXT NULL, error_message VARCHAR(4000) NULL,
  log_file VARCHAR(512) NULL, started_at DATETIME NULL, finished_at DATETIME NULL,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id), UNIQUE KEY uk_execution_step_order (execution_id, step_order),
  UNIQUE KEY uk_execution_step_no (execution_id, step_no), KEY idx_execution_step_status (execution_id, status),
  KEY idx_execution_step_task (task_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SQL Agent Hive任务执行Step表';
