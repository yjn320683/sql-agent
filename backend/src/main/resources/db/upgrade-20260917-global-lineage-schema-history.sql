SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS task_lineage_relation (
  id BIGINT NOT NULL AUTO_INCREMENT,
  snapshot_id BIGINT NOT NULL,
  relation_signature CHAR(64) NOT NULL,
  task_scope VARCHAR(16) NOT NULL,
  task_id BIGINT NOT NULL,
  version_id BIGINT NULL,
  version_no INT NOT NULL,
  statement_index INT NULL,
  relation_kind VARCHAR(32) NOT NULL,
  source_catalog VARCHAR(64) NULL,
  source_database VARCHAR(128) NULL,
  source_table VARCHAR(128) NULL,
  source_column VARCHAR(128) NULL,
  target_catalog VARCHAR(64) NULL,
  target_database VARCHAR(128) NULL,
  target_table VARCHAR(128) NULL,
  target_column VARCHAR(128) NULL,
  usage_type VARCHAR(32) NULL,
  direct_flag TINYINT(1) NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_lineage_relation_signature (snapshot_id,relation_signature),
  KEY idx_lineage_relation_task (task_scope,task_id,version_no,relation_kind),
  KEY idx_lineage_relation_source (source_catalog,source_database,source_table,source_column),
  KEY idx_lineage_relation_target (target_catalog,target_database,target_table,target_column)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务版本规范化表字段血缘索引';

CREATE TABLE IF NOT EXISTS rt_realtime_table_schema_version (
  id BIGINT NOT NULL AUTO_INCREMENT,
  realtime_table_id BIGINT NOT NULL,
  version_no INT NOT NULL,
  schema_fingerprint CHAR(64) NOT NULL,
  change_source VARCHAR(32) NOT NULL,
  compatibility VARCHAR(16) NOT NULL,
  schema_json LONGTEXT NOT NULL,
  diff_json LONGTEXT NOT NULL,
  source_event_id BIGINT NULL,
  operator VARCHAR(64) NOT NULL,
  first_seen_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_seen_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_rt_table_schema_version (realtime_table_id,version_no),
  UNIQUE KEY uk_rt_table_schema_fingerprint (realtime_table_id,schema_fingerprint),
  KEY idx_rt_table_schema_time (realtime_table_id,last_seen_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='受管实时表不可变Schema版本';

-- 现有受管表只建立当前登记结构基线，不伪造历史版本。
INSERT INTO rt_realtime_table_schema_version(
  realtime_table_id,version_no,schema_fingerprint,change_source,compatibility,
  schema_json,diff_json,operator,first_seen_at,last_seen_at)
SELECT r.id,1,SHA2(CONCAT_WS('|',r.catalog_name,r.database_name,r.table_name,
       COALESCE(r.table_comment,''),COALESCE(r.table_options_json,'{}'),
       COALESCE((SELECT GROUP_CONCAT(CONCAT_WS(':',c.sort_order,c.column_name,c.data_type,
         c.nullable_flag,c.primary_key_flag,c.partition_key_flag,COALESCE(c.column_comment,''))
         ORDER BY c.sort_order SEPARATOR '|') FROM rt_realtime_table_column c
         WHERE c.realtime_table_id=r.id),'')),256),
       'BACKFILLED_BASELINE','BASELINE',
       JSON_OBJECT('comment',COALESCE(r.table_comment,''),'options',CAST(COALESCE(r.table_options_json,'{}') AS JSON),
         'columns',(SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT('name',c2.column_name,'dataType',c2.data_type,
           'nullable',c2.nullable_flag,'primaryKey',c2.primary_key_flag,'partitionKey',c2.partition_key_flag,
           'comment',COALESCE(c2.column_comment,''),'sortOrder',c2.sort_order)),JSON_ARRAY())
           FROM rt_realtime_table_column c2 WHERE c2.realtime_table_id=r.id)),
       JSON_OBJECT('addedColumns',JSON_ARRAY(),'removedColumns',JSON_ARRAY(),'modifiedColumns',JSON_ARRAY(),
         'optionChanges',JSON_ARRAY(),'commentChanged',FALSE),
       COALESCE(r.operator,'system'),COALESCE(r.create_time,NOW()),COALESCE(r.last_synced_at,r.update_time,NOW())
FROM rt_realtime_table r
WHERE NOT EXISTS (SELECT 1 FROM rt_realtime_table_schema_version v WHERE v.realtime_table_id=r.id);
