-- 第7批：不可变血缘快照与现有业务域资产化。脚本可重复执行。
CREATE TABLE IF NOT EXISTS task_lineage_snapshot (
  id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '血缘快照ID',
  task_scope        VARCHAR(16)  NOT NULL COMMENT 'OFFLINE或REALTIME',
  task_id           BIGINT       NOT NULL COMMENT '任务ID',
  version_id        BIGINT       NULL COMMENT '任务版本记录ID',
  version_no        INT          NOT NULL COMMENT '任务版本号；未版本化当前代码为0',
  sql_checksum      CHAR(64)     NOT NULL COMMENT 'SQL或配置SHA-256',
  dialect           VARCHAR(16)  NOT NULL COMMENT 'HIVE、TRINO或FLINK',
  default_database  VARCHAR(128) NOT NULL DEFAULT 'default' COMMENT '解析默认数据库',
  parser_version    VARCHAR(32)  NOT NULL COMMENT '解析器版本',
  snapshot_source   VARCHAR(16)  NOT NULL COMMENT 'SAVED或BACKFILLED',
  complete_flag     TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '解析是否完整',
  lineage_json      LONGTEXT     NOT NULL COMMENT '不含SQL正文的血缘事实JSON',
  diagnostics_json  LONGTEXT     NOT NULL COMMENT '解析诊断JSON',
  create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_lineage_snapshot_fact
    (task_scope, task_id, version_no, sql_checksum, default_database),
  KEY idx_lineage_snapshot_version (task_scope, task_id, version_no, id),
  KEY idx_lineage_snapshot_checksum (sql_checksum)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='离线与实时任务版本不可变血缘快照';

DELIMITER $$
DROP PROCEDURE IF EXISTS upgrade_lineage_domain_assets_20260917$$
CREATE PROCEDURE upgrade_lineage_domain_assets_20260917()
BEGIN
  DECLARE column_count INT DEFAULT 0;
  SELECT COUNT(*) INTO column_count FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='rt_paimon_business_domain' AND column_name='description';
  IF column_count=0 THEN
    ALTER TABLE rt_paimon_business_domain ADD COLUMN description VARCHAR(512) NULL COMMENT '业务域说明' AFTER domain_name;
  END IF;
  SELECT COUNT(*) INTO column_count FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='rt_paimon_business_domain' AND column_name='owner';
  IF column_count=0 THEN
    ALTER TABLE rt_paimon_business_domain ADD COLUMN owner VARCHAR(64) NULL COMMENT '业务域负责人' AFTER description;
  END IF;
  SELECT COUNT(*) INTO column_count FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='rt_paimon_business_domain' AND column_name='disabled_time';
  IF column_count=0 THEN
    ALTER TABLE rt_paimon_business_domain ADD COLUMN disabled_time DATETIME NULL COMMENT '停用时间' AFTER enabled_flag;
  END IF;
END$$
DELIMITER ;
CALL upgrade_lineage_domain_assets_20260917();
DROP PROCEDURE upgrade_lineage_domain_assets_20260917;

CREATE TABLE IF NOT EXISTS rt_asset_business_domain_relation (
  id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '资产业务域关联ID',
  asset_type        VARCHAR(16)  NOT NULL COMMENT 'HIVE或PAIMON',
  asset_key         VARCHAR(512) NOT NULL COMMENT '规范化资产唯一键',
  asset_key_hash    CHAR(64)     NOT NULL COMMENT '资产唯一键SHA-256',
  realtime_table_id BIGINT       NULL COMMENT 'PAIMON实时表登记ID',
  catalog_name      VARCHAR(128) NULL COMMENT 'Catalog',
  database_name     VARCHAR(128) NOT NULL COMMENT '数据库',
  table_name        VARCHAR(128) NOT NULL COMMENT '表名',
  domain_id         BIGINT       NOT NULL COMMENT '业务域ID',
  updated_by        VARCHAR(64)  NOT NULL COMMENT '最后操作人',
  create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_asset_business_domain (asset_type, asset_key_hash),
  KEY idx_asset_domain_id (domain_id, asset_type),
  KEY idx_asset_realtime_table (realtime_table_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Hive与Paimon资产的业务域归属';
