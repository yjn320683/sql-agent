SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS rt_alert_rule (
  id BIGINT NOT NULL AUTO_INCREMENT, rule_code VARCHAR(64) NOT NULL, rule_name VARCHAR(128) NOT NULL,
  event_type VARCHAR(64) NOT NULL, severity VARCHAR(32) NOT NULL, enabled_flag TINYINT(1) NOT NULL DEFAULT 1,
  threshold_value BIGINT NULL, consecutive_samples INT NOT NULL DEFAULT 1, window_seconds INT NOT NULL DEFAULT 300,
  description VARCHAR(512) NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id), UNIQUE KEY uk_alert_rule_code (rule_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='实时告警内置规则';

CREATE TABLE IF NOT EXISTS rt_alert_rule_state (
  rule_id BIGINT NOT NULL, task_id BIGINT NOT NULL, task_instance_id BIGINT NOT NULL DEFAULT 0,
  consecutive_count INT NOT NULL DEFAULT 0, last_condition_met TINYINT(1) NOT NULL DEFAULT 0,
  last_value VARCHAR(128) NULL, evidence_json LONGTEXT NULL, last_evaluated_at DATETIME NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (rule_id,task_id,task_instance_id), KEY idx_alert_rule_state_task (task_id,last_evaluated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='实时告警规则连续采样状态';

DELIMITER $$
DROP PROCEDURE IF EXISTS upgrade_alert_closure_20260916$$
CREATE PROCEDURE upgrade_alert_closure_20260916()
BEGIN
  DECLARE column_count INT DEFAULT 0;
  DECLARE index_count INT DEFAULT 0;

  SELECT COUNT(*) INTO column_count FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='rt_alert' AND column_name='task_instance_id';
  IF column_count=0 THEN ALTER TABLE rt_alert ADD COLUMN task_instance_id BIGINT NULL AFTER task_id; END IF;
  SELECT COUNT(*) INTO column_count FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='rt_alert' AND column_name='rule_id';
  IF column_count=0 THEN ALTER TABLE rt_alert ADD COLUMN rule_id BIGINT NULL AFTER task_instance_id; END IF;
  SELECT COUNT(*) INTO column_count FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='rt_alert' AND column_name='event_type';
  IF column_count=0 THEN ALTER TABLE rt_alert ADD COLUMN event_type VARCHAR(64) NOT NULL DEFAULT 'RUNTIME_EVENT' AFTER rule_id; END IF;
  SELECT COUNT(*) INTO column_count FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='rt_alert' AND column_name='fingerprint';
  IF column_count=0 THEN ALTER TABLE rt_alert ADD COLUMN fingerprint CHAR(64) NULL AFTER detail; END IF;
  SELECT COUNT(*) INTO column_count FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='rt_alert' AND column_name='active_fingerprint';
  IF column_count=0 THEN ALTER TABLE rt_alert ADD COLUMN active_fingerprint CHAR(64) NULL AFTER fingerprint; END IF;
  SELECT COUNT(*) INTO column_count FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='rt_alert' AND column_name='occurrence_count';
  IF column_count=0 THEN ALTER TABLE rt_alert ADD COLUMN occurrence_count INT NOT NULL DEFAULT 1 AFTER active_fingerprint; END IF;
  SELECT COUNT(*) INTO column_count FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='rt_alert' AND column_name='first_occurred_at';
  IF column_count=0 THEN ALTER TABLE rt_alert ADD COLUMN first_occurred_at DATETIME NULL AFTER occurrence_count; END IF;
  SELECT COUNT(*) INTO column_count FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='rt_alert' AND column_name='last_occurred_at';
  IF column_count=0 THEN ALTER TABLE rt_alert ADD COLUMN last_occurred_at DATETIME NULL AFTER first_occurred_at; END IF;
  SELECT COUNT(*) INTO column_count FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='rt_alert' AND column_name='acknowledged_by';
  IF column_count=0 THEN ALTER TABLE rt_alert ADD COLUMN acknowledged_by VARCHAR(64) NULL AFTER last_occurred_at; END IF;
  SELECT COUNT(*) INTO column_count FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='rt_alert' AND column_name='acknowledged_at';
  IF column_count=0 THEN ALTER TABLE rt_alert ADD COLUMN acknowledged_at DATETIME NULL AFTER acknowledged_by; END IF;
  SELECT COUNT(*) INTO column_count FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='rt_alert' AND column_name='muted_until';
  IF column_count=0 THEN ALTER TABLE rt_alert ADD COLUMN muted_until DATETIME NULL AFTER acknowledged_at; END IF;
  SELECT COUNT(*) INTO column_count FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='rt_alert' AND column_name='recovered_at';
  IF column_count=0 THEN ALTER TABLE rt_alert ADD COLUMN recovered_at DATETIME NULL AFTER muted_until; END IF;
  SELECT COUNT(*) INTO column_count FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='rt_alert' AND column_name='evidence_json';
  IF column_count=0 THEN ALTER TABLE rt_alert ADD COLUMN evidence_json LONGTEXT NULL AFTER recovered_at; END IF;

  UPDATE rt_alert SET status=CASE LOWER(status) WHEN 'acknowledged' THEN 'ACKNOWLEDGED' WHEN 'muted' THEN 'MUTED' WHEN 'recovered' THEN 'RECOVERED' ELSE 'OPEN' END;
  UPDATE rt_alert SET fingerprint=SHA2(CONCAT(task_id,'|LEGACY|',COALESCE(title,'')),256),
    first_occurred_at=COALESCE(first_occurred_at,create_time),last_occurred_at=COALESCE(last_occurred_at,update_time),occurrence_count=GREATEST(1,occurrence_count);
  UPDATE rt_alert a JOIN (SELECT task_id,title,MAX(id) keep_id FROM rt_alert WHERE status IN ('OPEN','ACKNOWLEDGED','MUTED') GROUP BY task_id,title HAVING COUNT(*)>1) d
    ON d.task_id=a.task_id AND d.title=a.title AND a.id<>d.keep_id
    SET a.status='RECOVERED',a.recovered_at=NOW(),a.active_fingerprint=NULL;
  UPDATE rt_alert SET active_fingerprint=IF(status IN ('OPEN','ACKNOWLEDGED','MUTED'),fingerprint,NULL);
  ALTER TABLE rt_alert MODIFY COLUMN status VARCHAR(32) NOT NULL DEFAULT 'OPEN', MODIFY COLUMN fingerprint CHAR(64) NOT NULL,
    MODIFY COLUMN first_occurred_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, MODIFY COLUMN last_occurred_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP;

  SELECT COUNT(*) INTO index_count FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='rt_alert' AND index_name='uk_alert_active_fingerprint';
  IF index_count=0 THEN ALTER TABLE rt_alert ADD UNIQUE KEY uk_alert_active_fingerprint(active_fingerprint); END IF;
  SELECT COUNT(*) INTO index_count FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='rt_alert' AND index_name='idx_alert_rule_status';
  IF index_count=0 THEN ALTER TABLE rt_alert ADD KEY idx_alert_rule_status(rule_id,status,last_occurred_at); END IF;
  SELECT COUNT(*) INTO index_count FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='rt_alert' AND index_name='idx_alert_instance';
  IF index_count=0 THEN ALTER TABLE rt_alert ADD KEY idx_alert_instance(task_instance_id,last_occurred_at); END IF;
END$$
DELIMITER ;
CALL upgrade_alert_closure_20260916();
DROP PROCEDURE upgrade_alert_closure_20260916;

INSERT INTO rt_alert_rule(rule_code,rule_name,event_type,severity,threshold_value,consecutive_samples,window_seconds,description)
VALUES
  ('TASK_FAILURE','任务运行失败','TASK_FAILURE','critical',1,1,300,'正式实例进入失败状态'),
  ('FREQUENT_RESTART','频繁重启','FREQUENT_RESTART','warning',3,1,600,'Flink 作业重启次数达到阈值'),
  ('CHECKPOINT_FAILURE','Checkpoint 连续失败','CHECKPOINT_FAILURE','critical',1,3,300,'最新 Checkpoint 失败且连续采样达到阈值'),
  ('BACKPRESSURE','持续反压','BACKPRESSURE','warning',800,3,300,'最大反压毫秒/秒达到阈值'),
  ('SOURCE_LAG','源端延迟','SOURCE_LAG','warning',300000,3,300,'源端读取延迟达到阈值'),
  ('DIRTY_DATA','脏数据','DIRTY_DATA','warning',1,1,300,'存在未处理脏数据'),
  ('SCHEMA_CHANGE','Schema 变化','SCHEMA_CHANGE','warning',1,1,300,'存在待处理或阻断的 Schema 变化')
ON DUPLICATE KEY UPDATE rule_name=VALUES(rule_name),event_type=VALUES(event_type),description=VALUES(description);
