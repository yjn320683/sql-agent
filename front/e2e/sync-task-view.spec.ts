import { expect, test, type Page } from '@playwright/test';

const response = (data: unknown) => ({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, data }) });

async function expectProductionFieldOrder(page: Page) {
  const navigation = page.getByRole('navigation', { name: '配置分组导航' });
  const navigationBox = await navigation.boundingBox();
  const contentBox = await page.locator('.realtime-advanced-content').boundingBox();
  expect(navigationBox).not.toBeNull();
  expect(contentBox).not.toBeNull();
  expect(navigationBox!.x + navigationBox!.width).toBeLessThan(contentBox!.x);
  await expect(page.locator('.realtime-advanced-content > .realtime-advanced-section').last()).toHaveAttribute('id', 'sync-advanced-section-command');
  await expect(navigation.getByRole('button').last()).toHaveText('命令预览');
  await expect(page.locator('#sync-advanced-section-mysql .ant-descriptions-item-label')).toHaveText(['源端类型', 'Server']);
  await expect(page.locator('#sync-advanced-section-target .ant-descriptions-item-label')).toHaveText([
    '源库', '源表列表', '目标Paimon库', '目标Paimon表所属域', '目标Paimon表前缀',
    '目标Paimon表列表', '目标Paimon表同步元数据列', '目标Paimon表同步元数据列前缀',
    '目标Paimon表类型映射', '目标Paimon表配置', 'MySQL 配置', '整库模式',
  ]);
  await expect(page.locator('#sync-advanced-section-target .ant-descriptions-row')).toHaveCount(12);
  await expect(page.locator('#sync-advanced-section-target .ant-descriptions-item-label').filter({ hasText: '目标Paimon表同步元数据列前缀' })).toHaveCount(1);
  await expect(page.locator('#sync-advanced-section-target .ant-descriptions-row').nth(7).locator('input')).toHaveValue('__meta_');
  await expect(page.locator('#sync-advanced-section-target .realtime-dynamic-param-grid').first().locator('.ant-form-item-label')).toHaveText([
    '目标 Paimon 表 Bucket', '目标 Paimon 表 Sink 并行度', '目标 Paimon 表 Changelog Producer', '主键表 Sequence 字段',
  ]);
  await expect(page.locator('#sync-advanced-section-target .realtime-dynamic-param-grid').first().locator('.ant-form-item').nth(2).locator('.ant-select-selection-item')).toHaveText(/^input/);
  await expect(page.locator('[id="taskConfig_cdcConfig_tableConfOverrides_sequence.field"]')).toHaveValue('__meta_op_ts');
  await expect(page.locator('[id="taskConfig_cdcConfig_tableConfOverrides_changelog-producer"]')).toBeDisabled();
  await expect(page.locator('#sync-advanced-section-runtime .ant-descriptions-item-label')).toHaveText([
    '任务并行度', 'TaskManager 内存', 'JobManager 内存', 'Checkpoint 间隔', 'Flink配置',
  ]);
}

