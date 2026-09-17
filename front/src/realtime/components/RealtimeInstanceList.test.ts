import { describe, expect, it } from 'vitest';
import type { TaskInstance } from '../types';
import { filterAndSortInstances } from './RealtimeInstanceList';

const row = (id: number, status: string, jobId: string, startedAt: string): TaskInstance => ({
  id, taskId: 1, status, jobId, startedAt, createTime: startedAt, updateTime: startedAt,
  executionMode: 'PRODUCTION', managed: true,
});

describe('RealtimeInstanceList', () => {
  it('三类任务共享相同的筛选和排序语义', () => {
    const rows = [row(2, 'failed', 'job-b', '2026-01-02'), row(1, 'running', 'job-a', '2026-01-01')];
    expect(filterAndSortInstances(rows, 'job', 'all', 'running', 'startedAtDesc').map((item) => item.id)).toEqual([1]);
    expect(filterAndSortInstances(rows, '', 'all', 'all', 'idAsc').map((item) => item.id)).toEqual([1, 2]);
  });
});
