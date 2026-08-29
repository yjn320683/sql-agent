-- 仅供现有环境人工执行。执行前请先备份并确认字段不存在。
ALTER TABLE sql_task
  ADD COLUMN task_type VARCHAR(32) NOT NULL DEFAULT 'RUN_HIVE' COMMENT '任务类型' AFTER description,
  ADD COLUMN execution_frequency VARCHAR(128) NOT NULL DEFAULT '手动执行' COMMENT '执行频率描述' AFTER task_type,
  ADD COLUMN owner VARCHAR(64) NOT NULL DEFAULT '' COMMENT '负责人' AFTER execution_frequency,
  ADD COLUMN enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '任务状态：0停用，1启用' AFTER owner,
  ADD KEY idx_sql_task_enabled (enabled);

UPDATE sql_task SET owner = created_by WHERE owner = '';
