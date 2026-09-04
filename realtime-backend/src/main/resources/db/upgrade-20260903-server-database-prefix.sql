-- Server“库缩写”升级为选填“库前缀”，可重复执行。
SET @has_database_abbr = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rt_server' AND COLUMN_NAME = 'database_abbr'
);
SET @rename_database_prefix_sql = IF(
  @has_database_abbr > 0,
  'ALTER TABLE rt_server CHANGE COLUMN database_abbr database_prefix VARCHAR(16) NULL COMMENT ''数据库前缀，选填；填写时只允许1至9位小写字母''',
  'SELECT 1'
);
PREPARE rename_database_prefix_stmt FROM @rename_database_prefix_sql;
EXECUTE rename_database_prefix_stmt;
DEALLOCATE PREPARE rename_database_prefix_stmt;

SET @has_database_prefix = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rt_server' AND COLUMN_NAME = 'database_prefix'
);
SET @add_database_prefix_sql = IF(
  @has_database_prefix = 0,
  'ALTER TABLE rt_server ADD COLUMN database_prefix VARCHAR(16) NULL COMMENT ''数据库前缀，选填；填写时只允许1至9位小写字母'' AFTER database_name',
  'SELECT 1'
);
PREPARE add_database_prefix_stmt FROM @add_database_prefix_sql;
EXECUTE add_database_prefix_stmt;
DEALLOCATE PREPARE add_database_prefix_stmt;

SET @has_database_abbr_index = (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rt_server' AND INDEX_NAME = 'uk_server_database_abbr'
);
SET @drop_database_abbr_index_sql = IF(
  @has_database_abbr_index > 0,
  'ALTER TABLE rt_server DROP INDEX uk_server_database_abbr',
  'SELECT 1'
);
PREPARE drop_database_abbr_index_stmt FROM @drop_database_abbr_index_sql;
EXECUTE drop_database_abbr_index_stmt;
DEALLOCATE PREPARE drop_database_abbr_index_stmt;

SET @clear_legacy_database_prefix_sql = IF(
  @has_database_abbr > 0,
  'UPDATE rt_server SET database_prefix = CASE WHEN type = ''mysql'' THEN '''' ELSE NULL END',
  'SELECT 1'
);
PREPARE clear_legacy_database_prefix_stmt FROM @clear_legacy_database_prefix_sql;
EXECUTE clear_legacy_database_prefix_stmt;
DEALLOCATE PREPARE clear_legacy_database_prefix_stmt;

SET @has_database_identity_index = (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rt_server' AND INDEX_NAME = 'uk_server_type_database_identity'
);
SET @add_database_identity_index_sql = IF(
  @has_database_identity_index = 0,
  'ALTER TABLE rt_server ADD UNIQUE KEY uk_server_type_database_identity (type, database_name, database_prefix)',
  'SELECT 1'
);
PREPARE add_database_identity_stmt FROM @add_database_identity_index_sql;
EXECUTE add_database_identity_stmt;
DEALLOCATE PREPARE add_database_identity_stmt;
