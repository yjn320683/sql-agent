-- 第9批：数据地图解析状态与 Neo4j 可靠投影。可重复执行。
CREATE TABLE IF NOT EXISTS data_map_lineage_run (
  id BIGINT NOT NULL AUTO_INCREMENT,
  run_type VARCHAR(16) NOT NULL COMMENT 'INCREMENTAL/FULL/REPROJECT',
  status VARCHAR(16) NOT NULL COMMENT 'RUNNING/SUCCEEDED/PARTIAL/FAILED',
  scanned_count INT NOT NULL DEFAULT 0,
  success_count INT NOT NULL DEFAULT 0,
  partial_count INT NOT NULL DEFAULT 0,
  failed_count INT NOT NULL DEFAULT 0,
  projected_count INT NOT NULL DEFAULT 0,
  generation_no BIGINT NULL,
  error_message VARCHAR(2000) NULL,
  started_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  finished_at DATETIME NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_data_map_run_time (run_type,started_at),
  KEY idx_data_map_run_status (status,started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据地图血缘解析与投影运行记录';

CREATE TABLE IF NOT EXISTS data_map_lineage_task_state (
  task_scope VARCHAR(16) NOT NULL,
  task_id BIGINT NOT NULL,
  version_id BIGINT NULL,
  version_no INT NOT NULL,
  generation_no BIGINT NOT NULL DEFAULT 1,
  snapshot_id BIGINT NULL,
  sql_checksum CHAR(64) NOT NULL,
  parse_status VARCHAR(16) NOT NULL COMMENT 'COMPLETE/PARTIAL/FAILED',
  projection_status VARCHAR(16) NOT NULL COMMENT 'PENDING/PROJECTED/FAILED',
  diagnostic_count INT NOT NULL DEFAULT 0,
  graph_revision BIGINT NOT NULL DEFAULT 0,
  last_parsed_at DATETIME NULL,
  last_projected_at DATETIME NULL,
  last_error VARCHAR(2000) NULL,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (task_scope,task_id),
  KEY idx_data_map_state_projection (projection_status,update_time),
  KEY idx_data_map_state_snapshot (snapshot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='当前生产任务血缘解析与图投影状态';

CREATE TABLE IF NOT EXISTS data_map_graph_outbox (
  id BIGINT NOT NULL AUTO_INCREMENT,
  snapshot_id BIGINT NOT NULL,
  task_scope VARCHAR(16) NOT NULL,
  task_id BIGINT NOT NULL,
  version_id BIGINT NULL,
  version_no INT NOT NULL,
  generation_no BIGINT NOT NULL DEFAULT 1,
  event_type VARCHAR(24) NOT NULL DEFAULT 'UPSERT_CURRENT',
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/SUCCEEDED/FAILED/SUPERSEDED',
  attempts INT NOT NULL DEFAULT 0,
  available_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  locked_by VARCHAR(64) NULL,
  locked_at DATETIME NULL,
  last_error VARCHAR(2000) NULL,
  projected_at DATETIME NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_data_map_outbox_snapshot (snapshot_id,event_type,generation_no),
  KEY idx_data_map_outbox_claim (status,available_at,id),
  KEY idx_data_map_outbox_generation (generation_no,status,id),
  KEY idx_data_map_outbox_task (task_scope,task_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MySQL血缘事实到Neo4j的可靠投影事件';

CREATE TABLE IF NOT EXISTS data_map_projection_generation (
  id BIGINT NOT NULL AUTO_INCREMENT,
  generation_no BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL COMMENT 'BUILDING/ACTIVE/RETIRED/FAILED',
  expected_count INT NOT NULL DEFAULT 0,
  projected_count INT NOT NULL DEFAULT 0,
  activated_at DATETIME NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_data_map_generation_no (generation_no),
  KEY idx_data_map_generation_status (status,generation_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Neo4j全量投影代次';
