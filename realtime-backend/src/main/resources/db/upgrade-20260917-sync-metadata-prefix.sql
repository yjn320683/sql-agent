UPDATE rt_task_param
SET param_value = '__meta_op_ts',
    update_time = CURRENT_TIMESTAMP
WHERE task_type = 'sync'
  AND param_type = 'table_conf'
  AND param_key = 'sequence.field';
