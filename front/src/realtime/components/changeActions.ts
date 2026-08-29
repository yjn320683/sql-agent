const actionAliases: Record<string, string> = {
  CREATE: '创建',
  EDIT: '编辑',
  DELETE: '删除',
  START: '启动',
  STOP: '停止',
  STATUS_SYNC: '状态同步',
  REFRESH: '状态同步',
  创建任务: '创建',
  编辑任务: '编辑',
  删除任务: '删除',
  启动任务: '启动',
  停止任务: '停止',
  刷新状态: '状态同步',
};

export const normalizeChangeAction = (action?: string) => {
  const value = (action ?? '').trim();
  return (actionAliases[value] ?? actionAliases[value.toUpperCase()] ?? value) || '-';
};

const actionOrder = ['创建', '编辑', '删除', '启动', '停止', '状态同步'];

export const availableChangeActions = (actions: Array<string | undefined>) => {
  const existing = new Set(actions.map(normalizeChangeAction).filter((value) => actionOrder.includes(value)));
  return actionOrder.filter((value) => existing.has(value));
};

export const changeDetailButtonText = (action?: string) => {
  const normalized = normalizeChangeAction(action);
  if (normalized === '编辑') return '查看变更';
  if (normalized === '启动') return '使用参数';
  if (normalized === '停止') return '停止详情';
  return '查看详情';
};
