import { requestJson } from '../api/client';
import type {
  PaimonTablePrefixOption,
  MysqlColumn,
  MysqlTableSchema,
  RealtimeAlert,
  RealtimeServer,
  RealtimeServerSave,
  SyncTask,
  SyncTaskPage,
  SyncTaskSave,
  TaskChangeLog,
  TaskInstance,
  TaskMapping,
  TaskParam,
  SyncDirtyRecordPage,
  SyncProgressSnapshot,
  SyncSchemaChange,
  SyncSourceTableOption,
  ManagedTask, ManagedTaskSave, ManagedTaskType, RealtimeTable, RealtimeTableCreateRequest,
} from './types';

const json = (method: string, body?: unknown): RequestInit => ({
  method,
  headers: { 'Content-Type': 'application/json; charset=UTF-8' },
  body: body === undefined ? undefined : JSON.stringify(body),
});

type UnifiedTaskWire = {
  id?: number; taskId?: number; taskType: 'sync'; name: string; owner?: string; description?: string;
  flinkVersion: string; status?: SyncTask['status']; updateTime?: string; createTime?: string;
  expectedUpdateTime?: string; startType?: string; statePath?: string;
  alarmConfig: { alarmType?: string; alarmGroup?: string };
  flinkConf: { parallelism?: number; checkpointIntervalSeconds?: number; taskManagerMemoryGb?: number; jobManagerMemoryGb?: number; flinkConfOverrides?: Record<string, string> };
  taskConfig: SyncTaskSave['taskConfig'] & { sourceServerId: number; sourceType: 'mysql-cdc' };
  editPolicy?: SyncTask['editPolicy']; sourceServerName?: string; projectId?: number; projectName?: string;
};

const memoryGb = (value?: string) => {
  const raw = String(value ?? '').trim();
  const parsed = Number(raw.replace(/\s*(?:gb?|mb?)$/i, ''));
  if (!Number.isFinite(parsed)) return undefined;
  return /mb$/i.test(raw) ? parsed / 1024 : parsed;
};

const toUnifiedTask = (value: SyncTaskSave): UnifiedTaskWire => ({
  taskType: 'sync', name: value.name, owner: value.owner, description: value.description,
  flinkVersion: value.flinkVersion, expectedUpdateTime: value.expectedUpdateTime,
  alarmConfig: { alarmType: value.taskConfig.alarmType, alarmGroup: value.taskConfig.alarmGroup },
  flinkConf: {
    parallelism: value.taskConfig.parallelism,
    checkpointIntervalSeconds: value.taskConfig.checkpointInterval,
    taskManagerMemoryGb: memoryGb(value.taskConfig.taskManagerMemory),
    jobManagerMemoryGb: memoryGb(value.taskConfig.jobManagerMemory),
    flinkConfOverrides: value.taskConfig.flinkConfOverrides ?? {},
  },
  taskConfig: {
    sourceServerId: value.sourceServerId,
    sourceType: 'mysql-cdc',
    cdcConfig: value.taskConfig.cdcConfig,
  },
});

const fromUnifiedTask = (value: UnifiedTaskWire): SyncTask => ({
  id: value.id!, name: value.name, owner: value.owner, description: value.description,
  flinkVersion: value.flinkVersion, sourceServerId: value.taskConfig.sourceServerId,
  sourceType: 'mysql-cdc', targetDatabase: value.taskConfig.cdcConfig.targetDatabase,
  status: value.status ?? 'not_running', projectId: value.projectId, projectName: value.projectName,
  sourceServerName: value.sourceServerName, createTime: value.createTime ?? '', updateTime: value.updateTime ?? '',
  editPolicy: value.editPolicy,
  taskConfig: {
    ...value.taskConfig,
    parallelism: value.flinkConf?.parallelism,
    checkpointInterval: value.flinkConf?.checkpointIntervalSeconds,
    taskManagerMemory: value.flinkConf?.taskManagerMemoryGb === undefined ? undefined : `${value.flinkConf.taskManagerMemoryGb}GB`,
    jobManagerMemory: value.flinkConf?.jobManagerMemoryGb === undefined ? undefined : `${value.flinkConf.jobManagerMemoryGb}GB`,
    flinkConfOverrides: value.flinkConf?.flinkConfOverrides ?? {},
    alarmType: value.alarmConfig?.alarmType,
    alarmGroup: value.alarmConfig?.alarmGroup,
  },
});

