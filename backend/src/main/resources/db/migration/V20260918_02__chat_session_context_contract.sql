-- 补齐页面 AI 会话上下文字段。每个变更均先检查 information_schema，兼容已手工升级的环境。
SET @context_type_exists = (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='chat_session' AND column_name='context_type'
);
SET @context_type_sql = IF(
  @context_type_exists > 0,
  'SELECT 1',
  'ALTER TABLE chat_session ADD COLUMN context_type VARCHAR(40) NULL COMMENT ''页面 AI 业务上下文类型'' AFTER title'
);
PREPARE context_type_statement FROM @context_type_sql;
EXECUTE context_type_statement;
DEALLOCATE PREPARE context_type_statement;

SET @context_id_exists = (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='chat_session' AND column_name='context_id'
);
SET @context_id_sql = IF(
  @context_id_exists > 0,
  'SELECT 1',
  'ALTER TABLE chat_session ADD COLUMN context_id VARCHAR(100) NULL COMMENT ''页面 AI 业务实体 ID'' AFTER context_type'
);
PREPARE context_id_statement FROM @context_id_sql;
EXECUTE context_id_statement;
DEALLOCATE PREPARE context_id_statement;

SET @context_title_exists = (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='chat_session' AND column_name='context_title'
);
SET @context_title_sql = IF(
  @context_title_exists > 0,
  'SELECT 1',
  'ALTER TABLE chat_session ADD COLUMN context_title VARCHAR(200) NULL COMMENT ''页面来源标题'' AFTER context_id'
);
PREPARE context_title_statement FROM @context_title_sql;
EXECUTE context_title_statement;
DEALLOCATE PREPARE context_title_statement;

SET @context_index_exists = (
  SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema=DATABASE() AND table_name='chat_session' AND index_name='idx_chat_session_context'
);
SET @context_index_sql = IF(
  @context_index_exists > 0,
  'SELECT 1',
  'ALTER TABLE chat_session ADD INDEX idx_chat_session_context(ob_id,context_type,context_id,last_active_at)'
);
PREPARE context_index_statement FROM @context_index_sql;
EXECUTE context_index_statement;
DEALLOCATE PREPARE context_index_statement;
