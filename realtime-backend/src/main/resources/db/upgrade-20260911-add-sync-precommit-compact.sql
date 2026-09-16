-- 为同步任务增加 Paimon Changelog 提交前压缩参数；仅更新参数字典，不修改已有任务配置。
INSERT INTO rt_task_param (
    task_type, param_type, param_key, key_desc, param_value, value_type, input_type,
    required_flag, enabled_flag, sort_order, create_time, update_time
) VALUES (
    'sync', 'table_conf', 'precommit-compact', 'Changelog 提交前压缩',
    '[{"label":"false（官网默认）","value":"false","default":false},{"label":"true（推荐）","value":"true","default":true}]',
    'list', 'select', 0, 1, 65.0000, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
)
ON DUPLICATE KEY UPDATE
    key_desc = VALUES(key_desc), param_value = VALUES(param_value),
    value_type = VALUES(value_type), input_type = VALUES(input_type),
    required_flag = VALUES(required_flag),
    enabled_flag = VALUES(enabled_flag), sort_order = VALUES(sort_order),
    update_time = CURRENT_TIMESTAMP;