export const listSyncTasks = async (query: URLSearchParams): Promise<SyncTaskPage> => {
  const page = await requestJson<{ records: SyncTaskPage['items']; total: number; pageNo: number; pageSize: number }>('/v1/api/tasks/page', json('POST', {
    taskType: 'sync', pageNo: Number(query.get('page') || 1), pageSize: Number(query.get('pageSize') || 20),
    keyword: query.get('keyword') || undefined, status: query.get('status') || undefined,
    owner: query.get('owner') || undefined, lastOperator: query.get('lastOperator') || undefined,
    paimonTableKeyword: query.get('paimonTableKeyword') || undefined,
    sortField: query.get('sort') || 'lastOperationTime', sortOrder: query.get('order') || 'desc',
  }));
  return { items: page.records, total: page.total, page: page.pageNo, pageSize: page.pageSize };
};
export const getSyncTask = async (id: number) => fromUnifiedTask(await requestJson<UnifiedTaskWire>(`/v1/api/tasks/${id}/detail`));
export const createSyncTask = (value: SyncTaskSave) => requestJson<number>('/v1/api/tasks/create', json('POST', toUnifiedTask(value)));
export const updateSyncTask = (id: number, value: SyncTaskSave) => requestJson<number>(`/v1/api/tasks/${id}/update`, json('POST', toUnifiedTask(value)));
export const deleteSyncTask = (id: number) => requestJson<void>(`/v1/api/tasks/${id}/delete`, json('POST'));
export const previewSyncTask = (value: SyncTaskSave, excludeTaskId?: number) => requestJson<{ command: string; arguments: string[] }>(`/v1/api/tasks/command-preview${excludeTaskId ? `?excludeTaskId=${excludeTaskId}` : ''}`, json('POST', toUnifiedTask(value)));
export const previewSavedSyncTask = (id: number, debug = false, value?: Record<string, unknown>) => requestJson<{ command: string; arguments: string[] }>(debug ? `/api/tasks/${id}/debug-command-preview` : `/v1/api/tasks/${id}/command-preview`, debug ? json('POST', value ?? {}) : undefined);
export const startSyncTask = async (id: number, value: { startType: string; statePath?: string; dryRun?: boolean; parallelism?: number; checkpointInterval?: number; taskManagerMemory?: string; jobManagerMemory?: string; flinkConfOverrides?: Record<string, string>; mysqlConfOverrides?: Record<string, string>; tableConfOverrides?: Record<string, string> }, debug = false) => {
  if (!debug) return requestJson<TaskInstance>(`/v1/api/tasks/${id}/enable`, json('POST', value));
  const task = await getSyncTask(id); const request = toUnifiedTask(task);
  request.taskId = id; request.startType = value.startType; request.statePath = value.statePath;
  request.flinkConf = { ...request.flinkConf, parallelism: value.parallelism, checkpointIntervalSeconds: value.checkpointInterval,
    taskManagerMemoryGb: memoryGb(value.taskManagerMemory), jobManagerMemoryGb: memoryGb(value.jobManagerMemory), flinkConfOverrides: value.flinkConfOverrides };
  request.taskConfig = { ...request.taskConfig, cdcConfig: { ...request.taskConfig.cdcConfig,
    mysqlConfOverrides: value.mysqlConfOverrides, tableConfOverrides: value.tableConfOverrides } };
  return requestJson<TaskInstance>('/v1/api/tasks/debug', json('POST', request));
};
export const stopSyncTask = (id: number, value: { stopType: string }) => requestJson<TaskInstance>(`/v1/api/tasks/${id}/stop`, json('POST', value));
export const canEnableSyncTask = (id: number) => requestJson<{ canEnable: boolean; reason?: string }>(`/v1/api/tasks/${id}/can-enable`, json('POST', {}));
export const refreshSyncTask = (id: number) => requestJson<TaskInstance>(`/api/realtime/sync-tasks/${id}/refresh-status`, { method: 'POST' });
export const listMappings = (id: number) => requestJson<TaskMapping[]>(`/api/realtime/sync-tasks/${id}/table-mappings`);
export const listInstances = (id: number, executionMode: 'PRODUCTION' | 'DEBUG' = 'PRODUCTION') => requestJson<TaskInstance[]>(`/v1/api/tasks/${id}/instances?executionMode=${executionMode}`);
export const listVersions = (id: number) => requestJson<Record<string, unknown>[]>(`/v1/api/tasks/${id}/versions`);
export const getVersionConfig = (id: number, versionId: number) => requestJson<Record<string, unknown>>(`/v1/api/tasks/${id}/versions/${versionId}/config`);
export const getStateHistory = (id: number, type: string) => requestJson<Record<string, unknown>[]>(`/v1/api/flink-common/${type === 'checkpoint' ? 'listcheckpoint' : 'listsavepoint'}?taskId=${id}`);
export const getInstanceInfo = (taskId: number, instanceId: number, kind: 'config' | 'startup-log' | 'runtime-log' | 'runtime' | 'resources' | 'checkpoints' | 'log-components') => requestJson<unknown>(`/v1/api/tasks/${taskId}/instances/${instanceId}/${kind}`);
export const getInstanceLogs = (taskId: number, instanceId: number, component?: string, file?: string) => {
  const query = new URLSearchParams();
  if (component?.startsWith('taskmanager:')) {
    query.set('component', 'taskmanager'); query.set('taskManagerId', component.slice('taskmanager:'.length));
  } else if (component) query.set('component', component);
  if (file) query.set('file', file);
  return requestJson<unknown>(`/v1/api/tasks/${taskId}/instances/${instanceId}/runtime-log?${query}`);
};
export const instanceLogDownloadUrl = (taskId: number, instanceId: number, component?: string, file?: string) => {
  const query = new URLSearchParams(); if (component) query.set('component', component); if (file) query.set('file', file);
  return `/api/realtime/sync-tasks/${taskId}/instances/${instanceId}/logs/download?${query}`;
};
export const stopInstance = (taskId: number, instanceId: number, stopType = 'direct') => requestJson<TaskInstance>(`/v1/api/tasks/${taskId}/instances/${instanceId}/stop`, json('POST', { stopType }));
export const refreshInstance = (taskId: number, instanceId: number) => requestJson<TaskInstance>(`/api/realtime/sync-tasks/${taskId}/instances/${instanceId}/refresh-status`, { method: 'POST' });
export const getSyncProgress = (taskId: number, instanceId: number, refresh = true) => requestJson<SyncProgressSnapshot>(`/api/realtime/sync-tasks/${taskId}/instances/${instanceId}/sync-progress?refresh=${refresh}`);
export const listSyncDirtyRecords = (taskId: number, unresolvedOnly = true, page = 1, pageSize = 20) => requestJson<SyncDirtyRecordPage>(`/api/realtime/sync-tasks/${taskId}/dirty-records?unresolvedOnly=${unresolvedOnly}&page=${page}&pageSize=${pageSize}`);
export const resolveSyncDirtyRecord = (taskId: number, recordId: number) => requestJson<boolean>(`/api/realtime/sync-tasks/${taskId}/dirty-records/${recordId}/resolve`, { method: 'POST' });
export const listSyncSchemaChanges = (taskId: number, refresh = false) => requestJson<SyncSchemaChange[]>(`/api/realtime/sync-tasks/${taskId}/schema-changes?refresh=${refresh}`);
export const applySyncSchemaChange = (taskId: number, eventId: number) => requestJson<Record<string, unknown>>(`/api/realtime/sync-tasks/${taskId}/schema-changes/${eventId}/apply`, { method: 'POST' });

