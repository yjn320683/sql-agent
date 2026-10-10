import { expect, test } from '@playwright/test';

const response = (data: unknown) => ({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, data }) });

test('实时任务上下线与运行状态独立', async ({ page }) => {
  let taskStatus = 1;
  let updateRequests = 0;
  await page.route('**/api/**', (route) => {
    const path = new URL(route.request().url()).pathname;
    if (!path.startsWith('/api/')) return route.continue();
    if (path === '/api/auth/me') return route.fulfill(response({ obId: '900001' }));
    if (path === '/api/servers') return route.fulfill(response([]));
    return route.fulfill(response([]));
  });
  await page.route('**/v1/api/**', (route) => {
    const path = new URL(route.request().url()).pathname;
    if (!path.startsWith('/v1/api/')) return route.continue();
    if (path === '/v1/api/tasks/page') return route.fulfill(response({
      records: [{ id: 1101, name: 'CDC 测试任务', taskType: 'sync', taskStatus,
        status: 'not_running', instanceStatus: 'not_running', owner: 'tester', mappingCount: 0 }],
      total: 1, pageNo: 1, pageSize: 20,
    }));
    if (path === '/v1/api/tasks/1101/task-status') {
      updateRequests += 1;
      taskStatus = JSON.parse(route.request().postData() ?? '{}').taskStatus;
      return route.fulfill(response({ id: 1101, taskStatus, instanceStatus: 'not_running' }));
    }
    if (path === '/v1/api/tasks/params') return route.fulfill(response([]));
    return route.fulfill(response([]));
  });

  await page.goto('/realtime/sync-tasks');
  const filters = page.locator('.realtime-sync-query-filter');
  const fields = filters.locator('.realtime-sync-filter-item');
  await expect(fields).toHaveCount(3);
  const boxes = await Promise.all(Array.from({ length: 3 }, (_, index) => fields.nth(index).boundingBox()));
  const actions = await filters.locator('.realtime-sync-filter-actions').boundingBox();
  expect(boxes.every((box) => box && Math.abs(box.y - boxes[0]!.y) < 2)).toBe(true);
  expect(actions && Math.abs(actions.y - boxes[0]!.y) < 2).toBe(true);
  expect(boxes[2] && actions && boxes[2].x + boxes[2].width < actions.x).toBe(true);
  const row = page.locator('.ant-table-tbody tr').filter({ hasText: 'CDC 测试任务' });
  await expect(row.getByRole('switch')).toBeChecked();
  await expect(row.getByText('未运行')).toBeVisible();
  await row.getByRole('switch').click();
  await expect(row.getByRole('switch')).not.toBeChecked();
  await expect(row.getByText('未运行')).toBeVisible();
  await expect(row.getByText('启动')).toHaveClass(/ant-typography-disabled/);
  expect(updateRequests).toBe(1);
});
