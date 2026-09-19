import { afterEach, describe, expect, it, vi } from 'vitest';
import { analyzeAssetImpact, getAssetLineageGraph, searchLineageAssets } from './assets';

const response = (data: unknown) => Promise.resolve({
  ok: true, status: 200, json: async () => ({ code: 0, data }),
} as Response);

afterEach(() => vi.unstubAllGlobals());

describe('全局血缘与影响分析 API', () => {
  it('保留资产定位、方向、深度和字段影响参数', async () => {
    const fetchMock = vi.fn(() => response({ records: [], nodes: [], edges: [] }));
    vi.stubGlobal('fetch', fetchMock);

    await searchLineageAssets(new URLSearchParams({ keyword: 'orders', catalog: 'hive' }));
    await getAssetLineageGraph(new URLSearchParams({ catalog: 'hive', database: 'ods', table: 'orders', column: 'id', direction: 'DOWNSTREAM', depth: '3' }));
    await analyzeAssetImpact({ catalog: 'hive', database: 'ods', table: 'orders', columns: ['id'], changeType: 'TYPE_CHANGE' });

    const calls = fetchMock.mock.calls as unknown as Array<[string, RequestInit?]>;
    expect(calls[0]?.[0]).toBe('/api/data-map/catalog/search?keyword=orders&catalog=hive');
    expect(calls[1]?.[0]).toContain('/api/data-map/lineage/graph?catalog=hive&database=ods&table=orders&column=id');
    expect(calls[2]?.[0]).toBe('/api/data-map/impact-analysis');
    expect(JSON.parse(String(calls[2]?.[1]?.body))).toMatchObject({ table: 'orders', columns: ['id'] });
  });
});