test('同步任务查看复用编辑布局且保持只读', async ({ page }, testInfo) => {
  const requestedSourceDatabases: string[] = [];
  let showDuplicateDdlEvents = false;
  let debugStarted = false;
  let debugRequest: Record<string, unknown> | undefined;
  let productionLocked = false;
  let failInstanceConfig = false;
  let updatePayload: Record<string, unknown> | undefined;
  let releaseVersionResponse: () => void = () => undefined;
  let notifyVersionRequested: () => void = () => undefined;
  const versionResponseGate = new Promise<void>((resolve) => { releaseVersionResponse = resolve; });
  const versionRequested = new Promise<void>((resolve) => { notifyVersionRequested = resolve; });
  await page.route('**/api/**', (route) => {
    const requestUrl = new URL(route.request().url());
    const path = requestUrl.pathname;
    if (!path.startsWith('/api/')) return route.continue();
    if (path === '/api/auth/me') return route.fulfill(response({ obId: '900001' }));
    if (path === '/api/servers') return route.fulfill(response([{ id: 7, name: '订单库', database: 'orders', databasePrefix: 'src', type: 'mysql' }]));
    if (path === '/api/servers/7/mysql/table-schema') return route.fulfill(response({
      table: 'order_info', primaryKeys: ['id', 'tenant'], columns: [
        { name: 'id', type: 'BIGINT', nullable: false },
        { name: 'tenant', type: 'VARCHAR(32)', nullable: false },
        { name: 'remark', type: 'VARCHAR(100)', nullable: true },
      ],
    }));
    if (path === '/api/paimon/cdc-options') return route.fulfill(response({ targetDatabase: 'ods', defaultProjectId: 1, tablePrefixes: [{ label: '交易', value: 'trade' }] }));
    if (path === '/api/tasks/sync/source-tables') {
      requestedSourceDatabases.push(requestUrl.searchParams.get('database') ?? '');
      return route.fulfill(response([
        { tableName: 'order_info', hasPrimaryKey: true },
        { tableName: 'without_pk', hasPrimaryKey: false },
      ]));
    }
    return route.fulfill(response([]));
  });
  await page.route('**/v1/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (!path.startsWith('/v1/api/')) return route.continue();
    if (path === '/v1/api/tasks/page') return route.fulfill(response({
      records: [{ id: 1101, name: '订单 CDC', owner: '管理员', description: '订单同步', status: 'not_running', taskStatus: 0, instanceStatus: 'not_running', sourceServerId: 7, targetDatabase: 'ods', createTime: '', updateTime: '' }],
      total: 1, pageNo: 1, pageSize: 20,
    }));
    if (path === '/v1/api/tasks/1101/detail') return route.fulfill(response({
      id: 1101, name: '订单 CDC', owner: '管理员', description: '订单同步', taskType: 'sync', flinkVersion: '2.2.1',
      status: 'not_running', taskStatus: 0, alarmConfig: { alarmType: 'task-failed', alarmGroup: '值班组' },
      editPolicy: productionLocked ? { editable: true, productionLocked: true, lockedTables: [] } : undefined,
      flinkConf: { parallelism: 1, checkpointIntervalSeconds: 30, taskManagerMemoryGb: 2, jobManagerMemoryGb: 1 },
      taskConfig: { sourceServerId: 7, sourceType: 'mysql-cdc', cdcConfig: {
        databaseName: 'orders', selectedTables: ['order_info'], targetDatabase: 'ods', domainPrefix: 'trade',
        tablePrefix: 'ods_src_orders_trade_', metadataColumns: ['database_name', 'table_name', 'op_ts'], mode: 'combined',
        mysqlConfOverrides: { 'scan.startup.mode': 'initial', 'fixed.option': 'locked' },
        tableConfOverrides: { bucket: '1', 'sink.parallelism': '1' },
      } },
    }));
    if (path === '/v1/api/tasks/1101/update') {
      updatePayload = route.request().postDataJSON() as Record<string, unknown>;
      return route.fulfill(response(1101));
    }
    if (path === '/v1/api/tasks/1101/instances') return route.fulfill(response([{ id: 250, taskId: 1101,
      status: 'canceled', executionMode: 'PRODUCTION', managed: true, startedAt: '2026-09-18 10:00:00' },
    ...(debugStarted ? [{ id: 251, taskId: 1101, status: 'submitting', executionMode: 'DEBUG', managed: true, startedAt: '2026-10-12 10:00:00' }] : [])]));
    if (path === '/v1/api/tasks/debug') {
      debugRequest = route.request().postDataJSON() as Record<string, unknown>;
      debugStarted = true;
      return route.fulfill(response({ id: 251, taskId: 1101, status: 'submitting', executionMode: 'DEBUG', managed: true }));
    }
    if (path === '/v1/api/tasks/1101/instances/250/config' && failInstanceConfig) return route.fulfill({
      status: 500, contentType: 'application/json', body: JSON.stringify({ code: 500, message: '实例有效配置快照不存在' }),
    });
    if (path === '/v1/api/tasks/1101/instances/250/config') return route.fulfill(response({
      id: 1101, instanceId: 250, taskType: 'sync', name: '历史订单 CDC', owner: '历史负责人', flinkVersion: '2.2.1',
      sourceServerId: 7, sourceServerName: '历史 Server', targetDatabase: 'ods',
      taskConfig: { taskManagerMemory: '1536MB', jobManagerMemory: '2GiB', cdcConfig: { databaseName: 'orders', selectedTables: ['order_info'], targetDatabase: 'ods',
        domainPrefix: 'trade', tablePrefix: 'ods_src_orders_trade_', targetTableList: ['历史目标表'],
        mysqlConfOverrides: { 'scan.snapshot.fetch.size': '512' },
        tableConfOverrides: { bucket: '1', 'sink.parallelism': '1', 'changelog-producer': 'lookup', 'sequence.field': '__meta_op_ts' } } },
      startType: 'savepoint', statePath: 'hdfs://history/savepoint-1',
    }));
    if (path === '/v1/api/tasks/1101/versions') return route.fulfill(response([
      { id: 9, versionNo: 9, operator: 'tester', createTime: '2026-10-12 10:00:00' },
    ]));
    if (path === '/v1/api/tasks/1101/versions/9/config') {
      notifyVersionRequested();
      await versionResponseGate;
      return route.fulfill(response({ id: 1101, taskType: 'sync', name: '过期版本快照',
        taskConfig: { cdcConfig: { databaseName: 'orders', selectedTables: ['order_info'] } } }));
    }
    if (path === '/v1/api/tasks/1101/sync/ddl-exceptions') return route.fulfill(response(showDuplicateDdlEvents ? [
      { id: 1, tableName: 'order_info', status: 'ACTIVE', ddlSql: 'ALTER TABLE order_info DROP COLUMN a' },
      { id: 2, tableName: 'order_info', status: 'ACTIVE', ddlSql: 'ALTER TABLE order_info DROP COLUMN b' },
    ] : []));
    if (path === '/v1/api/tasks/params') return route.fulfill(response([
      { paramType: 'table_conf', paramKey: 'changelog-producer', keyDesc: '目标 Paimon 表 Changelog Producer', inputType: 'select', value: [{ label: 'input（平台默认）', value: 'input', default: true }], requiredFlag: 1, editableFlag: 0, enabledFlag: 1, sortOrder: 30 },
      { paramType: 'table_conf', paramKey: 'sequence.field', keyDesc: '主键表 Sequence 字段', inputType: 'input', value: '__meta_op_ts', requiredFlag: 1, editableFlag: 1, enabledFlag: 1, sortOrder: 51 },
      { paramType: 'mysql_conf', paramKey: 'scan.snapshot.fetch.size', keyDesc: '快照单次拉取行数', inputType: 'select', value: [{ label: '1024（官网默认）', value: '1024', default: true }], requiredFlag: true, editableFlag: true, enabledFlag: true },
      { paramType: 'mysql_conf', paramKey: 'scan.incremental.snapshot.chunk.size', keyDesc: '快照分片行数', inputType: 'select', value: [{ label: '8096（官网默认）', value: '8096', default: true }], requiredFlag: true, editableFlag: true, enabledFlag: true },
      { paramType: 'mysql_conf', paramKey: 'scan.startup.mode', keyDesc: '启动模式', inputType: 'input', value: 'initial', requiredFlag: false, editableFlag: true, enabledFlag: true },
      { paramType: 'mysql_conf', paramKey: 'fixed.option', keyDesc: '固定参数', inputType: 'input', value: 'locked', requiredFlag: false, editableFlag: false, enabledFlag: true },
    ]));
    return route.fulfill(response([]));
  });

  await page.goto('/realtime/sync-tasks');
  await page.locator('.realtime-sync-table-section').getByText('调试', { exact: true }).click();
  const debugModal = page.locator('.realtime-debug-config-modal');
  await expect(debugModal).toBeVisible();
  await expect(debugModal).toHaveAttribute('style', /width: 1520px/);
  await expect(page.locator('.realtime-debug-drawer')).toHaveCount(0);
  await expect.poll(async () => {
    const debugNav = await debugModal.getByRole('navigation', { name: '配置分组导航' }).boundingBox();
    const debugForm = await debugModal.locator('.sync-debug-config-form').boundingBox();
    return Boolean(debugNav && debugForm && debugNav.x + debugNav.width <= debugForm.x + 2);
  }).toBe(true);
  await debugModal.locator('.ant-input-number-input[value="30"]').fill('45');
  await debugModal.getByRole('button', { name: '开始调试' }).click();
  await expect(page.locator('.sync-task-detail-drawer')).toBeVisible();
  const operationsDrawer = page.locator('.sync-task-detail-drawer');
  await expect(operationsDrawer.getByText('调试配置', { exact: true })).toBeVisible();
  await expect(operationsDrawer.getByText('实例配置', { exact: true })).toBeVisible();
  await expect.poll(async () => {
    const tabs = await operationsDrawer.locator('.ant-drawer-body > .ant-tabs > .ant-tabs-nav').boundingBox();
    const toolbar = await operationsDrawer.locator('.instance-list-toolbar').boundingBox();
    return tabs && toolbar ? toolbar.y - tabs.y - tabs.height : 0;
  }).toBeGreaterThanOrEqual(10);
  await expect(operationsDrawer.getByRole('tab', { name: '验数记录' })).toBeVisible();
  await operationsDrawer.getByText('全部实例', { exact: true }).click();
  await page.locator('.ant-select-dropdown:visible').getByText('调试', { exact: true }).click();
  await expect(operationsDrawer.locator('tr[data-row-key="251"]')).toBeVisible();
  await expect(operationsDrawer.locator('tr[data-row-key="250"]')).toHaveCount(0);
  await operationsDrawer.getByRole('tab', { name: '验数记录' }).click();
  await expect(operationsDrawer.getByText('暂无验数记录')).toBeVisible();
  expect((debugRequest?.flinkConf as Record<string, unknown>).checkpointIntervalSeconds).toBe(45);
  await page.goto('/realtime/sync-tasks');
  await page.locator('.realtime-sync-table-section').getByText('查看', { exact: true }).click();
  await expect(page).toHaveURL(/\/realtime\/sync-tasks\/1101\/view$/);
  await expect(page.locator('.realtime-sync-editor-page')).toBeVisible();
  await expect(page.getByText('查看 · 订单 CDC')).toBeVisible();
  await expect(page.getByText('基础信息', { exact: true }).first()).toBeVisible();
  await expect(page.locator('.realtime-wizard-steps .task-development-steps-toggle')).toHaveCount(0);
  const firstStepBox = await page.locator('.realtime-wizard-steps .ant-steps-item').first().boundingBox();
  const firstSectionBox = await page.locator('.realtime-wizard-content .realtime-editor-panel-title').boundingBox();
  expect(firstStepBox).not.toBeNull();
  expect(firstSectionBox).not.toBeNull();
  expect(Math.abs(firstStepBox!.y - firstSectionBox!.y)).toBeLessThan(28);
  await expect(page.getByPlaceholder('请输入同步任务名称')).toBeDisabled();
  await expect(page.getByRole('button', { name: '保存修改' })).toHaveCount(0);

  await page.getByRole('button', { name: '下一步' }).click();
  await expect(page.getByText('报警设置类型')).toBeVisible();
  await page.getByRole('tab', { name: '高级配置' }).click();
  await expect(page.getByRole('heading', { name: '源端配置' })).toBeVisible();
  await expect(page.getByRole('heading', { name: '公共配置' })).toBeVisible();
  await expect(page.getByRole('heading', { name: '私有配置' })).toBeVisible();
  await expect(page.getByRole('heading', { name: '命令预览' })).toBeVisible();
  await expect(page.getByRole('navigation', { name: '配置分组导航' }).getByText('入仓配置')).toBeVisible();
  await expectProductionFieldOrder(page);
  await expect(page.locator('[id="taskConfig_cdcConfig_tableConfOverrides_sequence.field"]')).toBeDisabled();
  await expect(page.getByRole('button', { name: '查看源表 DDL' })).toBeEnabled();
  await expect(page.locator('[id="taskConfig_cdcConfig_mysqlConfOverrides_scan.startup.mode"]')).toHaveValue('initial');
  await expect(page.locator('#sync-advanced-section-target .realtime-dynamic-param-grid').nth(1).locator('.ant-select-selection-item')).toHaveText(['1024（官网默认）', '8096（官网默认）']);
  await expect(page.locator('#sync-advanced-section-target .realtime-dynamic-param-grid').first().locator('input').first()).toHaveValue('1');
  await expect(page.locator('#sync-advanced-section-target .realtime-dynamic-param-grid').first().locator('input').nth(1)).toHaveValue('1');
  await expect(page.locator('#sync-advanced-section-runtime .ant-input-number-input').first()).toHaveValue('1');
  await expect(page.locator('#sync-advanced-section-runtime .ant-input-number-input').nth(1)).toHaveValue('2');
  await expect(page.locator('#sync-advanced-section-runtime .ant-input-number-input').nth(2)).toHaveValue('1');
  await expect(page.locator('#sync-advanced-section-runtime .ant-input-number-input').nth(3)).toHaveValue('30');
  await expect(page.locator('#sync-advanced-section-runtime .ant-descriptions-item-label').last()).toContainText('Flink配置');
  await expect(page.getByRole('button', { name: '删除配置 scan.startup.mode' })).toBeDisabled();
  await expect(page.getByRole('button', { name: '删除配置 fixed.option' })).toBeDisabled();
  await expect(page.getByRole('button', { name: '保存修改' })).toHaveCount(0);

  await page.goto('/realtime/sync-tasks/1101/edit');
  await page.getByRole('tab', { name: '高级配置' }).click();
  await expectProductionFieldOrder(page);
  await expect(page.locator('[id="taskConfig_cdcConfig_mysqlConfOverrides_scan.startup.mode"]')).toHaveValue('initial');
  await expect(page.locator('#sync-advanced-section-target .realtime-dynamic-param-grid').nth(1).locator('.ant-select-selection-item')).toHaveText(['1024（官网默认）', '8096（官网默认）']);
  await expect(page.locator('#sync-advanced-section-target .realtime-dynamic-param-grid').first().locator('input').first()).toHaveValue('1');
  await expect(page.locator('#sync-advanced-section-runtime .ant-input-number-input').first()).toHaveValue('1');
  await expect(page.locator('[id="taskConfig_cdcConfig_tableConfOverrides_sequence.field"]')).toBeEnabled();
  await expect(page.getByRole('button', { name: '删除配置 scan.startup.mode' })).toBeEnabled();
  await expect(page.getByRole('button', { name: '删除配置 fixed.option' })).toBeDisabled();
  await expect(page.getByRole('heading', { name: '源端配置' })).toBeVisible();
  await page.locator('#sync-advanced-section-target .realtime-sync-source-tables .ant-select-selector').click();
  await expect(page.locator('.ant-select-item-option').filter({ hasText: 'without_pk（无物理主键）' }))
    .toHaveClass(/ant-select-item-option-disabled/);
  await page.keyboard.press('Escape');
  const partitionSelect = page.locator('.realtime-mapping-table tbody tr').filter({ hasText: 'order_info' }).locator('.ant-select').last();
  await partitionSelect.click();
  await page.locator('.ant-select-dropdown:visible .ant-select-item-option').filter({ hasText: 'id' }).click();
  await expect(partitionSelect.locator('.ant-select-selection-item')).toHaveText(['id']);
  await partitionSelect.locator('input').fill('tenant');
  await partitionSelect.locator('input').press('Enter');
  await expect(page.getByText('分区键不能覆盖全部最终主键').first()).toBeVisible();
  await expect(partitionSelect.locator('.ant-select-selection-item')).toHaveText(['id']);

  await page.goto('/realtime/sync-tasks/new');
  await page.getByRole('tab', { name: '高级配置' }).click();
  await expectProductionFieldOrder(page);
  await expect(page.getByRole('heading', { name: '源端配置' })).toBeVisible();
  await expect(page.locator('.sync-more-config-rows').first()).toBeVisible();
  await expect(page.locator('#sync-advanced-section-target .realtime-dynamic-param-grid').nth(1).locator('.ant-select-selection-item')).toHaveText(['1024（官网默认）', '8096（官网默认）']);
  await expect(page.locator('#sync-advanced-section-target .realtime-dynamic-param-grid').first().locator('input').first()).toHaveValue('3');
  await expect(page.locator('#sync-advanced-section-target .ant-descriptions-row').nth(8).locator('.ant-select-selection-item')).toHaveText(['tinyint1-not-bool', 'to-nullable']);
  await expect(page.locator('#sync-advanced-section-runtime .ant-input-number-input').first()).toHaveValue('3');
  await expect(page.locator('[id="taskConfig_cdcConfig_tableConfOverrides_sequence.field"]')).toBeEnabled();
  await expect(page.getByRole('button', { name: '删除配置 fixed.option' })).toBeDisabled();
  expect(requestedSourceDatabases).toContain('orders');
  expect(requestedSourceDatabases).not.toContain('');

  const requestsBeforeSnapshot = requestedSourceDatabases.length;
  await page.goto('/realtime/sync-tasks');
  await page.locator('.clickable-task-row').first().getByText('订单 CDC').click();
  await expect(page.locator('.sync-task-detail-drawer')).toBeVisible();
  await page.locator('.sync-task-detail-drawer').getByText('日志', { exact: true }).first().click();
  const logTabs = page.locator('.sync-task-detail-drawer .task-log-tabs-fill');
  await expect(logTabs).toHaveClass(/ant-tabs-right/);
  await expect(logTabs.getByRole('tab')).toHaveText(['启动日志', '停止日志', '运行日志']);
  const logNav = await logTabs.locator(':scope > .ant-tabs-nav').boundingBox();
  const logContent = await logTabs.locator(':scope > .ant-tabs-content-holder').boundingBox();
  expect(logNav && logContent && logNav.x >= logContent.x + logContent.width - 2).toBe(true);
  await page.locator('.sync-task-detail-drawer').getByRole('button', { name: '返回实例列表' }).click();
  await page.locator('.sync-task-detail-drawer').getByText('实例配置', { exact: true }).click();
  const snapshotModal = page.locator('.sync-task-snapshot-modal');
  await expect(snapshotModal).toBeVisible();
  await expect(snapshotModal).toHaveAttribute('style', /width: 1520px/);
  await expect(snapshotModal.getByRole('navigation', { name: '配置分组导航' })).toBeVisible();
  await expect(snapshotModal.getByRole('navigation', { name: '配置分组导航' }).getByText('消费点配置')).toBeVisible();
  await expect(snapshotModal.locator('input[value="历史订单 CDC"]')).toBeDisabled();
  await expect(snapshotModal.locator('#sync-advanced-section-target textarea').first()).toHaveValue('历史目标表');
  await expect(snapshotModal.locator('#sync-advanced-section-target textarea').first()).toBeDisabled();
  await expect(snapshotModal.locator('#sync-advanced-section-target .realtime-dynamic-param').filter({ hasText: 'Changelog Producer' }).getByText('lookup')).toBeVisible();
  await expect(snapshotModal.locator('#sync-advanced-section-target [id="taskConfig_cdcConfig_tableConfOverrides_sequence.field"]')).toHaveValue('__meta_op_ts');
  await expect(snapshotModal.locator('#sync-advanced-section-target .realtime-dynamic-param').filter({ hasText: '快照单次拉取行数' }).getByText('512')).toBeVisible();
  await expect(snapshotModal.locator('#sync-advanced-section-target [id="taskConfig_cdcConfig_mysqlConfOverrides_scan.incremental.snapshot.chunk.size"]')).toHaveCount(0);
  await expect(snapshotModal.locator('input[value="hdfs://history/savepoint-1"]')).toBeDisabled();
  await expect(snapshotModal.locator('#sync-advanced-section-runtime .ant-input-number-input').nth(1)).toHaveValue('1.5');
  await expect(snapshotModal.locator('#sync-advanced-section-runtime .ant-input-number-input').nth(2)).toHaveValue('2');
  await expect(snapshotModal.getByRole('button', { name: '生成预览' })).toBeDisabled();
  await expect(snapshotModal.locator('.realtime-mapping-table').getByText('order_info')).toBeVisible();
  await expect(snapshotModal.locator('.realtime-mapping-table').getByRole('link', { name: 'order_info' })).toHaveCount(0);
  await expect(snapshotModal.locator('.realtime-mapping-table').getByText('历史快照')).toBeVisible();
  await expect(snapshotModal.locator('.realtime-mapping-table').getByText('待读取')).toHaveCount(0);
  expect(requestedSourceDatabases).toHaveLength(requestsBeforeSnapshot);
  await snapshotModal.screenshot({ path: testInfo.outputPath('instance-config.png'), animations: 'disabled' });
  const snapshotBody = snapshotModal.locator('.ant-modal-body');
  const snapshotForm = snapshotModal.locator('.realtime-editor-form.mode-advanced');
  const snapshotNotice = snapshotModal.locator('.ant-alert-info');
  const noticeBeforeScroll = await snapshotNotice.boundingBox();
  const snapshotContentBox = await snapshotModal.locator('.realtime-advanced-content').boundingBox();
  expect(snapshotContentBox).not.toBeNull();
  await page.mouse.move(snapshotContentBox!.x + 100, snapshotContentBox!.y + 200);
  await page.mouse.wheel(0, 600);
  await expect.poll(() => snapshotForm.evaluate((element) => element.scrollTop)).toBeGreaterThan(300);
  expect(await snapshotBody.evaluate((element) => element.scrollTop)).toBe(0);
  const noticeAfterScroll = await snapshotNotice.boundingBox();
  expect(noticeBeforeScroll && noticeAfterScroll && Math.abs(noticeBeforeScroll.y - noticeAfterScroll.y) < 2).toBe(true);
  const formBox = await snapshotForm.boundingBox();
  const navBox = await snapshotModal.getByRole('navigation', { name: '配置分组导航' }).boundingBox();
  expect(navBox && formBox && navBox.y >= formBox.y && navBox.y - formBox.y < 40).toBe(true);
  await snapshotModal.getByRole('navigation', { name: '配置分组导航' }).getByRole('button', { name: '命令预览' }).click();
  await expect.poll(() => snapshotForm.evaluate((element) => element.scrollTop)).toBeGreaterThan(1400);

  await snapshotModal.locator('.ant-modal-close').click();
  await page.locator('.sync-task-detail-drawer').getByRole('tab', { name: /版本记录/ }).click();
  await page.getByText('查看版本配置').click();
  await versionRequested;
  await snapshotModal.locator('.ant-modal-close').click();
  await page.locator('.sync-task-detail-drawer').getByRole('tab', { name: /实例列表/ }).click();
  await page.locator('.sync-task-detail-drawer').getByText('实例配置', { exact: true }).click();
  await expect(snapshotModal.locator('input[value="历史订单 CDC"]')).toBeVisible();
  const staleVersionResponse = page.waitForResponse((value) => new URL(value.url()).pathname === '/v1/api/tasks/1101/versions/9/config');
  releaseVersionResponse();
  await staleVersionResponse;
  await page.evaluate(() => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
  await expect(snapshotModal.locator('input[value="历史订单 CDC"]')).toBeVisible();
  await expect(snapshotModal.locator('input[value="过期版本快照"]')).toHaveCount(0);

  await snapshotModal.locator('.ant-modal-close').click();
  failInstanceConfig = true;
  await page.locator('.sync-task-detail-drawer').getByText('实例配置', { exact: true }).click();
  await expect(snapshotModal.getByText('实例有效配置快照不存在')).toBeVisible();
  await expect(snapshotModal.locator('.realtime-sync-editor-page')).toHaveCount(0);
  await snapshotModal.locator('.ant-modal-close').click();
  failInstanceConfig = false;
  await page.locator('.sync-task-detail-drawer').getByRole('tab', { name: /版本记录/ }).click();
  await page.getByText('查看版本配置').click();
  await expect(snapshotModal.getByText('以下为历史版本配置快照，仅供查看；不会回填当前任务或默认参数。')).toBeVisible();
  await expect(snapshotModal.getByRole('navigation', { name: '配置分组导航' }).getByText('消费点配置')).toHaveCount(0);

  showDuplicateDdlEvents = true;
  productionLocked = true;
  await page.goto('/realtime/sync-tasks/1101/edit');
  await page.getByRole('tab', { name: '高级配置' }).click();
  await expect(page.getByRole('button', { name: '剔除 order_info' })).toHaveCount(1);
  await expect(page.locator('#sync-advanced-section-mysql #sourceServerId')).toHaveValue('7');

  showDuplicateDdlEvents = false;
  await page.goto('/realtime/sync-tasks/1101/edit');
  await page.getByRole('tab', { name: '高级配置' }).click();
  await page.getByRole('button', { name: '保存修改' }).click();
  await expect.poll(() => updatePayload?.taskConfig).not.toBeUndefined();
  expect((updatePayload?.taskConfig as Record<string, unknown>).sourceServerId).toBe(7);
});
