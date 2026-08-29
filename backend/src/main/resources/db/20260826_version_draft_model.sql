ALTER TABLE sql_task
  CHANGE COLUMN draft_base_version_no effective_version_no INT NULL
    COMMENT '当前生效版本号；初始代码未由版本生效时为空';

ALTER TABLE sql_task_version
  CHANGE COLUMN base_version_no base_effective_version_no INT NULL
    COMMENT '创建草稿时所基于的生效版本号',
  ADD COLUMN base_effective_checksum CHAR(64) NOT NULL DEFAULT ''
    COMMENT '创建草稿时生效代码SHA-256，用于基线校验' AFTER base_effective_version_no,
  CHANGE COLUMN name_snapshot name VARCHAR(128) NOT NULL COMMENT '版本内任务名称',
  CHANGE COLUMN description_snapshot description VARCHAR(1024) NULL COMMENT '版本内任务描述',
  CHANGE COLUMN sql_snapshot sql_content LONGTEXT NOT NULL COMMENT '版本草稿Hive SQL',
  CHANGE COLUMN parameter_schema_snapshot parameter_schema LONGTEXT NULL
    COMMENT '版本草稿类型化运行参数定义JSON',
  ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
    COMMENT 'DRAFT、EFFECTIVE、HISTORICAL、STALE' AFTER version_note,
  ADD COLUMN revision BIGINT NOT NULL DEFAULT 1 COMMENT '版本草稿乐观锁版本号' AFTER status,
  CHANGE COLUMN operator_ob_id created_by VARCHAR(20) NOT NULL COMMENT '创建人obId',
  ADD COLUMN updated_by VARCHAR(20) NULL COMMENT '最后更新人obId' AFTER created_by,
  ADD COLUMN effective_by VARCHAR(20) NULL COMMENT '生效操作人obId' AFTER updated_by,
  ADD COLUMN effective_time DATETIME NULL COMMENT '生效时间' AFTER effective_by,
  ADD COLUMN update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
    COMMENT '更新时间' AFTER create_time;

UPDATE sql_task_version v
JOIN sql_task t ON t.id = v.task_id
SET v.base_effective_checksum = t.sql_checksum,
    v.version_note = COALESCE(NULLIF(TRIM(v.version_note), ''), CONCAT('历史版本 v', v.version_no)),
    v.status = 'HISTORICAL',
    v.updated_by = v.created_by,
    v.update_time = v.create_time;

UPDATE sql_task t
SET t.effective_version_no = (
  SELECT MAX(v.version_no)
  FROM sql_task_version v
  WHERE v.task_id = t.id AND v.sql_checksum = t.sql_checksum
);

UPDATE sql_task_version v
JOIN sql_task t ON t.id = v.task_id AND t.effective_version_no = v.version_no
SET v.status = 'EFFECTIVE',
    v.effective_by = v.updated_by,
    v.effective_time = v.update_time;

ALTER TABLE sql_task_version
  MODIFY COLUMN base_effective_checksum CHAR(64) NOT NULL
    COMMENT '创建草稿时生效代码SHA-256，用于基线校验',
  MODIFY COLUMN version_note VARCHAR(512) NOT NULL COMMENT '版本说明',
  MODIFY COLUMN updated_by VARCHAR(20) NOT NULL COMMENT '最后更新人obId',
  DROP COLUMN change_type,
  DROP COLUMN source_version_no,
  ADD KEY idx_task_version_status (task_id, status, update_time);

UPDATE sql_task_execution SET source_type = 'EFFECTIVE' WHERE source_type = 'DRAFT';

ALTER TABLE sql_task
  COMMENT = 'SQL Agent Hive任务及当前生效代码';

ALTER TABLE sql_task_version
  COMMENT = 'SQL Agent Hive可编辑版本草稿';

ALTER TABLE sql_task_version_step
  COMMENT = 'SQL Agent任务版本草稿Step表';
