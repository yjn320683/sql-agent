import { expect, test } from '@playwright/test';

const response = (data: unknown) => ({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, data }) });

test('同步任务查看复用编辑布局且保持只读', async ({ page }) => {
  await page.route('**/api/**', (route) => {
    const path = new URL(route.request().url()).pathname;
    if (!path.startsWith('/api/')) return route.continue();
    if (path === '/api/auth/me') return route.fulfill(response({ obId: '900001' }));
    if (path === '/api/servers') return route.fulfill(response([{ id: 7, name: '订单库', databaseName: 'orders', databasePrefix: 'src', type: 'mysql' }]));
    if (path === '/api/paimon/cdc-options') return route.fulfill(response({ targetDatabase: 'ods', defaultProjectId: 1, tablePrefixes: [{ label: '交易', value: 'trade' }] }));
    if (path === '/api/tasks/sync/source-tables') return route.fulfill(response([{ tableName: 'order_info' }]));
    return route.fulfill(response([]));
  });
  await page.route('**/v1/api/**', (route) => {
    const path = new URL(route.request().url()).pathname;
    if (!path.startsWith('/v1/api/')) return route.continue();
    if (path === '/v1/api/tasks/page') return route.fulfill(response({
      records: [{ id: 1101, name: '订单 CDC', owner: '管理员', description: '订单同步', status: 'not_running', taskStatus: 0, instanceStatus: 'not_running', sourceServerId: 7, targetDatabase: 'ods', createTime: '', updateTime: '' }],
      total: 1, pageNo: 1, pageSize: 20,
    }));
    if (path === '/v1/api/tasks/1101/detail') return route.fulfill(response({
      id: 1101, name: '订单 CDC', owner: '管理员', description: '订单同步', taskType: 'sync', flinkVersion: '2.2.1',
      status: 'not_running', taskStatus: 0, alarmConfig: { alarmType: 'task-failed', alarmGroup: '值班组' },
      flinkConf: { parallelism: 3, checkpointIntervalSeconds: 60, taskManagerMemoryGb: 3, jobManagerMemoryGb: 1 },
      taskConfig: { sourceServerId: 7, sourceType: 'mysql-cdc', cdcConfig: {
        databaseName: 'orders', selectedTables: ['order_info'], targetDatabase: 'ods', domainPrefix: 'trade',
        tablePrefix: 'ods_src_orders_trade_', metadataColumns: ['database_name', 'table_name', 'op_ts'], mode: 'combined',
      } },
    }));
    if (path === '/v1/api/tasks/params') return route.fulfill(response([]));
    return route.fulfill(response([]));
  });

  await page.goto('/realtime/sync-tasks');
  await page.locator('.realtime-sync-table-section').getByText('查看', { exact: true }).click();
  await expect(page).toHaveURL(/\/realtime\/sync-tasks\/1101\/view$/);
  await expect(page.locator('.realtime-sync-editor-page')).toBeVisible();
  await expect(page.getByText('查看 · 订单 CDC')).toBeVisible();
  await expect(page.getByText('基础信息', { exact: true }).first()).toBeVisible();
  await expect(page.getByPlaceholder('请输入同步任务名称')).toBeDisabled();
  await expect(page.getByRole('button', { name: '保存修改' })).toHaveCount(0);

  await page.getByRole('button', { name: '下一步' }).click();
  await expect(page.getByText('报警设置类型')).toBeVisible();
  await page.getByRole('tab', { name: '高级配置' }).click();
  await expect(page.getByRole('heading', { name: '源端&目标Paimon配置' })).toBeVisible();
  await expect(page.getByRole('button', { name: '保存修改' })).toHaveCount(0);
});
