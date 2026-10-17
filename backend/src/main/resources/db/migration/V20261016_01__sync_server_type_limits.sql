-- Server 的全量、增量任务独立计数和限额。任务配置字段保存在原有 config_json 中。
CREATE TABLE IF NOT EXISTS rt_sync_server_task_limit (
  source_server_id BIGINT NOT NULL,
  sync_mode VARCHAR(32) NOT NULL DEFAULT 'full_incremental',
  max_task_count INT NOT NULL DEFAULT 1,
  PRIMARY KEY (source_server_id, sync_mode)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
