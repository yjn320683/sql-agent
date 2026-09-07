-- 离线开发平台增强：版本检查摘要、结构化调度、依赖 DAG、重试和补数。
-- 执行前请先备份数据库；所有表均为增量创建，不修改现有任务和实例数据。

CREATE TABLE IF NOT EXISTS sql_task_version_check (
  id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '检查记录ID',
  task_id         BIGINT        NOT NULL COMMENT '任务ID',
  version_no      INT           NOT NULL COMMENT '版本号',
  version_revision BIGINT       NOT NULL COMMENT '执行检查时的草稿修订号',
  version_checksum CHAR(64)     NOT NULL COMMENT '执行检查时的代码校验和',
  check_type      VARCHAR(16)   NOT NULL COMMENT 'VALIDATE、QUALITY、EXPLAIN',
  status          VARCHAR(32)   NOT NULL COMMENT '检查状态',
  passed          TINYINT(1)    NULL COMMENT '是否通过',
  complete        TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '结果是否完整',
  error_count     INT           NOT NULL DEFAULT 0 COMMENT '错误数',
  warning_count   INT           NOT NULL DEFAULT 0 COMMENT '警告数',
  duration_ms     BIGINT        NULL COMMENT '耗时毫秒',
  result_summary  VARCHAR(1024) NULL COMMENT '摘要',
  result_payload  LONGTEXT      NULL COMMENT '完整结果JSON',
  checked_by      VARCHAR(20)   NOT NULL COMMENT '检查人obId',
  checked_at      DATETIME      NOT NULL COMMENT '检查时间',
  create_time     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_task_version_check (task_id, version_no, check_type),
  KEY idx_version_check_time (task_id, version_no, checked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务版本最近一次检查结果';

CREATE TABLE IF NOT EXISTS sql_task_schedule (
  id                     BIGINT        NOT NULL AUTO_INCREMENT COMMENT '调度配置ID',
  task_id                BIGINT        NOT NULL COMMENT '任务ID',
  schedule_type          VARCHAR(16)   NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL、CRON',
  cron_expression        VARCHAR(128)  NULL COMMENT 'Spring六段Cron表达式',
  timezone               VARCHAR(64)   NOT NULL DEFAULT 'Asia/Shanghai' COMMENT '时区',
  enabled                TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '是否启用',
  concurrency_policy     VARCHAR(16)   NOT NULL DEFAULT 'FORBID' COMMENT 'FORBID、ALLOW',
  max_retries            INT           NOT NULL DEFAULT 0 COMMENT '最大重试次数',
  retry_interval_seconds INT           NOT NULL DEFAULT 60 COMMENT '重试间隔秒数',
  parameter_values       LONGTEXT      NULL COMMENT '固定运行参数JSON',
  next_trigger_time      DATETIME      NULL COMMENT '下一触发时间',
  last_trigger_time      DATETIME      NULL COMMENT '上次触发时间',
  last_run_status        VARCHAR(16)   NULL COMMENT '上次调度运行状态',
  revision               BIGINT        NOT NULL DEFAULT 1 COMMENT '乐观锁版本号',
  created_by             VARCHAR(20)   NOT NULL,
  updated_by             VARCHAR(20)   NOT NULL,
  create_time            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_schedule_task (task_id),
  KEY idx_schedule_due (enabled, next_trigger_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='离线任务结构化调度配置';

CREATE TABLE IF NOT EXISTS sql_task_dependency (
  id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '依赖ID',
  task_id           BIGINT       NOT NULL COMMENT '下游任务ID',
  upstream_task_id  BIGINT       NOT NULL COMMENT '上游任务ID',
  dependency_type   VARCHAR(16)  NOT NULL DEFAULT 'SUCCESS' COMMENT 'SUCCESS、COMPLETED',
  created_by        VARCHAR(20)  NOT NULL,
  create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_task_upstream (task_id, upstream_task_id),
  KEY idx_dependency_upstream (upstream_task_id, task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='离线任务依赖DAG';

CREATE TABLE IF NOT EXISTS sql_task_schedule_run (
  id                 BIGINT        NOT NULL AUTO_INCREMENT COMMENT '调度运行ID',
  schedule_id        BIGINT        NULL COMMENT '调度配置ID，手工补数可为空',
  task_id            BIGINT        NOT NULL COMMENT '任务ID',
  trigger_type       VARCHAR(16)   NOT NULL COMMENT 'CRON、MANUAL、BACKFILL、RETRY',
  scheduled_time     DATETIME      NOT NULL COMMENT '计划触发时间',
  business_date      DATE          NULL COMMENT '业务日期',
  status             VARCHAR(16)   NOT NULL COMMENT 'WAITING、SUBMITTED、RETRYING、RETRIED、SKIPPED、FAILED、CANCELLED、SUCCEEDED',
  attempt_no         INT           NOT NULL DEFAULT 1 COMMENT '第几次尝试',
  execution_id       BIGINT        NULL COMMENT '执行实例ID',
  backfill_batch_id  BIGINT        NULL COMMENT '补数批次ID',
  parameter_values   LONGTEXT      NULL COMMENT '本次参数JSON',
  message            VARCHAR(1024) NULL COMMENT '调度说明或失败原因',
  created_by         VARCHAR(20)   NOT NULL,
  create_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_schedule_run_task_time (task_id, scheduled_time),
  KEY idx_schedule_run_status (status, update_time),
  KEY idx_schedule_run_execution (execution_id),
  KEY idx_schedule_run_backfill (backfill_batch_id, business_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='离线任务调度运行记录';

CREATE TABLE IF NOT EXISTS sql_task_backfill_batch (
  id                 BIGINT        NOT NULL AUTO_INCREMENT COMMENT '补数批次ID',
  task_id            BIGINT        NOT NULL COMMENT '任务ID',
  start_date         DATE          NOT NULL COMMENT '开始业务日期',
  end_date           DATE          NOT NULL COMMENT '结束业务日期',
  status             VARCHAR(16)   NOT NULL COMMENT 'PENDING、RUNNING、SUCCEEDED、PARTIAL_FAILED、FAILED、CANCELLED',
  total_count        INT           NOT NULL DEFAULT 0,
  submitted_count    INT           NOT NULL DEFAULT 0,
  succeeded_count    INT           NOT NULL DEFAULT 0,
  failed_count       INT           NOT NULL DEFAULT 0,
  parameter_values   LONGTEXT      NULL COMMENT '补数公共参数JSON',
  requested_by       VARCHAR(20)   NOT NULL,
  create_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_backfill_task_time (task_id, create_time),
  KEY idx_backfill_status (status, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='离线任务补数批次';
