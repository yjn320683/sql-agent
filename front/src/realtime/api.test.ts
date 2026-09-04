import { afterEach, describe, expect, it, vi } from 'vitest';
import { createSyncTask, getStateHistory, getSyncTask, listSyncTasks } from './api';
import type { SyncTaskSave } from './types';

const response = (data: unknown) => Promise.resolve({
  ok: true,
  status: 200,
  json: async () => ({ code: 0, data }),
} as Response);

const task: SyncTaskSave = {
  name: '订单同步', owner: 'tester', description: '中文配置', flinkVersion: '2.2.1',
  sourceServerId: 7, sourceType: 'mysql-cdc', targetDatabase: 'ods',
  taskConfig: {
    sourceServerId: 7, parallelism: 2, checkpointInterval: 30,
    taskManagerMemory: '3GB', jobManagerMemory: '1GB',
    alarmType: 'task-failed', alarmGroup: '实时告警组',
    flinkConfOverrides: { 'execution.checkpointing.mode': 'EXACTLY_ONCE' },
    cdcConfig: { databaseName: 'orders', selectedTables: ['orders'], targetDatabase: 'ods', domainPrefix: 'trade' },
  },
};

afterEach(() => vi.unstubAllGlobals());

describe('实时同步统一接口契约', () => {
  it('列表把参考项目搜索条件发送到统一分页接口', async () => {
    const fetchMock = vi.fn(() => response({ records: [], total: 0, pageNo: 1, pageSize: 20 }));
    vi.stubGlobal('fetch', fetchMock);
    await listSyncTasks(new URLSearchParams('page=3&pageSize=50&keyword=order&status=running&owner=owner-a&lastOperator=operator-b&paimonTableKeyword=orders&sort=name&order=asc'));
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('/v1/api/tasks/page');
    expect(JSON.parse(String(init.body))).toMatchObject({
      taskType: 'sync', pageNo: 3, pageSize: 50, keyword: 'order', status: 'running',
      owner: 'owner-a', lastOperator: 'operator-b', paimonTableKeyword: 'orders',
      sortField: 'name', sortOrder: 'asc',
    });
  });

  it('创建请求把公共告警和 Flink 配置从同步私有配置中剥离', async () => {
    const fetchMock = vi.fn()
      .mockImplementationOnce(() => response(31))
      .mockImplementationOnce(() => response({
        id: 31, taskType: 'sync', name: task.name, owner: task.owner, description: task.description,
        flinkVersion: task.flinkVersion, status: 'not_running', alarmConfig: {}, flinkConf: {},
        taskConfig: { sourceServerId: 7, sourceType: 'mysql-cdc', cdcConfig: task.taskConfig.cdcConfig },
      }));
    vi.stubGlobal('fetch', fetchMock);
    await createSyncTask(task);
    const [, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    const body = JSON.parse(String(init.body));
    expect(body.alarmConfig).toEqual({ alarmType: 'task-failed', alarmGroup: '实时告警组' });
    expect(body.flinkConf.parallelism).toBe(2);
    expect(body.taskConfig).toEqual({ sourceServerId: 7, sourceType: 'mysql-cdc', cdcConfig: task.taskConfig.cdcConfig });
    expect(body.taskConfig).not.toHaveProperty('parallelism');
    expect(body.taskConfig).not.toHaveProperty('alarmType');
  });

  it('详情把统一公共配置还原为同步编辑器模型', async () => {
    vi.stubGlobal('fetch', vi.fn(() => response({
      id: 31, taskType: 'sync', name: task.name, owner: task.owner, description: task.description,
      flinkVersion: '2.2.1', status: 'running', alarmConfig: { alarmType: 'task-failed', alarmGroup: '实时告警组' },
      flinkConf: { parallelism: 2, checkpointIntervalSeconds: 30, taskManagerMemoryGb: 3, jobManagerMemoryGb: 1 },
      taskConfig: { sourceServerId: 7, sourceType: 'mysql-cdc', cdcConfig: task.taskConfig.cdcConfig },
    })));
    const detail = await getSyncTask(31);
    expect(detail.sourceServerId).toBe(7);
    expect(detail.taskConfig.parallelism).toBe(2);
    expect(detail.taskConfig.checkpointInterval).toBe(30);
    expect(detail.taskConfig.taskManagerMemory).toBe('3GB');
    expect(detail.taskConfig.alarmGroup).toBe('实时告警组');
  });

  it('Checkpoint 和 Savepoint 使用参考项目的 Flink 公共接口', async () => {
    const fetchMock = vi.fn(() => response([]));
    vi.stubGlobal('fetch', fetchMock);
    await getStateHistory(31, 'checkpoint');
    await getStateHistory(31, 'savepoint');
    const calls = fetchMock.mock.calls as unknown as Array<[string, RequestInit?]>;
    expect(calls[0]?.[0]).toBe('/v1/api/flink-common/listcheckpoint?taskId=31');
    expect(calls[1]?.[0]).toBe('/v1/api/flink-common/listsavepoint?taskId=31');
  });
});