type ServerWire = Omit<RealtimeServer, 'databaseName'> & { database?: string };
const serverRequest = (value: RealtimeServerSave) => ({ ...value, type: 'mysql', database: value.databaseName, databaseName: undefined });
const fromServer = (value: ServerWire): RealtimeServer => ({ ...value, databaseName: value.database });
export const listServers = async () => (await requestJson<ServerWire[]>('/api/servers')).map(fromServer);
export const createServer = async (value: RealtimeServerSave) => fromServer(await requestJson<ServerWire>('/api/servers', json('POST', serverRequest(value))));
export const updateServer = async (id: number, value: RealtimeServerSave) => fromServer(await requestJson<ServerWire>(`/api/servers/${id}/update`, json('POST', serverRequest(value))));
export const deleteServer = (id: number) => requestJson<boolean>(`/api/servers/${id}/delete`, json('POST'));
export const testServer = (id: number) => requestJson<Record<string, unknown>>(`/api/servers/${id}/test-connection`, json('POST'));
export const testServerRequest = (value: RealtimeServerSave) => requestJson<Record<string, unknown>>('/api/servers/test-connection', json('POST', serverRequest(value)));
export const listMysqlTables = (id: number, database: string) => requestJson<string[]>(`/api/servers/${id}/mysql/tables?database=${encodeURIComponent(database)}`);
export const listSyncSourceTables = (serverId: number, database: string, excludeTaskId?: number) => {
  const query = new URLSearchParams({ sourceServerId: String(serverId), database });
  if (excludeTaskId) query.set('excludeTaskId', String(excludeTaskId));
  return requestJson<SyncSourceTableOption[]>(`/api/tasks/sync/source-tables?${query}`);
};
export const getMysqlSchema = (id: number, database: string, table: string) => requestJson<MysqlTableSchema>(`/api/servers/${id}/mysql/table-schema?database=${encodeURIComponent(database)}&table=${encodeURIComponent(table)}`);
export const getCommonColumns = (id: number, database: string, tables: string[]) => requestJson<MysqlColumn[]>(`/api/servers/${id}/mysql/common-columns?database=${encodeURIComponent(database)}&${tables.map((table) => `tables=${encodeURIComponent(table)}`).join('&')}`);

