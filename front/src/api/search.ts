import { requestJson } from './client';

export type GlobalSearchType =
  | 'OFFLINE_TASK'
  | 'REALTIME_TASK'
  | 'HIVE_TABLE'
  | 'REALTIME_TABLE'
  | 'OFFLINE_EXECUTION'
  | 'REALTIME_INSTANCE'
  | 'CHAT_SESSION'
  | 'REALTIME_ALERT';

export interface GlobalSearchItem {
  type: GlobalSearchType;
  id: string | number;
  title: string;
  subtitle?: string;
  status?: string;
  mode: 'offline' | 'realtime';
  route: string;
  updatedAt?: string;
}

export interface GlobalSearchGroup {
  type: GlobalSearchType;
  label: string;
  total: number;
  page: number;
  pageSize: number;
  items: GlobalSearchItem[];
  error?: string;
}

export interface GlobalSearchResult {
  keyword: string;
  generatedAt: string;
  groups: GlobalSearchGroup[];
  partialFailures: string[];
}

export function globalSearch(
  keyword: string,
  type?: GlobalSearchType,
  page = 1,
  pageSize = 5,
): Promise<GlobalSearchResult> {
  const params = new URLSearchParams({ keyword, page: String(page), pageSize: String(pageSize) });
  if (type) params.set('type', type);
  return requestJson<GlobalSearchResult>(`/api/search?${params.toString()}`);
}
