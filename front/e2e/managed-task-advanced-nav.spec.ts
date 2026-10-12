import { expect, test } from '@playwright/test';

const response = (data: unknown) => ({
  status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, data }),
});

for (const taskType of ['compute', 'export'] as const) {
  test(`${taskType} 高级配置显示左侧分组导航`, async ({ page }) => {
    await page.route('**/api/**', (route) => {
      const path = new URL(route.request().url()).pathname;
      if (!path.startsWith('/api/')) return route.continue();
      if (path === '/api/auth/me') return route.fulfill(response({ obId: '900001' }));
      return route.fulfill(response([]));
    });
    await page.route('**/v1/api/**', (route) => route.fulfill(response([])));

    await page.goto(`/realtime/${taskType}/new`);
    await page.getByRole('tab', { name: '高级配置' }).click();
    const nav = page.getByRole('navigation', { name: '配置分组导航' });
    const content = page.locator('.realtime-advanced-content');
    await expect(nav.getByRole('button')).toHaveText([
      '基础信息', '告警配置', taskType === 'compute' ? 'SQL 编辑' : '出仓设置', '运行资源',
    ]);
    await expect.poll(async () => {
      const navBox = await nav.boundingBox();
      const contentBox = await content.boundingBox();
      return Boolean(navBox && contentBox && navBox.x + navBox.width < contentBox.x);
    }).toBe(true);
    await nav.getByRole('button', { name: '运行资源' }).click();
    await expect.poll(() => page.locator('.realtime-editor-form.mode-advanced')
      .evaluate((element) => element.scrollTop)).toBeGreaterThan(0);
  });
}
