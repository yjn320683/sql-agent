-- 仅更新同步任务参数字典的默认值，不修改任何已有任务或实例配置。
INSERT INTO rt_task_param (
    task_type, param_type, param_key, key_desc, param_value, value_type, input_type,
    min_value, max_value, step_value, precision_value,
    required_flag, enabled_flag, sort_order, create_time, update_time
) VALUES (
    'sync', 'flink_conf', 'taskmanager.numberOfTaskSlots', 'TaskManager Slot 数', '3',
    'number', 'input_number', 1, 4, 1, 0, 0, 1, 100.0000,
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
)
ON DUPLICATE KEY UPDATE
    key_desc = VALUES(key_desc),
    param_value = IF(param_value IN ('1', '2'), '3', param_value),
    min_value = VALUES(min_value), max_value = VALUES(max_value),
    step_value = VALUES(step_value), precision_value = VALUES(precision_value),
    enabled_flag = VALUES(enabled_flag),
    sort_order = VALUES(sort_order), update_time = CURRENT_TIMESTAMP;

UPDATE rt_task_param
SET param_value = IF(param_value = '2', '3', param_value), min_value = 1, max_value = 4,
    step_value = 1, precision_value = 0, update_time = CURRENT_TIMESTAMP
WHERE task_type = 'sync' AND param_type = 'table_conf'
  AND param_key IN ('bucket', 'sink.parallelism');
