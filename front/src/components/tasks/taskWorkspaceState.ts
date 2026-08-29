export type TaskWorkspaceRoute =
  | { activeKey: 'list' }
  | { activeKey: 'new' }
  | { activeKey: `task:${number}`; taskId: number }
  | { activeKey: 'list'; executionTaskId: number; executionId?: number };

export function parseStoredTaskIds(rawValue: string | null): number[] {
  try {
    const value = JSON.parse(rawValue || '[]');
    if (!Array.isArray(value)) return [];
    return [...new Set(value.map(Number).filter((item) => Number.isInteger(item) && item > 0))];
  } catch {
    return [];
  }
}

export function parseTaskWorkspaceRoute(pathname: string): TaskWorkspaceRoute {
  if (pathname === '/tasks/new') return { activeKey: 'new' };
  const edit = /^\/tasks\/(\d+)\/edit$/.exec(pathname);
  if (edit) return { activeKey: `task:${Number(edit[1])}`, taskId: Number(edit[1]) };
  const detail = /^\/tasks\/(\d+)\/executions\/(\d+)$/.exec(pathname);
  if (detail) {
    return {
      activeKey: 'list',
      executionTaskId: Number(detail[1]),
      executionId: Number(detail[2]),
    };
  }
  const executions = /^\/tasks\/(\d+)\/executions$/.exec(pathname);
  if (executions) return { activeKey: 'list', executionTaskId: Number(executions[1]) };
  return { activeKey: 'list' };
}
