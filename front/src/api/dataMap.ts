import { requestJson } from './client';

export interface DataMapRun {
  id: number;
  run_type: 'INCREMENTAL' | 'FULL' | 'REPROJECT';
  status: 'RUNNING' | 'SUCCEEDED' | 'PARTIAL' | 'FAILED';
  scanned_count: number;
  success_count: number;
  partial_count: number;
  failed_count: number;
  projected_count: number;
  generation_no?: number;
  error_message?: string;
  started_at: string;
  finished_at?: string;
}

export interface DataMapOverview {
  graphConfigured: boolean;
  graphAvailable: boolean;
  graphSchemaReady: boolean;
  activeGeneration: number;
  projection: DataMapProjectionCounts;
  assetCount: number;
  columnCount: number;
  taskCount: number;
  pendingProjection: number;
  completeTasks: number;
  partialTasks: number;
  latestRuns: DataMapRun[];
}

export interface DataMapProjectionCounts {
  pending: number;
  processing: number;
  stale: number;
  failed: number;
  succeeded: number;
}

export interface DataMapReadiness {
  configured: boolean;
  available: boolean;
  schemaReady: boolean;
  ready: boolean;
  activeGeneration: number;
  buildingGenerations: number[];
  projection: DataMapProjectionCounts;
}

export interface DataMapCoverage {
  complete: number;
  partial: number;
  failed: number;
  pendingProjection: number;
}

export interface DataMapRunPage {
  records: DataMapRun[];
  total: number;
  page: number;
  pageSize: number;
}

export const getDataMapOverview = () => requestJson<DataMapOverview>('/api/data-map/overview');
export const getDataMapReadiness = () => requestJson<DataMapReadiness>('/api/data-map/graph/readiness');
export const getDataMapCoverage = () => requestJson<DataMapCoverage>('/api/data-map/parsing/coverage');
export const getDataMapRuns = (page = 1, pageSize = 20) => requestJson<DataMapRunPage>(`/api/data-map/parsing/runs?page=${page}&pageSize=${pageSize}`);
export const rebuildDataMap = () => requestJson<{ accepted: boolean; generation: number; message: string }>('/api/data-map/graph/rebuild', { method: 'POST' });
export const reprojectDataMap = () => requestJson<{ projected: number; generation: number }>('/api/data-map/graph/reproject', { method: 'POST' });
