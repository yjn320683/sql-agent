-- 补齐当前查询依赖的索引；已有相同左前缀索引时跳过，不删除旧索引。
DELIMITER $$
DROP PROCEDURE IF EXISTS upgrade_sync_query_index_20260911$$
CREATE PROCEDURE upgrade_sync_query_index_20260911(
    IN target_table VARCHAR(64), IN target_index VARCHAR(64), IN target_columns VARCHAR(255))
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM (
            SELECT INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',') AS columns_list
            FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=target_table
            GROUP BY INDEX_NAME
        ) existing_indexes
        WHERE INDEX_NAME=target_index OR columns_list=target_columns
           OR columns_list LIKE CONCAT(target_columns, ',%')
    ) THEN
        SET @sync_index_ddl=CONCAT('ALTER TABLE `',target_table,'` ADD INDEX `',target_index,'` (',target_columns,')');
        PREPARE sync_index_statement FROM @sync_index_ddl;
        EXECUTE sync_index_statement;
        DEALLOCATE PREPARE sync_index_statement;
    END IF;
END$$
DELIMITER ;
CALL upgrade_sync_query_index_20260911('rt_sync_task_table_mapping','idx_sync_mapping_server','source_server_id,task_id');
CALL upgrade_sync_query_index_20260911('rt_sync_task_table_mapping','idx_sync_mapping_source_table','task_id,source_table');
CALL upgrade_sync_query_index_20260911('rt_sync_task_table_mapping','idx_sync_mapping_target_table','task_id,target_table');
CALL upgrade_sync_query_index_20260911('rt_task_param','idx_task_param_type','task_type,param_type,enabled_flag,sort_order');
CALL upgrade_sync_query_index_20260911('rt_task_instance','idx_task_instance_task_status','task_id,status');
CALL upgrade_sync_query_index_20260911('rt_task_instance','idx_task_instance_status_task','status,task_id');
CALL upgrade_sync_query_index_20260911('rt_task_instance','idx_task_instance_task_create','task_id,create_time');
CALL upgrade_sync_query_index_20260911('rt_task_operation','idx_task_operation_deadline','operation_status,deadline_at');
CALL upgrade_sync_query_index_20260911('rt_task_change_log','idx_task_change_log_before_version','before_version_id');
CALL upgrade_sync_query_index_20260911('rt_task_change_log','idx_task_change_log_after_version','after_version_id');
CALL upgrade_sync_query_index_20260911('rt_task_change_log','idx_task_change_log_task_id','task_id,id');
DROP PROCEDURE upgrade_sync_query_index_20260911;