export const listTaskParams = () => requestJson<TaskParam[]>('/v1/api/tasks/params?taskType=sync');
export const getCdcOptions = () => requestJson<{ targetDatabase: string; tablePrefixes: PaimonTablePrefixOption[] }>('/api/paimon/cdc-options');
export const listAlerts = () => requestJson<RealtimeAlert[]>('/api/alerts');
export const acknowledgeAlert = (id: number) => requestJson<boolean>(`/api/alerts/${id}/acknowledge`, { method: 'POST' });
export const listChangeLogs = (taskId?: number) => requestJson<TaskChangeLog[]>(`/api/task-change-logs${taskId ? `?taskId=${taskId}` : ''}`);
export const getChangeLogDetail = (id: number) => requestJson<Record<string, unknown>>(`/api/task-change-logs/${id}/detail`);

export const listRealtimeTables = (query = new URLSearchParams()) => requestJson<{ records: RealtimeTable[]; total: number; page: number; pageSize: number }>(`/api/realtime/tables?${query}`);
export const listAvailableRealtimeTables = () => requestJson<RealtimeTable[]>('/api/realtime/tables/available');
export const listRealtimeDatabases = () => requestJson<string[]>('/api/realtime/tables/databases');
export const getRealtimeTable = (id: number) => requestJson<RealtimeTable>(`/api/realtime/tables/${id}`);
export const createRealtimeTable = (value: RealtimeTableCreateRequest) => requestJson<RealtimeTable>('/api/realtime/tables', json('POST', value));
export const refreshRealtimeTable = (id: number) => requestJson<RealtimeTable>(`/api/realtime/tables/${id}/refresh`, json('POST'));
export const safeUpdateRealtimeTable = (id: number, value: { comment?: string; addColumns?: RealtimeTable['columns']; columnComments?: Array<{ name: string; comment: string }>; options?: Record<string, string> }) => requestJson<RealtimeTable>(`/api/realtime/tables/${id}/safe-update`, json('POST', value));

export const listManagedTasks = async (taskType: ManagedTaskType, query: URLSearchParams) => {
  const page = await requestJson<{ records: ManagedTask[]; total: number; pageNo: number; pageSize: number }>('/v1/api/tasks/page', json('POST', {
    taskType, pageNo: Number(query.get('page') || 1), pageSize: Number(query.get('pageSize') || 20),
    keyword: query.get('keyword') || undefined, status: query.get('status') || undefined,
    owner: query.get('owner') || undefined, lastOperator: query.get('lastOperator') || undefined,
    sourceKeyword: query.get('sourceKeyword') || undefined,
  }));
  return page;
};
export const getManagedTask = (id: number) => requestJson<ManagedTask>(`/v1/api/tasks/${id}/detail`);
export const createManagedTask = (value: ManagedTaskSave) => requestJson<number>('/v1/api/tasks/create', json('POST', value));
export const updateManagedTask = (id: number, value: ManagedTaskSave) => requestJson<number>(`/v1/api/tasks/${id}/update`, json('POST', value));
export const deleteManagedTask = (id: number) => requestJson<void>(`/v1/api/tasks/${id}/delete`, json('POST'));
export const analyzeManagedSql = (value: ManagedTaskSave) => requestJson<{ valid: boolean; inputs: string[]; outputs: string[]; inputTableIds: number[]; outputTableIds: number[]; plan: string }>('/v1/api/tasks/analyze-sql', json('POST', value));
export const debugManagedTask = async (id: number) => { const task = await getManagedTask(id); return requestJson<TaskInstance>('/v1/api/tasks/debug', json('POST', { ...task, taskId: id })); };
export const enableManagedTask = (id: number) => requestJson<TaskInstance>(`/v1/api/tasks/${id}/enable`, json('POST', { startType: 'direct' }));
export const stopManagedTask = (id: number) => requestJson<TaskInstance>(`/v1/api/tasks/${id}/stop`, json('POST', { stopType: 'savepoint' }));
