import { requestJson } from '../api/client';
import type {
  BusinessDomain,
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
  SyncSourceTableOption,
} from './types';

const json = (method: string, body?: unknown): RequestInit => ({
  method,
  headers: { 'Content-Type': 'application/json; charset=UTF-8' },
  body: body === undefined ? undefined : JSON.stringify(body),
});

export const listSyncTasks = (query: URLSearchParams) => requestJson<SyncTaskPage>(`/api/realtime/sync-tasks?${query}`);
export const getSyncTask = (id: number) => requestJson<SyncTask>(`/api/realtime/sync-tasks/${id}`);
export const createSyncTask = (value: SyncTaskSave) => requestJson<SyncTask>('/api/realtime/sync-tasks', json('POST', value));
export const updateSyncTask = (id: number, value: SyncTaskSave) => requestJson<SyncTask>(`/api/realtime/sync-tasks/${id}`, json('PUT', value));
export const deleteSyncTask = (id: number) => requestJson<boolean>(`/api/realtime/sync-tasks/${id}`, { method: 'DELETE' });
export const previewSyncTask = (value: SyncTaskSave, excludeTaskId?: number) => requestJson<{ command: string; arguments: string[] }>(`/api/realtime/sync-tasks/command-preview${excludeTaskId ? `?excludeTaskId=${excludeTaskId}` : ''}`, json('POST', value));
export const previewSavedSyncTask = (id: number, debug = false, value?: Record<string, unknown>) => requestJson<{ command: string; arguments: string[] }>(`/api/realtime/sync-tasks/${id}/${debug ? 'debug-command-preview' : 'command-preview'}`, debug ? json('POST', value ?? {}) : undefined);
export const startSyncTask = (id: number, value: { startType: string; statePath?: string; dryRun?: boolean; parallelism?: number; checkpointInterval?: number; taskManagerMemory?: string; jobManagerMemory?: string; flinkConfOverrides?: Record<string, string>; mysqlConfOverrides?: Record<string, string>; tableConfOverrides?: Record<string, string> }, debug = false) => requestJson<TaskInstance>(`/api/realtime/sync-tasks/${id}/${debug ? 'debug' : 'start'}`, json('POST', value));
export const stopSyncTask = (id: number, value: { stopType: string }) => requestJson<TaskInstance>(`/api/realtime/sync-tasks/${id}/stop`, json('POST', value));
export const refreshSyncTask = (id: number) => requestJson<TaskInstance>(`/api/realtime/sync-tasks/${id}/refresh-status`, { method: 'POST' });
export const listMappings = (id: number) => requestJson<TaskMapping[]>(`/api/realtime/sync-tasks/${id}/table-mappings`);
export const listInstances = (id: number) => requestJson<TaskInstance[]>(`/api/realtime/sync-tasks/${id}/instances`);
export const listVersions = (id: number) => requestJson<Record<string, unknown>[]>(`/api/realtime/sync-tasks/${id}/versions`);
export const getVersionConfig = (id: number, versionId: number) => requestJson<Record<string, unknown>>(`/api/realtime/sync-tasks/${id}/versions/${versionId}/config`);
export const getStateHistory = (id: number, type: string) => requestJson<Record<string, unknown>[]>(`/api/realtime/sync-tasks/${id}/state-history?type=${encodeURIComponent(type)}`);
export const getInstanceInfo = (taskId: number, instanceId: number, kind: 'config' | 'startup-log' | 'runtime-log' | 'runtime' | 'resources' | 'checkpoints' | 'log-components') => requestJson<unknown>(`/api/realtime/sync-tasks/${taskId}/instances/${instanceId}/${kind}`);
export const getInstanceLogs = (taskId: number, instanceId: number, component?: string, file?: string) => {
  const query = new URLSearchParams(); if (component) query.set('component', component); if (file) query.set('file', file);
  return requestJson<unknown>(`/api/realtime/sync-tasks/${taskId}/instances/${instanceId}/logs?${query}`);
};
export const instanceLogDownloadUrl = (taskId: number, instanceId: number, component?: string, file?: string) => {
  const query = new URLSearchParams(); if (component) query.set('component', component); if (file) query.set('file', file);
  return `/api/realtime/sync-tasks/${taskId}/instances/${instanceId}/logs/download?${query}`;
};
export const stopInstance = (taskId: number, instanceId: number, stopType = 'direct') => requestJson<TaskInstance>(`/api/realtime/sync-tasks/${taskId}/instances/${instanceId}/stop`, json('POST', { stopType }));
export const refreshInstance = (taskId: number, instanceId: number) => requestJson<TaskInstance>(`/api/realtime/sync-tasks/${taskId}/instances/${instanceId}/refresh-status`, { method: 'POST' });

export const listServers = () => requestJson<RealtimeServer[]>('/api/realtime/servers');
export const createServer = (value: RealtimeServerSave) => requestJson<RealtimeServer>('/api/realtime/servers', json('POST', value));
export const updateServer = (id: number, value: RealtimeServerSave) => requestJson<RealtimeServer>(`/api/realtime/servers/${id}`, json('PUT', value));
export const deleteServer = (id: number) => requestJson<boolean>(`/api/realtime/servers/${id}`, { method: 'DELETE' });
export const testServer = (id: number) => requestJson<Record<string, unknown>>(`/api/realtime/servers/${id}/test-connection`, { method: 'POST' });
export const testServerRequest = (value: RealtimeServerSave) => requestJson<Record<string, unknown>>('/api/realtime/servers/test-connection', json('POST', value));
export const listMysqlTables = (id: number) => requestJson<string[]>(`/api/realtime/servers/${id}/mysql/tables`);
export const listSyncSourceTables = (serverId: number, excludeTaskId?: number) => requestJson<SyncSourceTableOption[]>(`/api/realtime/sync-tasks/source-tables?serverId=${serverId}${excludeTaskId ? `&excludeTaskId=${excludeTaskId}` : ''}`);
export const getMysqlSchema = (id: number, table: string) => requestJson<MysqlTableSchema>(`/api/realtime/servers/${id}/mysql/table-schema?table=${encodeURIComponent(table)}`);
export const getCommonColumns = (id: number, tables: string[]) => requestJson<string[]>(`/api/realtime/servers/${id}/mysql/common-columns?${tables.map((table) => `tables=${encodeURIComponent(table)}`).join('&')}`);

export const listTaskParams = () => requestJson<TaskParam[]>('/api/realtime/sync-params');
export const getCdcOptions = () => requestJson<{ targetDatabase: string; domains: BusinessDomain[] }>('/api/realtime/paimon/cdc-options');
export const listAlerts = () => requestJson<RealtimeAlert[]>('/api/realtime/alerts');
export const acknowledgeAlert = (id: number) => requestJson<boolean>(`/api/realtime/alerts/${id}/acknowledge`, { method: 'POST' });
export const listChangeLogs = (taskId?: number) => requestJson<TaskChangeLog[]>(`/api/realtime/task-change-logs${taskId ? `?taskId=${taskId}` : ''}`);
export const getChangeLogDetail = (id: number) => requestJson<Record<string, unknown>>(`/api/realtime/task-change-logs/${id}/detail`);
