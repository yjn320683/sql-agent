CREATE TABLE IF NOT EXISTS task_diagnostic_report (
  id BIGINT NOT NULL AUTO_INCREMENT,
  target_kind VARCHAR(32) NOT NULL COMMENT 'OFFLINE_EXECUTION或REALTIME_INSTANCE',
  target_id BIGINT NOT NULL COMMENT '执行或实例ID',
  revision INT NOT NULL COMMENT '报告修订号',
  report_status VARCHAR(16) NOT NULL COMMENT 'COMPLETE、PARTIAL或FAILED',
  complete_flag TINYINT(1) NOT NULL DEFAULT 0,
  failure_stage VARCHAR(32) NULL,
  summary VARCHAR(1024) NULL,
  report_json LONGTEXT NOT NULL,
  generated_at DATETIME NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_diagnostic_target_revision (target_kind,target_id,revision),
  KEY idx_diagnostic_target_latest (target_kind,target_id,generated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='离线与实时共用的不可变诊断报告';

DELIMITER $$
DROP PROCEDURE IF EXISTS upgrade_diagnostics_recovery_debug$$
CREATE PROCEDURE upgrade_diagnostics_recovery_debug()
BEGIN
  DECLARE column_count INT DEFAULT 0;
  DECLARE index_count INT DEFAULT 0;

  SELECT COUNT(*) INTO column_count FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='sql_task_execution' AND column_name='source_execution_id';
  IF column_count=0 THEN ALTER TABLE sql_task_execution ADD COLUMN source_execution_id BIGINT NULL COMMENT '快照重跑来源执行' AFTER task_revision; END IF;
  SELECT COUNT(*) INTO column_count FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='sql_task_execution' AND column_name='replay_strategy';
  IF column_count=0 THEN ALTER TABLE sql_task_execution ADD COLUMN replay_strategy VARCHAR(32) NULL COMMENT 'SNAPSHOT_REPLAY' AFTER source_execution_id; END IF;
  SELECT COUNT(*) INTO index_count FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='sql_task_execution' AND index_name='idx_task_execution_parent';
  IF index_count=0 THEN ALTER TABLE sql_task_execution ADD KEY idx_task_execution_parent (source_execution_id); END IF;

  SELECT COUNT(*) INTO column_count FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='rt_task_instance' AND column_name='source_instance_id';
  IF column_count=0 THEN ALTER TABLE rt_task_instance ADD COLUMN source_instance_id BIGINT NULL COMMENT '恢复来源实例' AFTER version_id; END IF;
  SELECT COUNT(*) INTO column_count FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='rt_task_instance' AND column_name='recovery_strategy';
  IF column_count=0 THEN ALTER TABLE rt_task_instance ADD COLUMN recovery_strategy VARCHAR(32) NULL COMMENT 'DIRECT、CHECKPOINT、SAVEPOINT或TIMESTAMP' AFTER source_instance_id; END IF;
  SELECT COUNT(*) INTO column_count FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='rt_task_instance' AND column_name='recovery_state_path';
  IF column_count=0 THEN ALTER TABLE rt_task_instance ADD COLUMN recovery_state_path VARCHAR(1024) NULL COMMENT '实际使用的状态路径' AFTER recovery_strategy; END IF;
  SELECT COUNT(*) INTO column_count FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='rt_task_instance' AND column_name='debug_report_status';
  IF column_count=0 THEN ALTER TABLE rt_task_instance ADD COLUMN debug_report_status VARCHAR(16) NULL COMMENT 'PASSED或FAILED' AFTER recovery_state_path; END IF;
  SELECT COUNT(*) INTO column_count FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='rt_task_instance' AND column_name='debug_report_summary';
  IF column_count=0 THEN ALTER TABLE rt_task_instance ADD COLUMN debug_report_summary VARCHAR(1024) NULL COMMENT '无写入调试摘要' AFTER debug_report_status; END IF;
  SELECT COUNT(*) INTO column_count FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='rt_task_instance' AND column_name='debug_report_json';
  IF column_count=0 THEN ALTER TABLE rt_task_instance ADD COLUMN debug_report_json LONGTEXT NULL COMMENT '结构化无写入调试报告' AFTER debug_report_summary; END IF;
  SELECT COUNT(*) INTO index_count FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='rt_task_instance' AND index_name='idx_task_instance_source';
  IF index_count=0 THEN ALTER TABLE rt_task_instance ADD KEY idx_task_instance_source (source_instance_id); END IF;
END$$
DELIMITER ;

CALL upgrade_diagnostics_recovery_debug();
DROP PROCEDURE upgrade_diagnostics_recovery_debug;
