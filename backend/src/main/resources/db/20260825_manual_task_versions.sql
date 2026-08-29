ALTER TABLE sql_task_version
  ADD COLUMN version_note VARCHAR(512) NULL COMMENT '手动版本备注；历史自动快照允许为空' AFTER change_type;

ALTER TABLE sql_task_execution
  ADD COLUMN source_type VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT '执行来源：DRAFT、VERSION' AFTER sql_snapshot,
  ADD COLUMN task_version_no INT NULL COMMENT '执行版本号，草稿执行时为空' AFTER source_type;

ALTER TABLE sql_task_execution
  ALTER COLUMN source_type DROP DEFAULT;
