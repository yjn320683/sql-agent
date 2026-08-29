import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, requestJson } from './client';

describe('requestJson', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('保留 HTTP 状态和后端错误信息', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(
      JSON.stringify({ code: 404, message: '接口不存在', data: null }),
      { status: 404, headers: { 'Content-Type': 'application/json' } },
    )));

    const error = await requestJson('/api/missing').catch((reason) => reason);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 404, code: 404, message: '接口不存在' });
  });

  it('非 JSON 错误响应使用可读的 HTTP 提示', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('Not Found', { status: 404 })));

    await expect(requestJson('/api/missing')).rejects.toMatchObject({
      status: 404,
      message: '请求失败（HTTP 404）',
    });
  });
});
