-- 动态参数可编辑性由 rt_task_param 控制；旧库先补列，再修正同步任务参数。
DELIMITER $$
DROP PROCEDURE IF EXISTS upgrade_sync_param_editable_20261012$$
CREATE PROCEDURE upgrade_sync_param_editable_20261012()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='rt_task_param' AND COLUMN_NAME='editable_flag'
    ) THEN
        ALTER TABLE rt_task_param ADD COLUMN editable_flag TINYINT(1) NOT NULL DEFAULT 1 AFTER required_flag;
    END IF;
END$$
DELIMITER ;
CALL upgrade_sync_param_editable_20261012();
DROP PROCEDURE upgrade_sync_param_editable_20261012;

UPDATE rt_task_param SET editable_flag=0
 WHERE task_type='sync' AND param_type='table_conf' AND param_key='changelog-producer';
UPDATE rt_task_param SET required_flag=1,param_value='__meta_op_ts'
 WHERE task_type='sync' AND param_type='table_conf' AND param_key='sequence.field';
