import { useCallback, useEffect, useMemo, useState } from 'react';
import { Button, Input, Modal, Select, Space, Table, Tag, Tooltip, Typography, message } from 'antd';
import { DeleteOutlined, DownOutlined, PlusOutlined, ReloadOutlined, SearchOutlined, UpOutlined } from '@ant-design/icons';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { debugManagedTask, deleteManagedTask, enableManagedTask, getManagedTask, listManagedTasks, stopManagedTask } from '../api';
import type { ManagedTask, ManagedTaskType } from '../types';
import RealtimeManagedTaskDetailModal from '../components/RealtimeManagedTaskDetailModal';

const ACTIVE = ['submitting', 'running', 'stopping', 'restarting'];
const statusLabel: Record<string, string> = { not_running: '未运行', submitting: '提交中', running: '运行中', restarting: '重启中', stopping: '停止中', failed: '失败', canceled: '已取消', finished: '已完成' };
const statusColor: Record<string, string> = { running: 'success', submitting: 'processing', restarting: 'processing', stopping: 'warning', failed: 'error', finished: 'success' };
const positive = (value: string | null, fallback: number) => { const number = Number(value); return Number.isInteger(number) && number > 0 ? number : fallback; };

interface Props { taskType: ManagedTaskType; workspace?: boolean }

