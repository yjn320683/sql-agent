import { requestJson } from './client';
import type { TaskVersionUnionCandidateVO, TaskVersionUnionVO } from '../types';

export interface PageVO<T> { items: T[]; page: number; pageSize: number; total: number }
export interface UnionMemberSaveRequest { taskId: number; versionNo: number; versionRevision: number; ddl?: string }
export interface UnionSaveRequest { revision?: number; unionDdl?: string; members: UnionMemberSaveRequest[] }

export const listTaskVersionUnions = (page = 1, pageSize = 20, keyword = ''): Promise<PageVO<TaskVersionUnionVO>> => {
  const query = new URLSearchParams({ page: String(page), pageSize: String(pageSize), keyword });
  return requestJson<PageVO<TaskVersionUnionVO>>(`/api/task-version-unions?${query.toString()}`);
};
export const listTaskVersionUnionCandidates = (page = 1, pageSize = 20, keyword = ''): Promise<PageVO<TaskVersionUnionCandidateVO>> => {
  const query = new URLSearchParams({ page: String(page), pageSize: String(pageSize), keyword });
  return requestJson<PageVO<TaskVersionUnionCandidateVO>>(`/api/task-version-unions/candidates?${query.toString()}`);
};
export const getTaskVersionUnion = (id: number): Promise<TaskVersionUnionVO> =>
  requestJson<TaskVersionUnionVO>(`/api/task-version-unions/${id}`);
export const createTaskVersionUnion = (body: UnionSaveRequest): Promise<TaskVersionUnionVO> =>
  requestJson<TaskVersionUnionVO>('/api/task-version-unions', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  });
export const updateTaskVersionUnion = (id: number, body: UnionSaveRequest): Promise<TaskVersionUnionVO> =>
  requestJson<TaskVersionUnionVO>(`/api/task-version-unions/${id}`, {
    method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  });
export const publishTaskVersionUnion = (id: number, revision: number): Promise<TaskVersionUnionVO> =>
  requestJson<TaskVersionUnionVO>(`/api/task-version-unions/${id}/publish`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ revision }),
  });
