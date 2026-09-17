import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  createTaskBackfill,
  getScheduleDag,
  getTaskBackfill,
  getTaskDependencies,
  getTaskSchedule,
  getTaskVersionCheckSummary,
  listTaskBackfills,
  listTaskScheduleRuns,
  pauseTaskBackfill,
  resumeTaskBackfill,
  retryFailedTaskBackfill,
  saveTaskDependencies,
  saveTaskSchedule,
} from './tasks';

const response = (data: unknown) => Promise.resolve({
  ok: true,
  status: 200,
  json: async () => ({ code: 0, data }),
} as Response);

afterEach(() => vi.unstubAllGlobals());

describe('离线版本检查与调度接口契约', () => {
  it('读取版本检查、调度、依赖、补数和运行记录时保留任务上下文', async () => {
    const fetchMock = vi.fn(() => response({ items: [], total: 0 }));
    vi.stubGlobal('fetch', fetchMock);

    await getTaskVersionCheckSummary(21, 4);
    await getTaskSchedule(21);
    await getTaskDependencies(21);
    await listTaskBackfills(21, 2, 50);
    await listTaskScheduleRuns(21, 3, 20);

    const urls = (fetchMock.mock.calls as unknown as Array<[string, RequestInit?]>).map(([url]) => url);
    expect(urls).toEqual([
      '/api/tasks/21/versions/4/check-summary',
      '/api/tasks/21/schedule',
      '/api/tasks/21/dependencies',
      '/api/tasks/21/backfills?page=2&pageSize=50',
      '/api/tasks/21/schedule-runs?page=3&pageSize=20',
    ]);
  });

  it('保存 Cron 调度时完整发送并发、重试、参数和修订号', async () => {
    const fetchMock = vi.fn(() => response({ taskId: 21 }));
    vi.stubGlobal('fetch', fetchMock);

    await saveTaskSchedule(21, {
      scheduleType: 'CRON', cronExpression: '0 0 7 * * *', timezone: 'Asia/Shanghai', enabled: true,
      concurrencyPolicy: 'FORBID', maxRetries: 2, retryIntervalSeconds: 60,
      executionTimeoutSeconds: 3600, slaDurationMinutes: 90, timeoutPolicy: 'ALERT_ONLY',
      parameters: { region: 'cn' }, revision: 5,
    });

    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('/api/tasks/21/schedule');
    expect(init.method).toBe('PUT');
    expect(JSON.parse(String(init.body))).toEqual({
      scheduleType: 'CRON', cronExpression: '0 0 7 * * *', timezone: 'Asia/Shanghai', enabled: true,
      concurrencyPolicy: 'FORBID', maxRetries: 2, retryIntervalSeconds: 60,
      executionTimeoutSeconds: 3600, slaDurationMinutes: 90, timeoutPolicy: 'ALERT_ONLY',
      parameters: { region: 'cn' }, revision: 5,
    });
  });

  it('保存依赖和创建补数使用后端约定的请求结构', async () => {
    const fetchMock = vi.fn(() => response({}));
    vi.stubGlobal('fetch', fetchMock);

    await saveTaskDependencies(21, [
      { upstreamTaskId: 8, dependencyType: 'SUCCESS' },
      { upstreamTaskId: 13, dependencyType: 'COMPLETED' },
    ]);
    await createTaskBackfill(21, {
      startDate: '2026-09-01', endDate: '2026-09-03', parameters: { region: 'cn' }, maxConcurrency: 3,
    });

    const calls = fetchMock.mock.calls as unknown as Array<[string, RequestInit]>;
    expect(calls[0][0]).toBe('/api/tasks/21/dependencies');
    expect(calls[0][1].method).toBe('PUT');
    expect(JSON.parse(String(calls[0][1].body))).toEqual({ items: [
      { upstreamTaskId: 8, dependencyType: 'SUCCESS' },
      { upstreamTaskId: 13, dependencyType: 'COMPLETED' },
    ] });
    expect(calls[1][0]).toBe('/api/tasks/21/backfills');
    expect(calls[1][1].method).toBe('POST');
    expect(JSON.parse(String(calls[1][1].body))).toEqual({
      startDate: '2026-09-01', endDate: '2026-09-03', parameters: { region: 'cn' }, maxConcurrency: 3,
    });
  });

  it('调度拓扑和补数控制接口使用批次级资源路径', async () => {
    const fetchMock = vi.fn(() => response({}));
    vi.stubGlobal('fetch', fetchMock);

    await getScheduleDag();
    await getTaskBackfill(21, 41);
    await pauseTaskBackfill(21, 41);
    await resumeTaskBackfill(21, 41);
    await retryFailedTaskBackfill(21, 41);

    const calls = fetchMock.mock.calls as unknown as Array<[string, RequestInit?]>;
    expect(calls.map(([url]) => url)).toEqual([
      '/api/schedules/dag',
      '/api/tasks/21/backfills/41',
      '/api/tasks/21/backfills/41/pause',
      '/api/tasks/21/backfills/41/resume',
      '/api/tasks/21/backfills/41/retry-failed',
    ]);
    expect(calls.slice(2).map(([, init]) => init?.method)).toEqual(['POST', 'POST', 'POST']);
  });
});
