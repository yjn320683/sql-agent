import { expect, test } from '@playwright/test';

const response = (data: unknown) => ({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, data }) });

for (const taskType of ['compute', 'export'] as const) {
  test(`${taskType} task uses a read-only view and submits edited debug config without saving`, async ({ page }) => {
    const task = {
      id: 31, taskType, name: '示例实时任务', owner: 'tester', description: '原始描述', flinkVersion: '2.2.1',
      taskStatus: 0, status: 'not_running', instanceStatus: 'not_running',
      alarmConfig: {}, flinkConf: { parallelism: 1, checkpointIntervalSeconds: 60, taskManagerMemoryGb: 2, jobManagerMemoryGb: 1, flinkConfOverrides: {} },
      taskConfig: taskType === 'compute'
        ? { computeConfig: { defaultDatabase: 'ods', sql: 'SELECT id FROM paimon.ods.source' } }
        : { exportConfig: { sourceDatabase: 'ods', targetServerId: 7, mappings: [], sink: { batchSize: 500, flushIntervalMs: 2000, maxRetries: 3 } } },
    };
    let debugPayload: Record<string, unknown> | undefined;
    let updateCount = 0;
    await page.route('**/api/**', (route) => {
      const path = new URL(route.request().url()).pathname;
      if (!path.startsWith('/api/')) return route.continue();
      if (path === '/api/auth/me') return route.fulfill(response({ obId: '900001' }));
      if (path === '/api/realtime/tables/available') return route.fulfill(response([{ id: 3, databaseName: 'ods', tableName: 'source', columns: [] }]));
      if (path === '/api/servers') return route.fulfill(response([{ id: 7, name: '目标 MySQL', database: 'target', type: 'mysql' }]));
      return route.fulfill(response([]));
    });
    await page.route('**/v1/api/**', (route) => {
      const path = new URL(route.request().url()).pathname;
      if (!path.startsWith('/v1/api/')) return route.continue();
      if (path === '/v1/api/tasks/page') return route.fulfill(response({ records: [task], total: 1, pageNo: 1, pageSize: 20 }));
      if (path === '/v1/api/tasks/31/detail') return route.fulfill(response(task));
      if (path === '/v1/api/tasks/params') return route.fulfill(response([]));
      if (path === '/v1/api/tasks/debug') {
        debugPayload = route.request().postDataJSON() as Record<string, unknown>;
        return route.fulfill(response({ id: 32, taskId: 31, executionMode: 'DEBUG', status: 'submitting' }));
      }
      if (path === '/v1/api/tasks/31/update') { updateCount += 1; return route.fulfill(response(31)); }
      return route.fulfill(response([]));
    });

    await page.goto(`/realtime/${taskType}`);
    await page.locator('.realtime-sync-table-section').getByText('查看', { exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`/realtime/${taskType}/31/view`));
    await expect(page.locator('input[id="name"]')).toBeDisabled();
    await expect(page.getByRole('button', { name: '保存修改' })).toHaveCount(0);
    if (taskType === 'compute') {
      await page.getByRole('tab', { name: '高级配置' }).click();
      await expect(page.locator('.cm-content')).toHaveAttribute('contenteditable', 'false');
    }
    await page.goto(`/realtime/${taskType}`);

    await page.locator('.realtime-sync-table-section').getByText('调试', { exact: true }).click();
    const modal = page.getByRole('dialog', { name: /隔离调试配置/ });
    await expect(modal).toBeVisible();
    await expect(modal.getByText('本次配置仅写入 DEBUG 实例快照')).toBeVisible();
    expect(debugPayload).toBeUndefined();
    await expect.poll(async () => {
      const navigationBox = await modal.getByRole('navigation', { name: '配置分组导航' }).boundingBox();
      const contentBox = await modal.locator('.realtime-advanced-content').boundingBox();
      return Boolean(navigationBox && contentBox && navigationBox.x + navigationBox.width < contentBox.x);
    }).toBe(true);
    await modal.locator('input[id="name"]').fill('仅本次调试的名称');
    await modal.getByRole('button', { name: '开始调试' }).click();
    await expect.poll(() => debugPayload?.name).toBe('仅本次调试的名称');
    expect(debugPayload).toMatchObject({ taskId: 31, taskType, startType: 'direct' });
    expect(debugPayload?.taskConfig).toEqual(task.taskConfig);
    expect(updateCount).toBe(0);
  });
}
