import { requestJson } from './client';
import type {
  HiveColumnsVO,
  HiveDatabaseListVO,
  HiveDdlVO,
  HiveExplainVO,
  HiveFunctionDetailVO,
  HiveFunctionSearchVO,
  HivePartitionsVO,
  HiveStatisticsVO,
  HiveStorageLayoutVO,
  HiveTableFreshnessVO,
  HiveTableDetailVO,
  HiveTableSearchVO,
  HiveValidationVO,
  PlatformHealthVO,
  TaskLineageVO,
  TaskDependenciesVO,
  TaskQualityVO,
  SqlCompletionVO,
  SqlStructurePreviewVO,
  SqlQueryPreviewVO,
  SqlTaskParameter,
  DataMapPrimaryKeysVO,
} from '../types';

interface SqlStructurePreviewRequest {
  sql: string;
  parameterSchema: SqlTaskParameter[];
  parameters?: Record<string, unknown>;
  businessDate?: string;
  validateParameterValues: boolean;
}

export interface SqlQueryPreviewRequest extends SqlStructurePreviewRequest {
  stepNo: number;
  limit?: number;
  defaultDb?: string;
}

export function completeSql(
  sql: string,
  cursor: number,
  defaultDb?: string,
  signal?: AbortSignal,
): Promise<SqlCompletionVO> {
  return requestJson<SqlCompletionVO>('/api/workspace/sql/completions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sql, cursor, defaultDb, limit: 100 }),
    signal,
  });
}

export function previewSqlStructure(
  body: SqlStructurePreviewRequest,
  signal?: AbortSignal,
): Promise<SqlStructurePreviewVO> {
  return requestJson<SqlStructurePreviewVO>('/api/workspace/sql/structure', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal,
  });
}

export function previewSqlQuery(body: SqlQueryPreviewRequest): Promise<SqlQueryPreviewVO> {
  return requestJson<SqlQueryPreviewVO>('/api/workspace/sql/preview', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
}

export function validateHiveDdl(ddl: string): Promise<{ valid: boolean; affectedTables: string[]; executed: boolean }> {
  return requestJson('/api/workspace/sql/ddl/validate', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ddl }),
  });
}

export const getPlatformHealth = (): Promise<PlatformHealthVO> =>
  requestJson<PlatformHealthVO>('/api/workspace/platform/health');

export function getTaskLineage(taskId: number, defaultDb?: string, versionNo?: number): Promise<TaskLineageVO> {
  const params = new URLSearchParams();
  if (defaultDb) params.set('defaultDb', defaultDb);
  if (versionNo) params.set('versionNo', String(versionNo));
  return requestJson<TaskLineageVO>(`/api/workspace/tasks/${taskId}/lineage?${params.toString()}`);
}

export function reanalyzeTaskLineage(taskId: number, defaultDb?: string, versionNo?: number): Promise<TaskLineageVO> {
  const params = new URLSearchParams();
  if (defaultDb) params.set('defaultDb', defaultDb);
  if (versionNo) params.set('versionNo', String(versionNo));
  return requestJson<TaskLineageVO>(`/api/workspace/tasks/${taskId}/lineage/reanalyze?${params.toString()}`, { method: 'POST' });
}

export function getTaskDependencies(taskId: number, defaultDb?: string, versionNo?: number): Promise<TaskDependenciesVO> {
  const params = new URLSearchParams();
  if (defaultDb) params.set('defaultDb', defaultDb);
  if (versionNo) params.set('versionNo', String(versionNo));
  return requestJson<TaskDependenciesVO>(
    `/api/workspace/tasks/${taskId}/dependencies?${params.toString()}`,
  );
}

export function checkTaskQuality(taskId: number, defaultDb?: string, versionNo?: number): Promise<TaskQualityVO> {
  const params = new URLSearchParams();
  if (defaultDb) params.set('defaultDb', defaultDb);
  if (versionNo) params.set('versionNo', String(versionNo));
  return requestJson<TaskQualityVO>(
    `/api/workspace/tasks/${taskId}/quality?${params.toString()}`,
    { method: 'POST' },
  );
}

export const listHiveDatabases = (): Promise<HiveDatabaseListVO> =>
  requestJson<HiveDatabaseListVO>('/api/workspace/hive/databases');

