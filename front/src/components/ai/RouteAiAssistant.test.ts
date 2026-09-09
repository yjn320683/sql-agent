import { describe, expect, it } from 'vitest';
import { resolveRouteAiContext } from './RouteAiAssistant';

describe('页面 AI 上下文路由', () => {
  it.each([
    ['/tasks', '', 'OFFLINE_TASK'],
    ['/tasks/12/edit', '', 'OFFLINE_TASK'],
    ['/tasks/12/edit', '?versionNo=3', 'OFFLINE_VERSION'],
    ['/tasks/12/executions/98', '', 'OFFLINE_EXECUTION'],
    ['/tasks/12/executions', '', 'OFFLINE_EXECUTION'],
    ['/tasks/unions', '', 'OFFLINE_VERSION'],
    ['/executions', '', 'OFFLINE_EXECUTION'],
    ['/catalog', '?table=ods.orders', 'CATALOG_TABLE'],
    ['/data-compares/12', '', 'DATA_COMPARE'],
    ['/data-compares/new', '', 'DATA_COMPARE'],
    ['/realtime/sync-tasks/31/edit', '', 'REALTIME_SYNC_TASK'],
    ['/realtime/compute/32/edit', '', 'REALTIME_COMPUTE_TASK'],
    ['/realtime/export/33/edit', '', 'REALTIME_EXPORT_TASK'],
    ['/realtime/servers', '', 'REALTIME_SERVER'],
    ['/realtime/alerts', '', 'REALTIME_ALERT'],
    ['/realtime/paimon-tables', '', 'REALTIME_TABLE'],
    ['/platform', '', 'PLATFORM_STATUS'],
  ])('%s 映射到 %s', (pathname, search, expected) => {
    expect(resolveRouteAiContext(pathname, search)?.type).toBe(expected);
  });

  it('登录后的对话和会话管理页不重复展示页面 AI', () => {
    expect(resolveRouteAiContext('/chat')).toBeUndefined();
    expect(resolveRouteAiContext('/sessions')).toBeUndefined();
  });

  it('离线版本上下文保留任务和版本基线', () => {
    expect(resolveRouteAiContext('/tasks/12/edit', '?versionNo=3')).toMatchObject({
      entityId: '3',
      parentId: '12',
      versionNo: 3,
    });
  });
});
