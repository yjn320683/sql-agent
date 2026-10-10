-- 第一阶段：增加任务上下线状态。执行后继续运行 upgrade-20261010-align-task-status.sql，
-- 将旧 status 拆分为 instance_status 和 deleted_flag。
-- 存量未删除任务按参考项目迁为上线；新任务默认下线。
DELIMITER $$
DROP PROCEDURE IF EXISTS upgrade_task_online_status_20261010$$
CREATE PROCEDURE upgrade_task_online_status_20261010()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rt_task' AND COLUMN_NAME = 'task_status'
    ) THEN
        ALTER TABLE rt_task ADD COLUMN task_status TINYINT(1) NULL
            COMMENT '任务上下线状态：0=下线、1=上线；运行状态由 status 保存' AFTER status;
        UPDATE rt_task SET task_status = 1 WHERE status <> 'deleted';
        UPDATE rt_task SET task_status = 0 WHERE task_status IS NULL;
        ALTER TABLE rt_task MODIFY COLUMN task_status TINYINT(1) NOT NULL DEFAULT 0
            COMMENT '任务上下线状态：0=下线、1=上线；运行状态由 status 保存';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rt_task' AND INDEX_NAME = 'idx_rt_task_enabled'
    ) THEN
        ALTER TABLE rt_task ADD INDEX idx_rt_task_enabled (task_type, task_status, update_time);
    END IF;
END$$
DELIMITER ;
CALL upgrade_task_online_status_20261010();
DROP PROCEDURE upgrade_task_online_status_20261010;
