import { describe, expect, it } from 'vitest';
import { parseStoredTaskIds, parseTaskWorkspaceRoute } from './taskWorkspaceState';

describe('task workspace route state', () => {
  it('maps list, create and edit routes to workspace tabs', () => {
    expect(parseTaskWorkspaceRoute('/tasks')).toEqual({ activeKey: 'list' });
    expect(parseTaskWorkspaceRoute('/tasks/new')).toEqual({ activeKey: 'new' });
    expect(parseTaskWorkspaceRoute('/tasks/42/edit')).toEqual({ activeKey: 'task:42', taskId: 42 });
  });

  it('preserves execution deep links inside the task list tab', () => {
    expect(parseTaskWorkspaceRoute('/tasks/42/executions')).toEqual({
      activeKey: 'list',
      executionTaskId: 42,
    });
    expect(parseTaskWorkspaceRoute('/tasks/42/executions/99')).toEqual({
      activeKey: 'list',
      executionTaskId: 42,
      executionId: 99,
    });
  });
});

describe('stored task tabs', () => {
  it('keeps only unique positive integer task ids', () => {
    expect(parseStoredTaskIds('[1, "2", 1, 0, -1, 3.5, "bad"]')).toEqual([1, 2]);
  });

  it('ignores invalid storage values', () => {
    expect(parseStoredTaskIds('{')).toEqual([]);
    expect(parseStoredTaskIds('{}')).toEqual([]);
    expect(parseStoredTaskIds(null)).toEqual([]);
  });
});
