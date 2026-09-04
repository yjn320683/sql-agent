SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS rt_realtime_table (
  id BIGINT NOT NULL AUTO_INCREMENT,
  catalog_name VARCHAR(64) NOT NULL DEFAULT 'paimon', database_name VARCHAR(128) NOT NULL,
  table_name VARCHAR(128) NOT NULL, table_comment VARCHAR(512) NULL,
  table_type VARCHAR(32) NOT NULL DEFAULT 'primary_key', creation_source VARCHAR(32) NOT NULL DEFAULT 'manual',
  producer_task_id BIGINT NULL, physical_status VARCHAR(32) NOT NULL DEFAULT 'declared',
  table_options_json LONGTEXT NOT NULL, last_error VARCHAR(2000) NULL, last_synced_at DATETIME NULL,
  operator VARCHAR(64) NOT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id), UNIQUE KEY uk_realtime_table_identity (catalog_name,database_name,table_name),
  KEY idx_realtime_table_status (physical_status,creation_source,update_time), KEY idx_realtime_table_producer (producer_task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='受管Paimon实时表';

CREATE TABLE IF NOT EXISTS rt_realtime_table_column (
  id BIGINT NOT NULL AUTO_INCREMENT, realtime_table_id BIGINT NOT NULL, column_name VARCHAR(128) NOT NULL,
  data_type VARCHAR(128) NOT NULL, nullable_flag TINYINT(1) NOT NULL DEFAULT 1,
  primary_key_flag TINYINT(1) NOT NULL DEFAULT 0, partition_key_flag TINYINT(1) NOT NULL DEFAULT 0,
  column_comment VARCHAR(512) NULL, sort_order INT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id), UNIQUE KEY uk_realtime_table_column (realtime_table_id,column_name),
  UNIQUE KEY uk_realtime_table_column_order (realtime_table_id,sort_order), KEY idx_realtime_column_table (realtime_table_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='受管实时表字段';

CREATE TABLE IF NOT EXISTS rt_task_table_reference (
  id BIGINT NOT NULL AUTO_INCREMENT, task_id BIGINT NOT NULL, realtime_table_id BIGINT NOT NULL,
  reference_role VARCHAR(16) NOT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id), UNIQUE KEY uk_task_table_reference (task_id,realtime_table_id,reference_role),
  KEY idx_task_table_reference_table (realtime_table_id,reference_role), KEY idx_task_table_reference_task (task_id,reference_role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='实时任务表依赖';

CREATE TABLE IF NOT EXISTS rt_compute_task_config (
  task_id BIGINT NOT NULL, default_database VARCHAR(128) NOT NULL, sql_text LONGTEXT NOT NULL,
  config_json LONGTEXT NOT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, PRIMARY KEY (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='实时计算任务配置';

CREATE TABLE IF NOT EXISTS rt_export_task_config (
  task_id BIGINT NOT NULL, source_database VARCHAR(128) NOT NULL, target_server_id BIGINT NOT NULL,
  config_json LONGTEXT NOT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (task_id), KEY idx_export_config_server (target_server_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='实时出仓任务配置';

CREATE TABLE IF NOT EXISTS rt_export_task_table_mapping (
  id BIGINT NOT NULL AUTO_INCREMENT, task_id BIGINT NOT NULL, realtime_table_id BIGINT NOT NULL,
  target_server_id BIGINT NOT NULL, target_database VARCHAR(128) NOT NULL, target_table VARCHAR(128) NOT NULL,
  column_mapping_json LONGTEXT NOT NULL, primary_keys_json LONGTEXT NOT NULL,
  write_mode VARCHAR(16) NOT NULL DEFAULT 'upsert', sort_order INT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id), UNIQUE KEY uk_export_mapping_order (task_id,sort_order),
  UNIQUE KEY uk_export_mapping_source (task_id,realtime_table_id),
  UNIQUE KEY uk_export_mapping_target (target_server_id,target_database,target_table),
  KEY idx_export_mapping_task (task_id), KEY idx_export_mapping_table (realtime_table_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='实时出仓表映射';

DELIMITER $$
DROP PROCEDURE IF EXISTS upgrade_realtime_tables_compute_export$$
CREATE PROCEDURE upgrade_realtime_tables_compute_export()
BEGIN
  DECLARE column_count INT DEFAULT 0;
  SELECT COUNT(*) INTO column_count FROM information_schema.columns
   WHERE table_schema=DATABASE() AND table_name='rt_sync_task_table_mapping' AND column_name='realtime_table_id';
  IF column_count=0 THEN
    ALTER TABLE rt_sync_task_table_mapping ADD COLUMN realtime_table_id BIGINT NULL COMMENT '受管实时表ID' AFTER target_table;
    ALTER TABLE rt_sync_task_table_mapping ADD KEY idx_sync_mapping_realtime_table (realtime_table_id);
  END IF;
END$$
DELIMITER ;
CALL upgrade_realtime_tables_compute_export();
DROP PROCEDURE upgrade_realtime_tables_compute_export;

-- 将升级前已经存在的同步映射登记为“待创建”；调度刷新会在物理表存在时补齐真实 Schema。
INSERT INTO rt_realtime_table(catalog_name,database_name,table_name,table_type,creation_source,
  producer_task_id,physical_status,table_options_json,operator)
SELECT 'paimon',m.target_database,m.target_table,'primary_key','sync',MIN(m.task_id),'declared','{}',
  COALESCE(MIN(t.owner),'system')
FROM rt_sync_task_table_mapping m
JOIN rt_task t ON t.id=m.task_id AND t.status<>'deleted'
GROUP BY m.target_database,m.target_table
ON DUPLICATE KEY UPDATE id=id;

UPDATE rt_sync_task_table_mapping m
JOIN rt_realtime_table r ON r.catalog_name='paimon' AND r.database_name=m.target_database
  AND r.table_name=m.target_table
SET m.realtime_table_id=r.id
WHERE m.realtime_table_id IS NULL;

UPDATE rt_realtime_table r
JOIN rt_sync_task_table_mapping m ON m.realtime_table_id=r.id
JOIN rt_task t ON t.id=m.task_id AND t.status<>'deleted'
SET r.creation_source='sync',r.producer_task_id=m.task_id,r.update_time=NOW()
WHERE r.producer_task_id IS NULL;

INSERT IGNORE INTO rt_task_table_reference(task_id,realtime_table_id,reference_role)
SELECT m.task_id,m.realtime_table_id,'OUTPUT'
FROM rt_sync_task_table_mapping m
JOIN rt_task t ON t.id=m.task_id AND t.status<>'deleted'
WHERE m.realtime_table_id IS NOT NULL;
