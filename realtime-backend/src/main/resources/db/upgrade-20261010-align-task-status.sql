-- 对齐参考项目：上下线 task_status、运行态 instance_status、删除标记 deleted_flag。
-- 已执行过 upgrade-20261010-task-online-status.sql 的数据库也可直接执行。
DELIMITER $$
DROP PROCEDURE IF EXISTS upgrade_align_task_status_20261010$$
CREATE PROCEDURE upgrade_align_task_status_20261010()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='rt_task' AND COLUMN_NAME='task_status'
    ) THEN
        ALTER TABLE rt_task ADD COLUMN task_status TINYINT(1) NOT NULL DEFAULT 0
            COMMENT '任务上下线状态：0=下线、1=上线' AFTER description;
        UPDATE rt_task SET task_status=1 WHERE status<>'deleted';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='rt_task' AND COLUMN_NAME='deleted_flag'
    ) THEN
        ALTER TABLE rt_task ADD COLUMN deleted_flag TINYINT(1) NOT NULL DEFAULT 0
            COMMENT '逻辑删除标记：0正常、1已删除' AFTER task_status;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='rt_task' AND COLUMN_NAME='status'
    ) THEN
        IF EXISTS (
            SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='rt_task' AND COLUMN_NAME='instance_status'
        ) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='rt_task.status 与 instance_status 同时存在，请先检查数据';
        END IF;
        ALTER TABLE rt_task CHANGE COLUMN status instance_status VARCHAR(32) NOT NULL DEFAULT 'not_running'
            COMMENT '正式实例汇总运行状态';
    END IF;

    UPDATE rt_task SET deleted_flag=1,instance_status='not_running',task_status=0
    WHERE instance_status='deleted';
    ALTER TABLE rt_task MODIFY COLUMN task_status TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '任务上下线状态：0=下线、1=上线';

    IF EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='rt_task' AND INDEX_NAME='idx_rt_task_list'
    ) THEN
        ALTER TABLE rt_task DROP INDEX idx_rt_task_list;
    END IF;
    ALTER TABLE rt_task ADD INDEX idx_rt_task_list (deleted_flag,task_status,instance_status,update_time);
END$$
DELIMITER ;
CALL upgrade_align_task_status_20261010();
DROP PROCEDURE upgrade_align_task_status_20261010;
