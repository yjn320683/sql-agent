SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS rt_project (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '项目ID',
  project_name VARCHAR(128) NOT NULL COMMENT '项目名称',
  enabled_flag TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_rt_project_name (project_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='实时同步项目';

CREATE TABLE IF NOT EXISTS rt_task (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '任务ID',
  project_id BIGINT NOT NULL COMMENT '所属项目ID',
  task_name VARCHAR(180) NOT NULL COMMENT '任务名称',
  task_type VARCHAR(16) NOT NULL DEFAULT 'sync' COMMENT '首期固定为sync',
  flink_version VARCHAR(32) NULL,
  owner VARCHAR(64) NOT NULL,
  description VARCHAR(1024) NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'not_running',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_rt_task_name (project_id, task_name),
  KEY idx_rt_task_list (task_type, status, update_time),
  KEY idx_rt_task_owner (owner)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='实时同步任务';

CREATE TABLE IF NOT EXISTS rt_task_version (
  id BIGINT NOT NULL AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  version_no INT NOT NULL,
  config LONGTEXT NOT NULL COMMENT '完整同步配置JSON',
  operator VARCHAR(64) NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_rt_task_version (task_id, version_no),
  KEY idx_rt_task_version_current (task_id, version_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同步任务版本';

CREATE TABLE IF NOT EXISTS rt_sync_task_config (
  task_id BIGINT NOT NULL,
  source_type VARCHAR(32) NOT NULL DEFAULT 'mysql-cdc',
  source_server_id BIGINT NOT NULL,
  target_database VARCHAR(128) NOT NULL,
  config_json LONGTEXT NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (task_id),
  KEY idx_sync_config_server (source_server_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='实时同步配置';

CREATE TABLE IF NOT EXISTS rt_sync_task_table_mapping (
  id BIGINT NOT NULL AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  source_server_id BIGINT NOT NULL,
  source_database VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  source_table VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  target_database VARCHAR(128) NOT NULL,
  target_table VARCHAR(128) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_sync_mapping_task_order (task_id, sort_order),
  UNIQUE KEY uk_sync_mapping_source (source_server_id, source_table),
  KEY idx_sync_mapping_server (source_server_id, task_id),
  KEY idx_sync_mapping_source_table (task_id, source_table),
  KEY idx_sync_mapping_target_table (task_id, target_table)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='源表与Paimon表映射';

CREATE TABLE IF NOT EXISTS rt_task_param (
  id BIGINT NOT NULL AUTO_INCREMENT,
  task_type VARCHAR(16) NOT NULL,
  param_type VARCHAR(32) NOT NULL,
  param_key VARCHAR(128) NOT NULL,
  key_desc VARCHAR(255) NOT NULL,
  param_value LONGTEXT NULL,
  value_type VARCHAR(16) NOT NULL,
  input_type VARCHAR(32) NOT NULL DEFAULT 'select',
  min_value DECIMAL(18,6) NULL,
  max_value DECIMAL(18,6) NULL,
  step_value DECIMAL(18,6) NULL,
  precision_value INT NULL,
  required_flag TINYINT(1) NOT NULL DEFAULT 0,
  enabled_flag TINYINT(1) NOT NULL DEFAULT 1,
  sort_order DECIMAL(10,4) NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_task_param_key (task_type, param_type, param_key),
  KEY idx_task_param_type (task_type, param_type, enabled_flag, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同步动态参数';

CREATE TABLE IF NOT EXISTS rt_server (
  id BIGINT NOT NULL AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL,
  type VARCHAR(32) NOT NULL DEFAULT 'mysql',
  address VARCHAR(512) NOT NULL,
  database_name VARCHAR(128) NULL,
  database_prefix VARCHAR(16) NULL COMMENT '数据库前缀，选填；填写时只允许1至9位小写字母',
  account VARCHAR(128) NULL,
  password VARCHAR(512) NULL,
  description VARCHAR(512) NULL,
  operator VARCHAR(64) NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_server_type_database_identity (type, database_name, database_prefix)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同步MySQL Server';

CREATE TABLE IF NOT EXISTS rt_task_instance (
  id BIGINT NOT NULL AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  version_id BIGINT NULL,
  job_id VARCHAR(128) NULL,
  yarn_application_id VARCHAR(128) NULL,
  status VARCHAR(32) NULL,
  execution_mode VARCHAR(16) NOT NULL DEFAULT 'PRODUCTION',
  managed_flag TINYINT(1) NOT NULL DEFAULT 1 COMMENT '0=历史导入只读，1=本平台管理',
  startup_log LONGTEXT NULL,
  effective_config_snapshot_json LONGTEXT NULL,
  savepoint_path VARCHAR(1024) NULL,
  tracking_url VARCHAR(1024) NULL,
  failure_message VARCHAR(2000) NULL,
  last_runtime_log LONGTEXT NULL,
  started_at DATETIME NULL,
  ended_at DATETIME NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_task_instance_managed_status (managed_flag, status, task_id),
  KEY idx_task_instance_task_status (task_id, status),
  KEY idx_task_instance_task_mode_status (task_id, execution_mode, status),
  KEY idx_task_instance_status_task (status, task_id),
  KEY idx_task_instance_task_mode_create (task_id, execution_mode, create_time),
  KEY idx_task_instance_task_create (task_id, create_time),
  KEY idx_task_instance_yarn_application (yarn_application_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同步任务运行实例';

CREATE TABLE IF NOT EXISTS rt_task_operation (
  id BIGINT NOT NULL AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  task_instance_id BIGINT NULL,
  operation_type VARCHAR(32) NOT NULL,
  operation_status VARCHAR(32) NOT NULL,
  operator VARCHAR(64) NOT NULL,
  request_json LONGTEXT NULL,
  result_json LONGTEXT NULL,
  error_message LONGTEXT NULL,
  start_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deadline_at DATETIME NOT NULL,
  end_time DATETIME NULL,
  active_flag TINYINT(1) NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_task_active_operation (task_id, active_flag),
  KEY idx_task_operation_task (task_id, create_time),
  KEY idx_task_operation_instance (task_instance_id, create_time),
  KEY idx_task_operation_deadline (operation_status, deadline_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同步任务操作上下文';

CREATE TABLE IF NOT EXISTS rt_task_change_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  task_id BIGINT NULL,
  operation_id BIGINT NULL,
  before_version_id BIGINT NULL,
  after_version_id BIGINT NULL,
  task_instance_id BIGINT NULL,
  operator VARCHAR(64) NOT NULL,
  action VARCHAR(64) NULL,
  detail TEXT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_task_change_log_task (task_id, create_time),
  KEY idx_task_change_log_operation (operation_id),
  KEY idx_task_change_log_before_version (before_version_id),
  KEY idx_task_change_log_after_version (after_version_id),
  KEY idx_task_change_log_instance (task_instance_id),
  KEY idx_task_change_log_task_id (task_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同步任务变更记录';

CREATE TABLE IF NOT EXISTS rt_alert (
  id BIGINT NOT NULL AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  severity VARCHAR(32) NULL,
  status VARCHAR(32) NULL,
  title VARCHAR(255) NULL,
  detail TEXT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_alert_status (status, severity),
  KEY idx_alert_task (task_id, status, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同步任务告警';

CREATE TABLE IF NOT EXISTS rt_paimon_business_domain (
  id BIGINT NOT NULL AUTO_INCREMENT,
  domain_code VARCHAR(32) NOT NULL,
  domain_name VARCHAR(64) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  enabled_flag TINYINT(1) NOT NULL DEFAULT 1,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_paimon_business_domain_code (domain_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同步业务域';

INSERT IGNORE INTO rt_project (id,project_name,enabled_flag) VALUES (1,'默认项目',1);

INSERT IGNORE INTO rt_paimon_business_domain
  (domain_code,domain_name,sort_order,enabled_flag)
VALUES
  ('c2b','c2b',10,1),
  ('b2b','b2b',20,1);

INSERT IGNORE INTO rt_task_param
  (task_type,param_type,param_key,key_desc,param_value,value_type,input_type,required_flag,enabled_flag,sort_order)
VALUES
  ('sync','mysql_conf','scan.snapshot.fetch.size','快照单次拉取行数','[{"label":"128","value":"128"},{"label":"256","value":"256"},{"label":"512","value":"512"},{"label":"1024（官网默认）","value":"1024","default":true}]','list','select',1,1,10),
  ('sync','mysql_conf','scan.incremental.snapshot.chunk.size','快照分片行数','[{"label":"1024","value":"1024"},{"label":"2048","value":"2048"},{"label":"4096","value":"4096"},{"label":"8096（官网默认）","value":"8096","default":true}]','list','select',1,1,20),
  ('sync','table_conf','bucket','目标Paimon表Bucket',NULL,'number','input_number',1,1,10),
  ('sync','table_conf','sink.parallelism','目标Paimon表Sink并行度',NULL,'number','input_number',1,1,20),
  ('sync','table_conf','changelog-producer','目标Paimon表 Changelog Producer','[{"label":"none（官网默认）","value":"none","default":true},{"label":"input","value":"input"},{"label":"lookup","value":"lookup"},{"label":"full-compaction","value":"full-compaction"}]','list','select',1,1,30),
  ('sync','table_conf','dynamic-bucket.target-row-num','动态 Bucket 目标行数','2000000','number','input_number',0,1,42),
  ('sync','table_conf','consumer.expiration-time','Consumer 过期时间','1 d','string','input',1,1,104),
  ('sync','flink_conf','taskmanager.memory.managed.fraction','TaskManager Managed Memory 比例','0.4','number','input_number',1,1,10),
  ('sync','flink_conf','taskmanager.memory.network.fraction','TaskManager Network Memory 比例','0.1','number','input_number',1,1,20),
  ('sync','flink_conf','taskmanager.memory.network.min','TaskManager Network Memory 最小值','64mb','string','input',0,1,30),
  ('sync','flink_conf','taskmanager.memory.network.max','TaskManager Network Memory 最大值','1gb','string','input',0,1,40),
  ('sync','flink_conf','taskmanager.memory.jvm-overhead.fraction','TaskManager JVM Overhead 比例','0.1','number','input_number',0,1,50),
  ('sync','flink_conf','taskmanager.memory.jvm-overhead.min','TaskManager JVM Overhead 最小值','192mb','string','input',0,1,60),
  ('sync','flink_conf','taskmanager.memory.jvm-overhead.max','TaskManager JVM Overhead 最大值','1gb','string','input',0,1,70),
  ('sync','flink_conf','taskmanager.memory.task.off-heap.size','TaskManager Task Off-Heap Memory','0b','string','input',0,1,80),
  ('sync','flink_conf','taskmanager.memory.managed.size','TaskManager Managed Memory 固定大小',NULL,'string','input',0,1,90),
  ('sync','flink_conf','table.exec.state.ttl','Table 状态 TTL',NULL,'string','input',0,1,180),
  ('sync','flink_conf','table.exec.mini-batch.enabled','MiniBatch 开关','[{"label":"false（官网默认）","value":"false","default":true},{"label":"true","value":"true"}]','list','select',0,1,181),
  ('sync','flink_conf','table.exec.mini-batch.allow-latency','MiniBatch 允许延迟',NULL,'string','input',0,1,182),
  ('sync','flink_conf','table.exec.mini-batch.size','MiniBatch 批量大小',NULL,'number','input_number',0,1,183),
  ('sync','flink_conf','table.exec.source.idle-timeout','Source 空闲超时',NULL,'string','input',0,1,184),
  ('sync','flink_conf','table.local-time-zone','Table 时区','Asia/Shanghai','string','input',0,1,185),
  ('sync','flink_conf','yarn.application-attempts','YARN AM 最大尝试次数（含首次）',NULL,'number','input_number',0,1,190),
  ('sync','flink_conf','high-availability.type','高可用模式','[{"label":"zookeeper","value":"zookeeper","default":true}]','list','select',0,1,200),
  ('sync','flink_conf','high-availability.storageDir','HA 元数据共享存储 URI（需与 ZooKeeper HA 一起配置）',NULL,'string','input',0,1,201),
  ('sync','flink_conf','high-availability.zookeeper.quorum','ZooKeeper Quorum（需与 ZooKeeper HA 一起配置）',NULL,'string','input',0,1,202),
  ('sync','flink_conf','high-availability.zookeeper.path.root','ZooKeeper 根路径（默认 /flink）','/flink','string','input',0,1,203);

UPDATE rt_task_param SET min_value=0,max_value=1,step_value=0.01,precision_value=2
 WHERE task_type='sync' AND param_type='flink_conf'
   AND param_key IN ('taskmanager.memory.managed.fraction','taskmanager.memory.network.fraction','taskmanager.memory.jvm-overhead.fraction');
UPDATE rt_task_param SET min_value=1,step_value=1,precision_value=0
 WHERE task_type='sync' AND param_type='flink_conf'
   AND param_key IN ('table.exec.mini-batch.size','yarn.application-attempts');
UPDATE rt_task_param SET min_value=-2,max_value=2147483647,step_value=1,precision_value=0
 WHERE task_type='sync' AND param_type='table_conf' AND param_key='bucket';
UPDATE rt_task_param SET min_value=1,max_value=128,step_value=1,precision_value=0
 WHERE task_type='sync' AND param_type='table_conf' AND param_key='sink.parallelism';
UPDATE rt_task_param SET min_value=100000,step_value=100000,precision_value=0
 WHERE task_type='sync' AND param_type='table_conf' AND param_key='dynamic-bucket.target-row-num';
