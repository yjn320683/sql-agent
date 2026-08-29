import { requestJson } from './client';
import type {
  DataCompareCreateRequest,
  DataCompareDetailVO,
  DataComparePageVO,
  DataComparePlanVO,
  DataComparePrepareVO,
  DataCompareReportVO,
  DataCompareRule,
  DataCompareTableVO,
} from '../types';

export interface DataCompareListQuery {
  status?: string;
  type?: string;
  page?: number;
  pageSize?: number;
  taskId?: number;
  versionNo?: number;
  jobId?: number;
  operator?: string;
  fromTime?: string;
  toTime?: string;
  mine?: boolean;
  keyword?: string;
}

export function listDataCompares(query: DataCompareListQuery = {}): Promise<DataComparePageVO> {
  const params = new URLSearchParams({
    status: query.status || 'all',
    type: query.type || 'all',
    page: String(query.page || 1),
    pageSize: String(query.pageSize || 20),
  });
  if (query.taskId) params.set('taskId', String(query.taskId));
  if (query.versionNo) params.set('versionNo', String(query.versionNo));
  if (query.jobId) params.set('jobId', String(query.jobId));
  if (query.operator?.trim()) params.set('operator', query.operator.trim());
  if (query.fromTime) params.set('fromTime', query.fromTime);
  if (query.toTime) params.set('toTime', query.toTime);
  if (query.mine) params.set('mine', 'true');
  if (query.keyword?.trim()) params.set('keyword', query.keyword.trim());
  return requestJson<DataComparePageVO>(`/api/data-compares?${params.toString()}`);
}

export const prepareVersionCompare = (
  taskId: number,
  candidateVersionNo: number,
): Promise<DataComparePrepareVO> => requestJson<DataComparePrepareVO>('/api/data-compares/prepare-version', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ taskId, candidateVersionNo }),
});

export const generateVersionComparePlan = (
  taskId: number,
  candidateVersionNo: number,
  baselineSteps: string[],
  candidateSteps: string[],
): Promise<DataComparePlanVO> => requestJson<DataComparePlanVO>('/api/data-compares/generate-version-sql', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ taskId, candidateVersionNo, baselineSteps, candidateSteps }),
});

export const createDataCompare = (body: DataCompareCreateRequest): Promise<DataCompareDetailVO> =>
  requestJson<DataCompareDetailVO>('/api/data-compares', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  });

export const getDataCompare = (id: number): Promise<DataCompareDetailVO> =>
  requestJson<DataCompareDetailVO>(`/api/data-compares/${id}`);

export const getDataCompareReport = (id: number, page = 1, pageSize = 20, keyword = ''): Promise<DataCompareReportVO> => {
  const params = new URLSearchParams({ page: String(page), pageSize: String(pageSize) });
  if (keyword.trim()) params.set('keyword', keyword.trim());
  return requestJson<DataCompareReportVO>(`/api/data-compares/${id}/report?${params.toString()}`);
};

export const updateDataCompareRule = (id: number, rule: DataCompareRule): Promise<DataCompareTableVO> =>
  requestJson<DataCompareTableVO>(`/api/data-compares/tables/${id}/rule`, {
    method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ rule }),
  });

export const rerunDataCompareTable = (id: number): Promise<DataCompareTableVO> =>
  requestJson<DataCompareTableVO>(`/api/data-compares/tables/${id}/rerun`, { method: 'POST' });

export const forcePassDataCompareTable = (id: number, reason: string): Promise<DataCompareTableVO> =>
  requestJson<DataCompareTableVO>(`/api/data-compares/tables/${id}/force-pass`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ reason }),
  });

export const cancelDataCompare = (id: number): Promise<{ accepted: boolean }> =>
  requestJson<{ accepted: boolean }>(`/api/data-compares/${id}/cancel`, { method: 'POST' });

export const getDataCompareTableLog = (id: number): Promise<{ content: string }> =>
  requestJson<{ content: string }>(`/api/data-compares/tables/${id}/logs`);
