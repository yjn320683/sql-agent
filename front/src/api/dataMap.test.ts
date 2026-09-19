import { afterEach, describe, expect, it, vi } from 'vitest';
import { getDataMapCoverage, getDataMapOverview, getDataMapReadiness, getDataMapRuns, rebuildDataMap, reprojectDataMap } from './dataMap';

const response = (data: unknown) => Promise.resolve({
  ok: true, status: 200, json: async () => ({ code: 0, data }),
} as Response);

afterEach(() => vi.unstubAllGlobals());

describe('数据地图 API', () => {
  it('使用独立模块的概览、解析监控和可靠投影入口', async () => {
    const fetchMock = vi.fn(() => response({ records: [] }));
    vi.stubGlobal('fetch', fetchMock);

    await getDataMapOverview();
    await getDataMapReadiness();
    await getDataMapCoverage();
    await getDataMapRuns(2, 50);
    await reprojectDataMap();
    await rebuildDataMap();

    const calls = fetchMock.mock.calls as unknown as Array<[string, RequestInit?]>;
    expect(calls.map(([url]) => url)).toEqual([
      '/api/data-map/overview',
      '/api/data-map/graph/readiness',
      '/api/data-map/parsing/coverage',
      '/api/data-map/parsing/runs?page=2&pageSize=50',
      '/api/data-map/graph/reproject',
      '/api/data-map/graph/rebuild',
    ]);
    expect(calls[4]?.[1]).toMatchObject({ method: 'POST' });
    expect(calls[5]?.[1]).toMatchObject({ method: 'POST' });
  });
});
