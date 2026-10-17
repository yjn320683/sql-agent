import { expect, test } from '@playwright/test';

test('逐表 Bucket、默认分区和时间过滤保存到统一任务配置', async ({ page }) => {
  const success = (data: unknown) => ({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, data }) });
  let saved: any;
  await page.route('**/api/**', route => {
    const path = new URL(route.request().url()).pathname;
    if (!path.startsWith('/api/')) return route.continue();
    if (path === '/api/auth/me') return route.fulfill(success({ obId: '900001' }));
    if (path === '/api/servers') return route.fulfill(success([{ id: 7, name: '测试库', database: 'orders', type: 'mysql' }]));
    if (path === '/api/tasks/sync/source-server-availability') return route.fulfill(success([
      { serverId: 7, syncMode: 'full_incremental', currentTaskCount: 1, maxTaskCount: 1, available: true },
      { serverId: 7, syncMode: 'incremental', currentTaskCount: 0, maxTaskCount: 1, available: true },
    ]));
    if (path === '/api/paimon/cdc-options') return route.fulfill(success({ targetDatabase: 'ods', mysqlServerTimeZone: 'Asia/Shanghai', tablePrefixes: [{ label: '交易', value: 'trade' }] }));
    if (path === '/api/tasks/sync/source-tables') return route.fulfill(success([{ tableName: 'orders', hasPrimaryKey: true }]));
    if (path === '/api/servers/7/mysql/table-schema') return route.fulfill(success({
      table: 'orders', primaryKeys: ['id'], columns: [
        { name: 'id', type: 'bigint', nullable: false },
        { name: 'region', type: 'varchar(32)', nullable: true },
        { name: 'created_at', type: 'datetime', nullable: true },
      ],
    }));
    return route.fulfill(success([]));
  });
  await page.route('**/v1/api/**', route => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/v1/api/tasks/31/detail') return route.fulfill(success({
      id: 31, name: '逐表配置测试', owner: 'tester', taskType: 'sync', flinkVersion: '2.2.1',
      status: 'not_running', taskStatus: 0, alarmConfig: { alarmType: 'task-failed', alarmGroup: '测试' },
      flinkConf: { parallelism: 1, checkpointIntervalSeconds: 30, taskManagerMemoryGb: 2, jobManagerMemoryGb: 1 },
      taskConfig: { sourceServerId: 7, syncMode: 'full_incremental', sourceType: 'mysql-cdc', cdcConfig: {
        databaseName: 'orders', selectedTables: ['orders'], targetDatabase: 'ods', domainPrefix: 'trade', mode: 'combined',
        tableConfigs: { orders: { primaryKeys: ['id', 'region'], partitionKeys: ['region'] } },
      } },
    }));
    if (path === '/v1/api/tasks/31/update') { saved = route.request().postDataJSON(); return route.fulfill(success(31)); }
    if (path === '/v1/api/tasks/page') return route.fulfill(success({ records: [], total: 0 }));
    return route.fulfill(success([]));
  });
  await page.goto('/realtime/sync-tasks/31/edit');
  await page.getByRole('tab', { name: '高级配置' }).click();
  await page.getByRole('button', { name: '配置 orders 同步选项', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: '同步表配置：orders' });
  await dialog.getByRole('spinbutton', { name: '逐表 Bucket' }).fill('8');
  await dialog.getByRole('switch').click();
  await expect(dialog.getByRole('textbox', { name: 'region 默认分区值' })).toHaveValue('default');
  await dialog.getByRole('combobox', { name: '过滤时间字段' }).click();
  await page.getByText('created_at · datetime', { exact: true }).click();
  await dialog.getByRole('textbox', { name: '过滤开始时间' }).fill('2026-02-30 00:00:00');
  await dialog.getByRole('button', { name: /保\s*存/ }).click();
  await expect(dialog.getByText('请选择有效的过滤开始时间')).toBeVisible();
  await dialog.getByRole('textbox', { name: '过滤开始时间' }).fill('2026-10-01 00:00:00');
  await dialog.getByRole('button', { name: /保\s*存/ }).click();
  await expect(dialog).not.toBeVisible();
  await page.getByRole('button', { name: '保存修改' }).click();
  await expect.poll(() => saved?.taskConfig?.cdcConfig?.tableConfigs?.orders).toEqual({
    primaryKeys: ['id', 'region'], partitionKeys: ['region'], bucket: 8,
    partitionDefaults: { version: 1, values: { region: 'default' } },
    timeFilter: { version: 1, field: 'created_at', sourceType: 'datetime', start: '2026-10-01 00:00:00', timeZone: 'Asia/Shanghai' },
  });
});
