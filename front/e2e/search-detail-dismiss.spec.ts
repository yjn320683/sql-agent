import { expect, test } from '@playwright/test';

const response = (data: unknown) => ({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, data }) });

test('全局搜索打开实时任务后，点击遮罩一次即可关闭详情', async ({ page }) => {
  let detailRequests = 0;
  await page.route('**/api/**', (route) => {
    const path = new URL(route.request().url()).pathname;
    if (!path.startsWith('/api/')) return route.continue();
    if (path === '/api/auth/me') return route.fulfill(response({ obId: '900001' }));
    if (path === '/api/search') return route.fulfill(response({
      keyword: 'CDC', generatedAt: '', partialFailures: [], groups: [{
        type: 'REALTIME_TASK', label: '实时任务', total: 1, page: 1, pageSize: 5,
        items: [{ type: 'REALTIME_TASK', id: 1101, title: 'CDC 测试任务', subtitle: '实时同步', status: 'not_running', mode: 'realtime', route: '/realtime/sync-tasks?taskId=1101' }],
      }],
    }));
    if (path === '/api/servers') return route.fulfill(response([]));
    return route.fulfill(response([]));
  });
  await page.route('**/v1/api/**', (route) => {
    const path = new URL(route.request().url()).pathname;
    if (!path.startsWith('/v1/api/')) return route.continue();
    if (path === '/v1/api/tasks/page') return route.fulfill(response({ records: [], total: 0, pageNo: 1, pageSize: 20 }));
    if (path === '/v1/api/tasks/1101/detail') {
      detailRequests += 1;
      return route.fulfill(response({
        id: 1101, name: 'CDC 测试任务', taskType: 'sync', flinkVersion: '1.18', status: 'not_running',
        alarmConfig: {}, flinkConf: {}, taskConfig: { sourceServerId: 1, sourceType: 'mysql-cdc', cdcConfig: { targetDatabase: 'test' } },
      }));
    }
    if (path === '/v1/api/tasks/1101/instances' || path === '/v1/api/tasks/params') return route.fulfill(response([]));
    return route.fulfill(response([]));
  });

  await page.goto('/realtime/sync-tasks');
  await page.getByRole('button', { name: '打开全局搜索' }).click();
  await page.getByPlaceholder('搜索任务、表、实例、会话或告警').fill('CDC');
  await page.getByRole('button', { name: /CDC 测试任务/ }).click();
  const drawer = page.locator('.realtime-task-detail-drawer');
  await expect(drawer).toBeVisible();
  await expect(page.locator('.global-search-modal')).toBeHidden();
  await page.locator('.ant-drawer-mask').click({ position: { x: 20, y: 20 } });
  await expect(drawer).toBeHidden();
  await expect(page).not.toHaveURL(/taskId=/);
  await page.waitForTimeout(350);
  await expect(drawer).toBeHidden();
  expect(detailRequests).toBe(1);
});