export const getDataMapPrimaryKeys = (db: string, table: string): Promise<DataMapPrimaryKeysVO> =>
  requestJson<DataMapPrimaryKeysVO>(
    `/api/workspace/data-map/tables/${encodeURIComponent(db)}/${encodeURIComponent(table)}/primary-keys`,
  );

export function searchHiveFunctions(
  keyword = '',
  page = 1,
  pageSize = 50,
  defaultDb?: string,
): Promise<HiveFunctionSearchVO> {
  const params = new URLSearchParams({
    keyword,
    limit: String(pageSize),
    offset: String((page - 1) * pageSize),
  });
  if (defaultDb) params.set('defaultDb', defaultDb);
  return requestJson<HiveFunctionSearchVO>(`/api/workspace/hive/functions?${params.toString()}`);
}

export function getHiveFunction(name: string, defaultDb?: string): Promise<HiveFunctionDetailVO> {
  const params = new URLSearchParams();
  if (defaultDb) params.set('defaultDb', defaultDb);
  return requestJson<HiveFunctionDetailVO>(
    `/api/workspace/hive/functions/${encodeURIComponent(name)}?${params.toString()}`,
  );
}

export function searchHiveTables(db: string | undefined, pattern = '', limit = 100, offset = 0): Promise<HiveTableSearchVO> {
  const params = new URLSearchParams({ pattern, limit: String(limit), offset: String(offset) });
  if (db) params.set('db', db);
  return requestJson<HiveTableSearchVO>(`/api/workspace/hive/tables?${params.toString()}`);
}

export const getHiveColumns = (db: string, table: string): Promise<HiveColumnsVO> =>
  requestJson<HiveColumnsVO>(
    `/api/workspace/hive/tables/${encodeURIComponent(db)}/${encodeURIComponent(table)}/columns`,
  );

const tablePath = (db: string, table: string) =>
  `/api/workspace/hive/tables/${encodeURIComponent(db)}/${encodeURIComponent(table)}`;

export const getHiveTable = (db: string, table: string): Promise<HiveTableDetailVO> =>
  requestJson<HiveTableDetailVO>(tablePath(db, table));

export function getHivePartitions(
  db: string,
  table: string,
  page: number,
  pageSize: number,
): Promise<HivePartitionsVO> {
  const params = new URLSearchParams({
    limit: String(pageSize),
    offset: String((page - 1) * pageSize),
  });
  return requestJson<HivePartitionsVO>(`${tablePath(db, table)}/partitions?${params.toString()}`);
}

export const getHiveTableDdl = (db: string, table: string): Promise<HiveDdlVO> =>
  requestJson<HiveDdlVO>(`${tablePath(db, table)}/ddl`);

export function getHiveTableStatistics(
  db: string,
  table: string,
  columns: string[],
): Promise<HiveStatisticsVO> {
  const params = new URLSearchParams();
  columns.slice(0, 50).forEach((column) => params.append('columns', column));
  return requestJson<HiveStatisticsVO>(`${tablePath(db, table)}/statistics?${params.toString()}`);
}

export const getHiveStorageLayout = (db: string, table: string): Promise<HiveStorageLayoutVO> =>
  requestJson<HiveStorageLayoutVO>(`${tablePath(db, table)}/storage-layout?maxFiles=1000`);

export const getHiveTableFreshness = (db: string, table: string): Promise<HiveTableFreshnessVO> =>
  requestJson<HiveTableFreshnessVO>(
    `${tablePath(db, table)}/freshness?partitionScanLimit=2000&pathSampleLimit=20`,
  );

export function validateTaskSql(taskId: number, defaultDb?: string, versionNo?: number): Promise<HiveValidationVO> {
  const params = new URLSearchParams();
  if (defaultDb) params.set('defaultDb', defaultDb);
  if (versionNo) params.set('versionNo', String(versionNo));
  return requestJson<HiveValidationVO>(
    `/api/workspace/tasks/${taskId}/validate?${params.toString()}`,
    { method: 'POST' },
  );
}

export function explainTaskSql(taskId: number, defaultDb?: string, versionNo?: number): Promise<HiveExplainVO> {
  const params = new URLSearchParams({ extended: 'false' });
  if (defaultDb) params.set('defaultDb', defaultDb);
  if (versionNo) params.set('versionNo', String(versionNo));
  return requestJson<HiveExplainVO>(
    `/api/workspace/tasks/${taskId}/explain?${params.toString()}`,
    { method: 'POST' },
  );
}
