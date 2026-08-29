-- 将早期新平台写入的英文动作统一为参考项目的中文动作值；可重复执行。
UPDATE rt_task_change_log
SET action = CASE UPPER(action)
  WHEN 'CREATE' THEN '创建'
  WHEN 'EDIT' THEN '编辑'
  WHEN 'DELETE' THEN '删除'
  WHEN 'START' THEN '启动'
  WHEN 'STOP' THEN '停止'
  WHEN 'STATUS_SYNC' THEN '状态同步'
  WHEN 'REFRESH' THEN '状态同步'
  ELSE action
END
WHERE UPPER(action) IN (
  'CREATE', 'EDIT', 'DELETE', 'START', 'STOP', 'STATUS_SYNC', 'REFRESH'
);
