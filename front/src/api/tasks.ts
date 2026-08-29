import { requestJson } from './client';
import type {
  ExecutionLogChunkVO,
  ExecutionCenterStatus,
  ExecutionSummaryVO,
  SqlTaskPageVO,
  SqlTaskSaveRequest,
  SqlTaskVO,
  SqlTaskVersionPageVO,
  SqlTaskVersionSaveRequest,
  SqlTaskVersionVO,
  TaskExecutionPageVO,
  TaskExecutionDiagnosticsVO,
  TaskExecutionStatus,
  TaskExecutionVO,
  TaskExecutionCreateRequest,
} from '../types';

export function listTasks(keyword = '', page = 1, pageSize = 20, status = 'active', updatedBy = ''): Promise<SqlTaskPageVO> {
  const params = new URLSearchParams({ keyword, page: String(page), pageSize: String(pageSize), status, updatedBy });
  return requestJson<SqlTaskPageVO>(`/api/tasks?${params.toString()}`);
}

export const getTask = (taskId: number): Promise<SqlTaskVO> =>
  requestJson<SqlTaskVO>(`/api/tasks/${taskId}`);

export const createTask = (body: SqlTaskSaveRequest): Promise<SqlTaskVO> =>
  requestJson<SqlTaskVO>('/api/tasks', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  });

export const updateTask = (taskId: number, body: SqlTaskSaveRequest): Promise<SqlTaskVO> =>
  requestJson<SqlTaskVO>(`/api/tasks/${taskId}`, {
    method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  });

export const setTaskEnabled = (taskId: number, enabled: boolean, revision: number): Promise<SqlTaskVO> =>
  requestJson<SqlTaskVO>(`/api/tasks/${taskId}/${enabled ? 'enable' : 'disable'}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ revision }),
  });

export function listTaskVersions(taskId: number, page = 1, pageSize = 20, keyword = ''): Promise<SqlTaskVersionPageVO> {
  const params = new URLSearchParams({ page: String(page), pageSize: String(pageSize) });
  if (keyword.trim()) params.set('keyword', keyword.trim());
  return requestJson<SqlTaskVersionPageVO>(`/api/tasks/${taskId}/versions?${params.toString()}`);
}

export const getTaskVersion = (taskId: number, versionNo: number): Promise<SqlTaskVersionVO> =>
  requestJson<SqlTaskVersionVO>(`/api/tasks/${taskId}/versions/${versionNo}`);

export const createTaskVersion = (taskId: number, note: string, revision: number): Promise<SqlTaskVersionVO> =>
  requestJson<SqlTaskVersionVO>(`/api/tasks/${taskId}/versions`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ note, revision }),
  });

export const updateTaskVersion = (taskId: number, versionNo: number, body: SqlTaskVersionSaveRequest): Promise<SqlTaskVersionVO> =>
  requestJson<SqlTaskVersionVO>(`/api/tasks/${taskId}/versions/${versionNo}`, {
    method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  });

export const activateTaskVersion = (taskId: number, versionNo: number, taskRevision: number, versionRevision: number): Promise<SqlTaskVersionVO> =>
  requestJson<SqlTaskVersionVO>(`/api/tasks/${taskId}/versions/${versionNo}/activate`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ taskRevision, versionRevision }),
  });

export const executeTask = (taskId: number, body: TaskExecutionCreateRequest): Promise<TaskExecutionVO> =>
  requestJson<TaskExecutionVO>(`/api/tasks/${taskId}/executions`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  });

const revisionAction = (taskId: number, action: 'archive' | 'restore', revision: number): Promise<SqlTaskVO> =>
  requestJson<SqlTaskVO>(`/api/tasks/${taskId}/${action}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ revision }),
  });

export const archiveTask = (taskId: number, revision: number) => revisionAction(taskId, 'archive', revision);
export const restoreTask = (taskId: number, revision: number) => revisionAction(taskId, 'restore', revision);
export const cloneTask = (taskId: number): Promise<SqlTaskVO> =>
  requestJson<SqlTaskVO>(`/api/tasks/${taskId}/clone`, { method: 'POST' });

export function listTaskExecutions(
  taskId: number,
  status: TaskExecutionStatus | 'all' = 'all',
  page = 1,
  pageSize = 20,
  keyword = '',
): Promise<TaskExecutionPageVO> {
  const params = new URLSearchParams({ status, page: String(page), pageSize: String(pageSize) });
  if (keyword.trim()) params.set('keyword', keyword.trim());
  return requestJson<TaskExecutionPageVO>(`/api/tasks/${taskId}/executions?${params.toString()}`);
}

export const getTaskExecution = (executionId: number): Promise<TaskExecutionVO> =>
  requestJson<TaskExecutionVO>(`/api/task-executions/${executionId}`);

export const getTaskExecutionDiagnostics = (executionId: number): Promise<TaskExecutionDiagnosticsVO> =>
  requestJson<TaskExecutionDiagnosticsVO>(`/api/workspace/task-executions/${executionId}/diagnostics`);

export function listExecutions(
  status: ExecutionCenterStatus = 'all',
  keyword = '',
  page = 1,
  pageSize = 20,
): Promise<TaskExecutionPageVO> {
  const params = new URLSearchParams({
    status,
    keyword,
    page: String(page),
    pageSize: String(pageSize),
  });
  return requestJson<TaskExecutionPageVO>(`/api/task-executions?${params.toString()}`);
}

export const getExecutionSummary = (): Promise<ExecutionSummaryVO> =>
  requestJson<ExecutionSummaryVO>('/api/task-executions/summary');

export const cancelTaskExecution = (executionId: number): Promise<void> =>
  requestJson<void>(`/api/task-executions/${executionId}/cancel`, { method: 'POST' });

export const getExecutionLogs = (
  executionId: number,
  offset: number,
  limit = 65536,
): Promise<ExecutionLogChunkVO> =>
  requestJson<ExecutionLogChunkVO>(
    `/api/task-executions/${executionId}/logs?offset=${offset}&limit=${limit}`,
  );

export const getExecutionStepLogs = (
  executionId: number,
  stepNo: number,
  offset: number,
  limit = 65536,
): Promise<ExecutionLogChunkVO> =>
  requestJson<ExecutionLogChunkVO>(
    `/api/task-executions/${executionId}/steps/${stepNo}/logs?offset=${offset}&limit=${limit}`,
  );
