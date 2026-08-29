import type {
  SessionManageQuery,
  SessionSortBy,
  SessionStatus,
  SortOrder,
} from '../types';

function isStatus(value: string | null): value is SessionStatus {
  return value === 'active' || value === 'archived' || value === 'all';
}

function isSortBy(value: string | null): value is SessionSortBy {
  return value === 'createdAt' || value === 'lastActiveAt';
}

function isSortOrder(value: string | null): value is SortOrder {
  return value === 'asc' || value === 'desc';
}

function positiveInteger(value: string | null, fallback: number, max?: number): number {
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed < 1 || (max && parsed > max)) return fallback;
  return parsed;
}

export function queryFromParams(params: URLSearchParams): SessionManageQuery {
  const status = params.get('status');
  const sortBy = params.get('sortBy');
  const sortOrder = params.get('sortOrder');
  return {
    status: isStatus(status) ? status : 'active',
    keyword: params.get('keyword')?.trim() ?? '',
    page: positiveInteger(params.get('page'), 1),
    pageSize: positiveInteger(params.get('pageSize'), 20, 100),
    sortBy: isSortBy(sortBy) ? sortBy : 'lastActiveAt',
    sortOrder: isSortOrder(sortOrder) ? sortOrder : 'desc',
  };
}

export function queryToParams(query: SessionManageQuery): URLSearchParams {
  return new URLSearchParams({
    status: query.status,
    ...(query.keyword ? { keyword: query.keyword } : {}),
    page: String(query.page),
    pageSize: String(query.pageSize),
    sortBy: query.sortBy,
    sortOrder: query.sortOrder,
  });
}
