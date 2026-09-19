import { expect, test, type Page } from '@playwright/test';

const response = (data: unknown) => ({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, data }) });

async function mockBackend(page: Page) {
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (!path.startsWith('/api/')) return route.continue();
    if (path === '/api/auth/me') return route.fulfill(response({ obId: '900001', displayName: '视觉回归' }));
    if (path === '/api/data-map/overview') return route.fulfill(response({ graphConfigured: true, graphAvailable: true, graphSchemaReady: true, activeGeneration: 1, projection: { pending: 0, processing: 0, stale: 0, failed: 0, succeeded: 12 }, assetCount: 8, columnCount: 42, taskCount: 6, pendingProjection: 0, completeTasks: 6, partialTasks: 0, latestRuns: [] }));
    if (path === '/api/data-map/graph/readiness') return route.fulfill(response({ configured: true, available: true, schemaReady: true, ready: true, activeGeneration: 1, buildingGenerations: [], projection: { pending: 0, processing: 0, stale: 0, failed: 0, succeeded: 12 } }));
    if (path === '/api/data-map/parsing/coverage') return route.fulfill(response({ complete: 6, partial: 0, failed: 0, pendingProjection: 0 }));
    if (path === '/api/data-map/parsing/runs') return route.fulfill(response({ records: [], total: 0, page: 1, pageSize: 20 }));
    if (path === '/api/servers' || path.endsWith('/options') || path.endsWith('/databases') || path === '/api/alert-rules') return route.fulfill(response([]));
    if (path.includes('/api/data-compares')) return route.fulfill(response({ items: [], total: 0, page: 1, pageSize: 20 }));
    if (path.includes('/api/realtime/tables')) return route.fulfill(response({ records: [], total: 0, page: 1, pageSize: 20 }));
    if (path.includes('/api/alerts/page')) return route.fulfill(response({ records: [], total: 0, page: 1, pageSize: 20 }));
    if (path.includes('/api/realtime/sync-tasks') || path.includes('/api/realtime/managed-tasks')) return route.fulfill(response({ records: [], total: 0, page: 1, pageSize: 20 }));
    return route.fulfill(response([]));
  });
  await page.route('**/v1/**', (route) => {
    const path = new URL(route.request().url()).pathname;
    if (!path.startsWith('/v1/')) return route.continue();
    if (path === '/v1/api/tasks/page') {
      return route.fulfill(response({ records: [], total: 0, pageNo: 1, pageSize: 20 }));
    }
    return route.fulfill(response([]));
  });
}

for (const route of ['/data-compares', '/realtime/sync-tasks', '/realtime/compute', '/realtime/export', '/realtime/paimon-tables', '/realtime/alerts', '/data-map', '/data-map/parsing']) {
  test(`${route} 使用统一的内收列表边界`, async ({ page }) => {
    await mockBackend(page);
    await page.goto(route);
    const content = page.locator('.page-content').first();
    await expect(content).toBeVisible();
    await expect(content).toHaveCSS('padding-left', '12px');
    await expect(content).toHaveCSS('padding-right', '12px');
    const table = page.locator('.page-content .ant-table-wrapper').first();
    if (await table.count()) await expect(table).toHaveCSS('border-left-width', '0px');
    await expect(page.locator('body')).toHaveScreenshot(`${route.replaceAll('/', '-').replace(/^-/, '') || 'root'}.png`, { fullPage: true, animations: 'disabled' });
  });
}
