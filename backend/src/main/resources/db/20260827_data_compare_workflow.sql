-- 验数计划、报告审计与联合版本。仅增量建表/加字段，不包含任何删表操作。
CREATE TABLE IF NOT EXISTS data_compare_plan (
  plan_token                  VARCHAR(64)   NOT NULL COMMENT '不可变验数计划令牌',
  task_id                     BIGINT        NOT NULL COMMENT '任务ID',
  baseline_version_no         INT           NULL COMMENT '当前生效版本；NULL表示初始代码',
  candidate_version_no        INT           NOT NULL COMMENT '候选版本',
  baseline_checksum           CHAR(64)      NOT NULL COMMENT '基线代码及DDL校验和',
  candidate_checksum          CHAR(64)      NOT NULL COMMENT '候选代码及DDL校验和',
  union_id                    BIGINT        NULL COMMENT '联合版本ID',
  union_ddl_checksum          CHAR(64)      NULL COMMENT '联合DDL校验和',
  original_baseline_sql       LONGTEXT      NOT NULL,
  original_candidate_sql      LONGTEXT      NOT NULL,
  generated_baseline_sql      LONGTEXT      NOT NULL,
  generated_candidate_sql     LONGTEXT      NOT NULL,
  baseline_steps              LONGTEXT      NOT NULL COMMENT '依赖闭包后的Step JSON',
  candidate_steps             LONGTEXT      NOT NULL COMMENT '依赖闭包后的Step JSON',
  table_mappings              LONGTEXT      NOT NULL COMMENT '可选输出表映射JSON',
  temporary_tables            LONGTEXT      NOT NULL COMMENT '调测表JSON',
  operator_ob_id              VARCHAR(20)   NOT NULL,
  consumed_job_id             BIGINT        NULL,
  expires_at                  DATETIME      NOT NULL,
  create_time                 DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (plan_token),
  KEY idx_compare_plan_task (task_id, candidate_version_no, create_time),
  KEY idx_compare_plan_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='版本验数不可变执行计划';

ALTER TABLE data_compare_job_detail
  ADD COLUMN plan_token VARCHAR(64) NULL COMMENT '不可变验数计划令牌' AFTER compare_type,
  ADD COLUMN baseline_checksum CHAR(64) NULL COMMENT '提交时基线校验和' AFTER candidate_version_no,
  ADD COLUMN candidate_checksum CHAR(64) NULL COMMENT '提交时候选校验和' AFTER baseline_checksum,
  ADD COLUMN union_id BIGINT NULL COMMENT '联合版本ID' AFTER candidate_checksum,
  ADD COLUMN union_ddl_checksum CHAR(64) NULL COMMENT '联合DDL校验和' AFTER union_id,
  ADD UNIQUE KEY uk_compare_plan_token (plan_token),
  ADD KEY idx_compare_union_member (union_id, task_id, candidate_version_no, create_time);

ALTER TABLE data_compare_tbl_verify
  ADD COLUMN baseline_source_tbl_name VARCHAR(512) NULL COMMENT '基线原始输出表' AFTER original_tbl_name,
  ADD COLUMN candidate_source_tbl_name VARCHAR(512) NULL COMMENT '候选原始输出表' AFTER baseline_source_tbl_name,
  ADD COLUMN rule_revision BIGINT NOT NULL DEFAULT 1 COMMENT '规则修订号' AFTER compare_rule,
  ADD COLUMN verified_rule_revision BIGINT NULL COMMENT '最近完成验数的规则修订号' AFTER rule_revision,
  ADD COLUMN result_stale TINYINT(1) NOT NULL DEFAULT 0 COMMENT '规则变化后需重新验数' AFTER verified_rule_revision,
  ADD COLUMN rerun_count INT NOT NULL DEFAULT 0 COMMENT '单表重跑次数' AFTER result_stale,
  ADD COLUMN force_pass TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否强制通过' AFTER rerun_count,
  ADD COLUMN force_reason VARCHAR(1000) NULL COMMENT '强制通过原因' AFTER force_pass,
  ADD COLUMN force_operator_ob_id VARCHAR(20) NULL COMMENT '强制通过操作人' AFTER force_reason,
  ADD COLUMN force_time DATETIME NULL COMMENT '强制通过时间' AFTER force_operator_ob_id;

CREATE TABLE IF NOT EXISTS sql_task_version_union (
  id                          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '联合版本ID',
  union_ddl                   LONGTEXT      NULL COMMENT '所有成员共享DDL',
  union_ddl_checksum          CHAR(64)      NOT NULL COMMENT '联合DDL校验和',
  status                      VARCHAR(24)   NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT、PUBLISHING、PUBLISHED、PUBLISH_FAILED、STALE',
  revision                    BIGINT        NOT NULL DEFAULT 1 COMMENT '乐观锁修订号',
  error_message               VARCHAR(4000) NULL COMMENT '发布失败摘要',
  created_by                  VARCHAR(20)   NOT NULL,
  updated_by                  VARCHAR(20)   NOT NULL,
  published_by                VARCHAR(20)   NULL,
  published_time              DATETIME      NULL,
  create_time                 DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time                 DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_version_union_status (status, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务联合版本';

CREATE TABLE IF NOT EXISTS sql_task_version_union_member (
  id                          BIGINT       NOT NULL AUTO_INCREMENT,
  union_id                    BIGINT       NOT NULL,
  task_id                     BIGINT       NOT NULL,
  version_no                  INT          NOT NULL,
  version_revision            BIGINT       NOT NULL COMMENT '联合保存时成员修订号',
  version_checksum            CHAR(64)     NOT NULL COMMENT '成员SQL及DDL校验和',
  baseline_checksum           CHAR(64)     NOT NULL COMMENT '成员当前生效基线校验和',
  latest_compare_job_id       BIGINT       NULL COMMENT '完全匹配当前内容的最新验数任务',
  create_time                 DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time                 DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_union_task (union_id, task_id),
  UNIQUE KEY uk_union_task_version (task_id, version_no),
  KEY idx_union_member_job (latest_compare_job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='联合版本成员';

CREATE TABLE IF NOT EXISTS sql_task_version_union_publish_log (
  id                          BIGINT        NOT NULL AUTO_INCREMENT,
  union_id                    BIGINT        NOT NULL,
  statement_order             INT           NOT NULL,
  statement_checksum          CHAR(64)      NOT NULL,
  ddl_statement               LONGTEXT      NOT NULL,
  status                      VARCHAR(16)   NOT NULL COMMENT 'PENDING、SUCCEEDED、FAILED、SKIPPED',
  error_message               VARCHAR(4000) NULL,
  operator_ob_id              VARCHAR(20)   NOT NULL,
  started_at                  DATETIME      NULL,
  finished_at                 DATETIME      NULL,
  create_time                 DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_union_statement (union_id, statement_checksum),
  KEY idx_union_publish_log (union_id, statement_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='联合发布DDL逐条审计';
