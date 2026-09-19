import { afterEach, describe, expect, it, vi } from 'vitest';
import { applySyncSchemaChange, assignAssetDomain, canEnableSyncTask, compareRealtimeTableSchemaVersions, createBusinessDomain, createSyncTask, getAssetDomainAssignment, getInstanceDebugReport, getInstanceProgress, getMysqlTableDdl, getRealtimeTableSchemaVersion, getRecoveryOptions, getStateHistory, getSyncProgress, getSyncTask, listAlertRules, listAlerts, listAlertsPage, listBusinessDomainAssets, listBusinessDomains, listManagedTasks, listRealtimeTableSchemaVersions, listSyncDirtyRecords, listSyncSchemaChanges, listSyncTasks, muteAlert, recoverInstance, resolveSyncDirtyRecord, startSyncTask, unassignAssetDomain, unmuteAlert, updateBusinessDomain, updateBusinessDomainStatus, validateRealtimeTableSafeUpdate } from './api';
import type { SyncTaskSave } from './types';

const response = (data: unknown) => Promise.resolve({
  ok: true,
  status: 200,
  json: async () => ({ code: 0, data }),
} as Response);

const task: SyncTaskSave = {
  projectId: 29, name: '订单同步', owner: 'tester', description: '中文配置', flinkVersion: '2.2.1',
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
    const fetchMock = vi.fn(() => response(31));
    vi.stubGlobal('fetch', fetchMock);
    expect(await createSyncTask(task)).toBe(31);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    const body = JSON.parse(String(init.body));
    expect(body.projectId).toBe(29);
    expect(body.alarmConfig).toEqual({ alarmType: 'task-failed', alarmGroup: '实时告警组' });
    expect(body.flinkConf.parallelism).toBe(2);
    expect(body.taskConfig).toEqual({ sourceServerId: 7, sourceType: 'mysql-cdc', cdcConfig: task.taskConfig.cdcConfig });
    expect(body.taskConfig).not.toHaveProperty('parallelism');
    expect(body.taskConfig).not.toHaveProperty('alarmType');
  });

  it('源表 DDL 只传 Server 和表名，不允许调用方指定数据库', async () => {
    const fetchMock = vi.fn(() => response({ database: 'orders', table: 'order detail', ddl: 'CREATE TABLE ...' }));
    vi.stubGlobal('fetch', fetchMock);

    await getMysqlTableDdl(7, 'order detail');

    const [url] = fetchMock.mock.calls[0] as unknown as [string, RequestInit?];
    expect(url).toBe('/api/servers/7/mysql/table-ddl?table=order%20detail');
    expect(url).not.toContain('database=');
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

  it('启动预检读取轻量启动策略', async () => {
    const fetchMock = vi.fn(() => response({
      canEnable: true,
      startPolicy: {
        productionLocked: true,
        syncTableSetChanged: false,
        requiredStartType: 'savepoint',
        requiredStatePath: 'hdfs://savepoint/task-31/sp-1',
        canResetConsumptionPoint: false,
      },
    }));
    vi.stubGlobal('fetch', fetchMock);
    const result = await canEnableSyncTask(31);
    expect(result.startPolicy?.requiredStartType).toBe('savepoint');
    expect(fetchMock).toHaveBeenCalledWith('/v1/api/tasks/31/can-enable', expect.objectContaining({ method: 'POST' }));
  });

  it('正式启动可以显式提交消费点时间戳', async () => {
    const fetchMock = vi.fn(() => response({ id: 90, taskId: 31, status: 'submitting' }));
    vi.stubGlobal('fetch', fetchMock);
    await startSyncTask(31, { startType: 'direct', sourceStartupTimestampMillis: 1_700_000_000_000 });
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('/v1/api/tasks/31/enable');
    expect(JSON.parse(String(init.body))).toEqual({
      startType: 'direct', sourceStartupTimestampMillis: 1_700_000_000_000,
    });
  });

  it('计算与出仓列表完整发送同步布局中的筛选条件', async () => {
    const fetchMock = vi.fn(() => response({ records: [], total: 0, pageNo: 2, pageSize: 50 }));
    vi.stubGlobal('fetch', fetchMock);
    await listManagedTasks('compute', new URLSearchParams('page=2&pageSize=50&keyword=agg&status=running&owner=owner-a&lastOperator=operator-b&sourceKeyword=ods.orders'));
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('/v1/api/tasks/page');
    expect(JSON.parse(String(init.body))).toEqual({
      taskType: 'compute', pageNo: 2, pageSize: 50, keyword: 'agg', status: 'running',
      owner: 'owner-a', lastOperator: 'operator-b', sourceKeyword: 'ods.orders',
    });
  });

  it('同步可观测接口保留任务和实例上下文', async () => {
    const fetchMock = vi.fn(() => response({ items: [], total: 0 }));
    vi.stubGlobal('fetch', fetchMock);
    await getSyncProgress(31, 99, true);
    await listSyncDirtyRecords(31, false, 2, 50);
    await resolveSyncDirtyRecord(31, 7);
    await listSyncSchemaChanges(31, true);
    await applySyncSchemaChange(31, 8);
    const urls = (fetchMock.mock.calls as unknown as Array<[string, RequestInit?]>).map(([url]) => url);
    expect(urls).toEqual([
      '/api/realtime/sync-tasks/31/instances/99/sync-progress?refresh=true',
      '/api/realtime/sync-tasks/31/dirty-records?unresolvedOnly=false&page=2&pageSize=50',
      '/api/realtime/sync-tasks/31/dirty-records/7/resolve',
      '/api/realtime/sync-tasks/31/schema-changes?refresh=true',
      '/api/realtime/sync-tasks/31/schema-changes/8/apply',
    ]);
  });

  it('实例提交进度使用统一任务接口', async () => {
    const fetchMock = vi.fn(() => response({
      taskId: 31, instanceId: 99, taskType: 'sync', executionMode: 'PRODUCTION',
      instanceStatus: 'submitting', phase: 'submitting', stage: 'building_command',
      stageIndex: 4, stageCount: 6, message: '正在生成启动命令',
    }));
    vi.stubGlobal('fetch', fetchMock);

    const result = await getInstanceProgress(31, 99);

    expect(result.stage).toBe('building_command');
    expect(fetchMock).toHaveBeenCalledWith('/v1/api/tasks/31/instances/99/progress', { credentials: 'include' });
  });

  it('任务详情按任务查询告警，避免加载全平台告警', async () => {
    const fetchMock = vi.fn(() => response([]));
    vi.stubGlobal('fetch', fetchMock);

    await listAlerts(31);

    expect(fetchMock).toHaveBeenCalledWith('/api/alerts?taskId=31', { credentials: 'include' });
  });

  it('恢复与调试报告使用来源实例上下文', async () => {
    const fetchMock = vi.fn(() => response({ strategies: [], status: 'PASSED' }));
    vi.stubGlobal('fetch', fetchMock);
    await getRecoveryOptions(31, 99);
    await recoverInstance(31, 99, { startType: 'savepoint', statePath: 'hdfs:///sp-1' });
    await getInstanceDebugReport(31, 100);
    const calls = fetchMock.mock.calls as unknown as Array<[string, RequestInit?]>;
    expect(calls[0]?.[0]).toBe('/v1/api/tasks/31/instances/99/recovery-options');
    expect(calls[1]?.[0]).toBe('/v1/api/tasks/31/instances/99/recover');
    expect(JSON.parse(String(calls[1]?.[1]?.body))).toEqual({ startType: 'savepoint', statePath: 'hdfs:///sp-1' });
    expect(calls[2]?.[0]).toBe('/v1/api/tasks/31/instances/100/debug-report');
  });

  it('告警中心使用服务端分页和生命周期接口', async () => {
    const fetchMock = vi.fn(() => response({ records: [], total: 0, page: 2, pageSize: 20 }));
    vi.stubGlobal('fetch', fetchMock);
    await listAlertsPage(new URLSearchParams({ view: 'RECOVERED', page: '2', pageSize: '20' }));
    expect(fetchMock).toHaveBeenCalledWith('/api/alerts/page?view=RECOVERED&page=2&pageSize=20', { credentials: 'include' });

    fetchMock.mockImplementation(() => response(true));
    await muteAlert(8, '2026-09-16T12:00:00.000Z');
    expect(fetchMock).toHaveBeenLastCalledWith('/api/alerts/8/mute', expect.objectContaining({ method: 'POST', credentials: 'include' }));
    await unmuteAlert(8);
    expect(fetchMock).toHaveBeenLastCalledWith('/api/alerts/8/unmute', { method: 'POST', credentials: 'include' });

    fetchMock.mockImplementation(() => response([]));
    await listAlertRules();
    expect(fetchMock).toHaveBeenLastCalledWith('/api/alert-rules', { credentials: 'include' });
  });

  it('业务域列表、维护与资产关联使用统一资产接口', async () => {
    const fetchMock = vi.fn(() => response({ records: [], total: 0, pageNo: 1, pageSize: 20 }));
    vi.stubGlobal('fetch', fetchMock);

    await listBusinessDomains(new URLSearchParams({ keyword: '订单', enabled: 'true', page: '1' }));
    await createBusinessDomain({ code: 'trade', name: '交易域' });
    await updateBusinessDomain(3, { name: '交易域', owner: 'team-a' });
    await updateBusinessDomainStatus(3, false);
    await listBusinessDomainAssets(3, new URLSearchParams({ assetType: 'PAIMON', page: '2' }));
    await getAssetDomainAssignment('HIVE', 'hive', 'ods', 'orders');
    await assignAssetDomain({
      assetType: 'PAIMON', catalogName: 'paimon', databaseName: 'dwd', tableName: 'orders',
      realtimeTableId: 19, domainId: 3,
    });
    await unassignAssetDomain('HIVE', 'hive', 'ods', 'orders');

    const calls = fetchMock.mock.calls as unknown as Array<[string, RequestInit?]>;
    expect(calls.map(([url]) => url)).toEqual([
      '/api/realtime/business-domains?keyword=%E8%AE%A2%E5%8D%95&enabled=true&page=1',
      '/api/realtime/business-domains',
      '/api/realtime/business-domains/3',
      '/api/realtime/business-domains/3/status',
      '/api/realtime/business-domains/3/assets?assetType=PAIMON&page=2',
      '/api/realtime/business-domains/assignment?assetType=HIVE&catalogName=hive&databaseName=ods&tableName=orders',
      '/api/realtime/business-domains/assignment',
      '/api/realtime/business-domains/assignment?assetType=HIVE&catalogName=hive&databaseName=ods&tableName=orders',
    ]);
    expect(JSON.parse(String(calls[6]?.[1]?.body))).toMatchObject({ realtimeTableId: 19, domainId: 3 });
    expect(calls[7]?.[1]?.method).toBe('DELETE');
  });

  it('Schema 历史、版本比较和安全变更预检使用实时表生命周期接口', async () => {
    const fetchMock = vi.fn(() => response({ records: [], total: 0 }));
    vi.stubGlobal('fetch', fetchMock);

    await listRealtimeTableSchemaVersions(19, 2, 30);
    await getRealtimeTableSchemaVersion(19, 4);
    await compareRealtimeTableSchemaVersions(19, 2, 4);
    await validateRealtimeTableSafeUpdate(19, { addColumns: [] });

    const calls = fetchMock.mock.calls as unknown as Array<[string, RequestInit?]>;
    expect(calls.map(([url]) => url)).toEqual([
      '/api/realtime/tables/19/schema-versions?page=2&pageSize=30',
      '/api/realtime/tables/19/schema-versions/4',
      '/api/realtime/tables/19/schema-compare?fromVersion=2&toVersion=4',
      '/api/realtime/tables/19/safe-update/validate',
    ]);
    expect(calls[3]?.[1]?.method).toBe('POST');
  });
});
