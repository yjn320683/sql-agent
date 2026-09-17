import { useEffect, useMemo, useState } from 'react';
import { Button, Tooltip } from 'antd';
import { RobotOutlined } from '@ant-design/icons';
import { useLocation } from 'react-router-dom';
import type { AiContext, AiContextType, AiProposal, ProposalActionResult } from '../../types';
import AiAssistantDrawer from './AiAssistantDrawer';
import { inspectProposal } from './proposalRegistry';

interface RouteContext {
  type: AiContextType;
  title: string;
  entityId?: string;
  parentId?: string;
  versionNo?: number;
}

export function resolveRouteAiContext(pathname: string, search = ''): RouteContext | undefined {
  if (pathname.startsWith('/chat') || pathname.startsWith('/sessions')) return undefined;
  const params = new URLSearchParams(search);
  const match = (pattern: RegExp) => pathname.match(pattern);
  let found: RegExpMatchArray | null;
  if ((found = match(/^\/tasks\/(\d+)\/versions\/(\d+)/))) return {
    type: 'OFFLINE_VERSION', title: `离线版本 v${found[2]}`, entityId: found[2], parentId: found[1], versionNo: Number(found[2]),
  };
  if ((found = match(/^\/tasks\/(\d+)\/executions\/(\d+)/))) return {
    type: 'OFFLINE_EXECUTION', title: `离线实例 #${found[2]}`, entityId: found[2], parentId: found[1],
  };
  if ((found = match(/^\/tasks\/(\d+)\/executions\/?$/))) return {
    type: 'OFFLINE_EXECUTION', title: `任务 #${found[1]} · 运行记录`, parentId: found[1],
  };
  if ((found = match(/^\/tasks\/(\d+)/))) return {
    type: params.get('versionNo') ? 'OFFLINE_VERSION' : 'OFFLINE_TASK',
    title: params.get('versionNo') ? `离线任务 #${found[1]} · v${params.get('versionNo')}` : `离线任务 #${found[1]}`,
    entityId: params.get('versionNo') ?? found[1], parentId: params.get('versionNo') ? found[1] : undefined,
    versionNo: Number(params.get('versionNo')) || undefined,
  };
  if ((found = match(/^\/executions\/(\d+)/))) return {
    type: 'OFFLINE_EXECUTION', title: `离线实例 #${found[1]}`, entityId: found[1], parentId: params.get('taskId') ?? undefined,
  };
  if ((found = match(/^\/data-compares\/(\d+)/))) return { type: 'DATA_COMPARE', title: `验数任务 #${found[1]}`, entityId: found[1] };
  if (pathname.startsWith('/data-compares')) return { type: 'DATA_COMPARE', title: '数据验数' };
  if (pathname.startsWith('/catalog') || pathname.startsWith('/data-map/catalog')) return { type: 'CATALOG_TABLE', title: '数据目录', entityId: params.get('table') ?? undefined };
  if ((found = match(/^\/realtime\/sync-tasks\/(\d+)/))) return { type: 'REALTIME_SYNC_TASK', title: `实时同步任务 #${found[1]}`, entityId: found[1] };
  if (pathname.startsWith('/realtime/sync-tasks')) return { type: 'REALTIME_SYNC_TASK', title: '实时同步任务' };
  if ((found = match(/^\/realtime\/compute\/(\d+)/))) return { type: 'REALTIME_COMPUTE_TASK', title: `实时计算任务 #${found[1]}`, entityId: found[1] };
  if (pathname.startsWith('/realtime/compute')) return { type: 'REALTIME_COMPUTE_TASK', title: '实时计算任务' };
  if ((found = match(/^\/realtime\/export\/(\d+)/))) return { type: 'REALTIME_EXPORT_TASK', title: `实时出仓任务 #${found[1]}`, entityId: found[1] };
  if (pathname.startsWith('/realtime/export')) return { type: 'REALTIME_EXPORT_TASK', title: '实时出仓任务' };
  if ((found = match(/^\/realtime\/servers\/(\d+)/))) return { type: 'REALTIME_SERVER', title: `Server #${found[1]}`, entityId: found[1] };
  if (pathname.startsWith('/realtime/servers')) return { type: 'REALTIME_SERVER', title: 'Server 管理' };
  if (pathname.startsWith('/realtime/alerts')) return { type: 'REALTIME_ALERT', title: '告警记录' };
  if ((found = match(/^\/realtime\/paimon-tables\/(\d+)/))) return { type: 'REALTIME_TABLE', title: `实时表 #${found[1]}`, entityId: found[1] };
  if (pathname.startsWith('/realtime/paimon-tables')) return { type: 'REALTIME_TABLE', title: '实时表管理' };
  if (pathname.startsWith('/platform')) return { type: 'PLATFORM_STATUS', title: '平台状态' };
  if (pathname.startsWith('/executions')) return { type: 'OFFLINE_EXECUTION', title: '执行中心' };
  if (pathname.startsWith('/tasks/unions')) return { type: 'OFFLINE_VERSION', title: '版本集合' };
  if (pathname.startsWith('/tasks')) return { type: 'OFFLINE_TASK', title: '离线任务' };
  return undefined;
}

export default function RouteAiAssistant() {
  const location = useLocation();
  const [open, setOpen] = useState(false);
  const [pageContext, setPageContext] = useState<Partial<AiContext>>({});
  const resolved = useMemo(() => resolveRouteAiContext(location.pathname, location.search), [location.pathname, location.search]);
  useEffect(() => {
    setPageContext({});
    const receive = (rawEvent: Event) => setPageContext((rawEvent as CustomEvent<Partial<AiContext>>).detail || {});
    window.addEventListener('sql-agent:ai-context-update', receive);
    window.dispatchEvent(new Event('sql-agent:ai-context-request'));
    return () => window.removeEventListener('sql-agent:ai-context-update', receive);
  }, [location.pathname, location.search]);
  if (!resolved) return null;
  const context: AiContext = {
    contextType: pageContext.contextType ?? resolved.type,
    entityId: pageContext.entityId ?? resolved.entityId,
    parentId: pageContext.parentId ?? resolved.parentId,
    title: pageContext.title ?? resolved.title,
    versionNo: pageContext.versionNo ?? resolved.versionNo,
    revision: pageContext.revision,
    draft: pageContext.draft,
  };
  return (
    <>
      <Tooltip title="打开页面 AI">
        <Button className="route-ai-entry" type="primary" shape="circle" icon={<RobotOutlined />} onClick={() => setOpen(true)} aria-label="打开页面 AI" />
      </Tooltip>
      <AiAssistantDrawer
        open={open}
        context={context}
        onClose={() => setOpen(false)}
        onApplyProposal={(proposal: AiProposal): ProposalActionResult => {
          const inspected = inspectProposal(proposal, context);
          if (inspected.status !== 'APPLIED') return inspected;
          const handled = !window.dispatchEvent(new CustomEvent('sql-agent:apply-ai-proposal', {
            detail: proposal,
            cancelable: true,
          }));
          return handled
            ? { status: 'APPLIED', message: '已应用到当前页面草稿，仍需使用原页面按钮保存或发布' }
            : { status: 'READ_ONLY', message: '当前页面没有可写入的编辑表单，请进入对应编辑页后应用' };
        }}
      />
    </>
  );
}