export default function RealtimeManagedTasksPage({ taskType, workspace = false }: Props) {
  const navigate = useNavigate(); const location = useLocation(); const [searchParams, setSearchParams] = useSearchParams();
  const label = taskType === 'compute' ? '计算' : '出仓';
  const requestedTaskId = positive(searchParams.get('taskId'), 0);
  const requestedTab = (searchParams.get('tab') || 'instances') as 'instances' | 'detail' | 'runtime' | 'alerts' | 'changes' | 'versions';
  const [query, setQuery] = useState(() => ({ keyword: searchParams.get('q') ?? '', status: searchParams.get('status') ?? 'all', tableKeyword: searchParams.get('table') ?? '', owner: searchParams.get('owner') ?? '', lastOperator: searchParams.get('operator') ?? '' }));
  const [submitted, setSubmitted] = useState(query); const [expanded, setExpanded] = useState(Boolean(query.owner || query.lastOperator));
  const [page, setPage] = useState(() => positive(searchParams.get('page'), 1)); const [pageSize, setPageSize] = useState(() => positive(searchParams.get('pageSize'), 20));
  const [rows, setRows] = useState<ManagedTask[]>([]); const [total, setTotal] = useState(0); const [loading, setLoading] = useState(false); const [detail, setDetail] = useState<ManagedTask>();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams({ page: String(page), pageSize: String(pageSize) });
      if (submitted.keyword) params.set('keyword', submitted.keyword); if (submitted.status !== 'all') params.set('status', submitted.status);
      if (submitted.tableKeyword) params.set('sourceKeyword', submitted.tableKeyword); if (submitted.owner) params.set('owner', submitted.owner); if (submitted.lastOperator) params.set('lastOperator', submitted.lastOperator);
      const result = await listManagedTasks(taskType, params); setRows(result.records); setTotal(result.total);
    } catch (error) { message.error((error as Error).message); } finally { setLoading(false); }
  }, [page, pageSize, submitted, taskType]);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    if (!requestedTaskId || detail?.id === requestedTaskId) return;
    void getManagedTask(requestedTaskId).then(setDetail).catch((error) => message.error((error as Error).message));
  }, [detail?.id, requestedTaskId]);
  useEffect(() => {
    const next = new URLSearchParams({ page: String(page), pageSize: String(pageSize) });
    if (submitted.keyword) next.set('q', submitted.keyword); if (submitted.status !== 'all') next.set('status', submitted.status); if (submitted.tableKeyword) next.set('table', submitted.tableKeyword); if (submitted.owner) next.set('owner', submitted.owner); if (submitted.lastOperator) next.set('operator', submitted.lastOperator);
    if (requestedTaskId) next.set('taskId', String(requestedTaskId)); if (requestedTab !== 'instances') next.set('tab', requestedTab);
    setSearchParams(next, { replace: true });
  }, [page, pageSize, requestedTab, requestedTaskId, setSearchParams, submitted]);
  useEffect(() => { if (!rows.some((row) => ACTIVE.includes(row.status) || ACTIVE.includes(row.latestInstanceStatus ?? ''))) return undefined; const timer = window.setInterval(() => void load(), 10000); return () => window.clearInterval(timer); }, [load, rows]);

  const path = `/realtime/${taskType}`;
  const openEdit = (row?: ManagedTask) => navigate(row ? `${path}/${row.id}/edit` : `${path}/new`, { state: { returnTo: `${location.pathname}${location.search}`, taskName: row?.name } });
  const openDetail = async (row: ManagedTask) => { try { setDetail(await getManagedTask(row.id)); } catch (error) { message.error((error as Error).message); } };
  const action = async (name: string, operation: () => Promise<unknown>) => { try { await operation(); message.success(`${name}请求已提交`); await load(); } catch (error) { message.error((error as Error).message); } };
  const reset = () => { const empty = { keyword: '', status: 'all', tableKeyword: '', owner: '', lastOperator: '' }; setQuery(empty); setSubmitted(empty); setPage(1); };
  const ownerOptions = useMemo(() => Array.from(new Set(rows.map((row) => row.owner).filter(Boolean))).map((value) => ({ label: value, value })), [rows]);

  return <div className="realtime-page realtime-sync-tasks-page"><section className="realtime-sync-main-panel">
    {!workspace && <div className="realtime-page-header"><Typography.Title level={4}>实时{label}任务</Typography.Title><Space><Tooltip title="刷新"><Button aria-label="刷新" icon={<ReloadOutlined />} onClick={() => void load()} /></Tooltip><Tooltip title={`新建${label}任务`}><Button aria-label={`新建${label}任务`} type="primary" icon={<PlusOutlined />} onClick={() => openEdit()} /></Tooltip></Space></div>}
    <div className="realtime-sync-filter-section"><div className="realtime-sync-query-filter">
      <div className="realtime-sync-filter-item"><label>任务关键字：</label><Input allowClear placeholder="任务ID / 名称 / 描述" value={query.keyword} onChange={(event) => setQuery({ ...query, keyword: event.target.value })} onPressEnter={() => { setSubmitted(query); setPage(1); }} /></div>
      <div className="realtime-sync-filter-item"><label>任务类型：</label><Select value={taskType} disabled options={[{ value: taskType, label: `实时${label}任务` }]} /></div>
      <div className="realtime-sync-filter-item"><label>任务状态：</label><Select value={query.status} onChange={(status) => setQuery({ ...query, status })} options={[{ value: 'all', label: '全部' }, ...Object.entries(statusLabel).filter(([key]) => ['not_running', 'submitting', 'running', 'stopping', 'restarting', 'failed'].includes(key)).map(([value, text]) => ({ value, label: text }))]} /></div>
      <div className="realtime-sync-filter-item"><label>实时表：</label><Input allowClear placeholder="库名、表名或完整表名" value={query.tableKeyword} onChange={(event) => setQuery({ ...query, tableKeyword: event.target.value })} onPressEnter={() => { setSubmitted(query); setPage(1); }} /></div>
      <Space className="realtime-sync-filter-actions"><Button onClick={reset}>重置</Button><Button type="primary" icon={<SearchOutlined />} onClick={() => { setSubmitted(query); setPage(1); }}>查询</Button><Button type="link" aria-expanded={expanded} onClick={() => setExpanded((value) => !value)}>{expanded ? <>收起 <UpOutlined /></> : <>展开 <DownOutlined /></>}</Button><Tooltip title="刷新"><Button aria-label="刷新" icon={<ReloadOutlined />} onClick={() => void load()} /></Tooltip><Tooltip title={`新建${label}任务`}><Button aria-label={`新建${label}任务`} type="primary" icon={<PlusOutlined />} onClick={() => openEdit()} /></Tooltip></Space>
      {expanded && <><div className="realtime-sync-filter-item"><label>责任人：</label><Select showSearch optionFilterProp="label" value={query.owner || 'all'} onChange={(owner) => setQuery({ ...query, owner: owner === 'all' ? '' : owner })} options={[{ label: '全部', value: 'all' }, ...ownerOptions]} placeholder="请输入成员名称进行搜索" /></div><div className="realtime-sync-filter-item"><label>最近操作人：</label><Input allowClear value={query.lastOperator} onChange={(event) => setQuery({ ...query, lastOperator: event.target.value })} placeholder="输入操作人" onPressEnter={() => { setSubmitted(query); setPage(1); }} /></div></>}
    </div></div>
    <div className="realtime-sync-table-section"><Table rowKey="id" loading={loading} dataSource={rows} scroll={{ x: 1500 }} rowClassName="clickable-task-row" onRow={(row) => ({ onClick: () => void openDetail(row), onKeyDown: (event) => { if (event.key === 'Enter') void openDetail(row); }, role: 'button', tabIndex: 0 })} pagination={{ current: page, pageSize, total, showSizeChanger: true, pageSizeOptions: [20, 50, 100], showTotal: (value) => `共 ${value} 条`, onChange: (next, size) => { setPage(next); setPageSize(size); } }} columns={[
      { title: '任务 ID', dataIndex: 'id', width: 100, fixed: 'left' }, { title: '任务名称', dataIndex: 'name', width: 240, fixed: 'left', ellipsis: true, render: (value) => <Typography.Link>{value}</Typography.Link> },
      { title: '任务描述', dataIndex: 'description', width: 260, ellipsis: { showTitle: false }, render: (value) => <Tooltip title={value}><span>{value || '-'}</span></Tooltip> }, { title: '任务状态', width: 110, render: (_, row) => <Tag color={statusColor[row.status]}>{statusLabel[row.status] || row.status}</Tag> },
      { title: '实时表依赖', width: 360, ellipsis: { showTitle: false }, render: (_, row) => { const value = row.tableReferences?.map((item) => `${item.referenceRole === 'INPUT' ? '输入' : '输出'}：${item.databaseName}.${item.tableName}`).join('；') || '-'; return <Tooltip title={value}><span>{value}</span></Tooltip>; } },
      { title: '负责人', dataIndex: 'owner', width: 110 }, { title: '最近操作人', dataIndex: 'lastOperator', width: 120, render: (value) => value || '-' }, { title: '最近操作时间', dataIndex: 'lastOperationTime', width: 175, render: (value) => value || '-' },
      { title: '操作', fixed: 'right', width: 270, render: (_, row) => { const state = row.latestInstanceStatus || row.status; const active = ACTIVE.includes(state); return <Space size={12} onClick={(event) => event.stopPropagation()}>{state === 'running' ? <Typography.Link onClick={() => void action('停止', () => stopManagedTask(row.id))}>停止</Typography.Link> : <Typography.Link disabled={active} onClick={() => !active && void action('启动', () => enableManagedTask(row.id))}>启动</Typography.Link>}<Typography.Link onClick={() => void action('调试', () => debugManagedTask(row.id))}>调试</Typography.Link><Typography.Link disabled={active} onClick={() => !active && openEdit(row)}>编辑</Typography.Link><Typography.Link type="danger" disabled={active} onClick={() => !active && Modal.confirm({ title: `确认删除任务 ${row.name}？`, content: '历史版本和实例记录保留，实时表不会删除。', okText: '删除', cancelText: '取消', okButtonProps: { danger: true }, onOk: () => action('删除', () => deleteManagedTask(row.id)) })}><DeleteOutlined /> 删除</Typography.Link></Space>; } },
    ]} /></div>
  </section><RealtimeManagedTaskDetailModal task={detail} initialTab={requestedTab} onClose={() => setDetail(undefined)} /></div>;
}
