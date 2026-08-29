ALTER TABLE sql_task
  ADD COLUMN ddl_content LONGTEXT NULL COMMENT '当前生效的表结构变更 DDL，仅保存不自动执行' AFTER sql_content;

ALTER TABLE sql_task_version
  ADD COLUMN ddl_content LONGTEXT NULL COMMENT '版本内表结构变更 DDL，仅保存不自动执行' AFTER sql_content;
