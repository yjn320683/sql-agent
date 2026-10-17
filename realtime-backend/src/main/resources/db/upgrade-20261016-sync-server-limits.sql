-- 每个 Server 的全量/增量任务分别限额；未配置时每类默认 1 个。
CREATE TABLE IF NOT EXISTS rt_sync_server_task_limit (
  source_server_id BIGINT NOT NULL,
  sync_mode VARCHAR(32) NOT NULL DEFAULT 'full_incremental',
  max_task_count INT NOT NULL DEFAULT 1,
  PRIMARY KEY (source_server_id, sync_mode)
);
