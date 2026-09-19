-- 第 10 批：为 PROCESSING 租约回收查询增加索引。通过 information_schema 保持新旧环境幂等。
SET @recovery_index_exists = (
  SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'data_map_graph_outbox'
    AND index_name = 'idx_data_map_outbox_recovery'
);
SET @recovery_index_sql = IF(
  @recovery_index_exists > 0,
  'SELECT 1',
  'ALTER TABLE data_map_graph_outbox ADD INDEX idx_data_map_outbox_recovery(status, locked_at, available_at)'
);
PREPARE recovery_index_statement FROM @recovery_index_sql;
EXECUTE recovery_index_statement;
DEALLOCATE PREPARE recovery_index_statement;
