import type { BaseResponse } from '../types';

export class ApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly code?: number,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

export async function requestJson<T>(url: string, init?: RequestInit): Promise<T> {
  const resp = await fetch(url, {
    ...init,
    credentials: 'include',
  });
  const body = await resp.json().catch(() => null) as BaseResponse<T> | null;
  if (!resp.ok) {
    if (resp.status === 401 && typeof window !== 'undefined') {
      window.dispatchEvent(new CustomEvent('sql-agent:unauthorized'));
    }
    throw new ApiError(body?.message || `请求失败（HTTP ${resp.status}）`, resp.status, body?.code);
  }
  if (!body) throw new ApiError('服务返回了无法解析的数据', resp.status);
  if (body.code !== 0) throw new ApiError(body.message || `业务错误（${body.code}）`, resp.status, body.code);
  return body.data;
}
