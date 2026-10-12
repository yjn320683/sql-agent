CREATE TABLE IF NOT EXISTS rt_sync_ddl_exception (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  event_id CHAR(36) NOT NULL,
  task_id BIGINT NOT NULL,
  server_id BIGINT NOT NULL,
  database_name VARCHAR(128) NOT NULL,
  table_name VARCHAR(128) NOT NULL,
  ddl_sql TEXT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_sync_ddl_event (task_id, event_id),
  KEY idx_sync_ddl_active (task_id, status, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
