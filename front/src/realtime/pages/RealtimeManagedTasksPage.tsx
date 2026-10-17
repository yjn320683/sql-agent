import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Button, Input, Modal, Select, Space, Switch, Table, Tag, Tooltip, Typography, message } from 'antd';
import { DeleteOutlined, DownOutlined, PlusOutlined, ReloadOutlined, SearchOutlined, UpOutlined } from '@ant-design/icons';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { deleteManagedTask, enableManagedTask, getManagedTask, listManagedTasks, stopManagedTask, updateRealtimeTaskStatus } from '../api';
import type { ManagedTask, ManagedTaskType } from '../types';
import RealtimeManagedTaskDetailModal from '../components/RealtimeManagedTaskDetailModal';
import RealtimeManagedTaskEditorPage from './RealtimeManagedTaskEditorPage';

const ACTIVE = ['submitting', 'running', 'stopping', 'restarting'];
const statusLabel: Record<string, string> = { not_running: '未运行', submitting: '提交中', running: '运行中', restarting: '重启中', stopping: '停止中', failed: '失败', canceled: '已取消', finished: '已完成' };
const statusColor: Record<string, string> = { running: 'success', submitting: 'processing', restarting: 'processing', stopping: 'warning', failed: 'error', finished: 'success' };
const positive = (value: string | null, fallback: number) => { const number = Number(value); return Number.isInteger(number) && number > 0 ? number : fallback; };

interface Props { taskType: ManagedTaskType; workspace?: boolean }

export default function RealtimeManagedTasksPage({ taskType, workspace = false }: Props) {
  const navigate = useNavigate(); const location = useLocation(); const [searchParams, setSearchParams] = useSearchParams();
  const label = taskType === 'compute' ? '计算' : '出仓';
  const requestedTaskId = positive(searchParams.get('taskId'), 0);
  const requestedTab = (searchParams.get('tab') === 'detail' ? 'instances' : searchParams.get('tab') || 'instances') as 'instances' | 'runtime' | 'debug-report' | 'diagnostics' | 'alerts' | 'changes' | 'versions';
  const [query, setQuery] = useState(() => ({ keyword: searchParams.get('q') ?? '', taskStatus: searchParams.get('taskStatus') ?? 'all', instanceStatus: searchParams.get('instanceStatus') ?? searchParams.get('status') ?? 'all', tableKeyword: searchParams.get('table') ?? '', owner: searchParams.get('owner') ?? '', lastOperator: searchParams.get('operator') ?? '' }));
  const [submitted, setSubmitted] = useState(query); const [expanded, setExpanded] = useState(Boolean(query.tableKeyword || query.owner || query.lastOperator));
  const [page, setPage] = useState(() => positive(searchParams.get('page'), 1)); const [pageSize, setPageSize] = useState(() => positive(searchParams.get('pageSize'), 20));
  const [rows, setRows] = useState<ManagedTask[]>([]); const [total, setTotal] = useState(0); const [loading, setLoading] = useState(false); const [detail, setDetail] = useState<ManagedTask>();
  const [debugTask, setDebugTask] = useState<ManagedTask>();
  const debugRequestRef = useRef(0);
  const [statusLoadingIds, setStatusLoadingIds] = useState<Set<number>>(() => new Set());

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams({ page: String(page), pageSize: String(pageSize) });
      if (submitted.keyword) params.set('keyword', submitted.keyword); if (submitted.taskStatus !== 'all') params.set('taskStatus', submitted.taskStatus); if (submitted.instanceStatus !== 'all') params.set('instanceStatus', submitted.instanceStatus);
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
    if (submitted.keyword) next.set('q', submitted.keyword); if (submitted.taskStatus !== 'all') next.set('taskStatus', submitted.taskStatus); if (submitted.instanceStatus !== 'all') next.set('instanceStatus', submitted.instanceStatus); if (submitted.tableKeyword) next.set('table', submitted.tableKeyword); if (submitted.owner) next.set('owner', submitted.owner); if (submitted.lastOperator) next.set('operator', submitted.lastOperator);
    if (requestedTaskId) next.set('taskId', String(requestedTaskId)); if (requestedTab !== 'instances') next.set('tab', requestedTab);
    setSearchParams(next, { replace: true });
  }, [page, pageSize, requestedTab, requestedTaskId, setSearchParams, submitted]);
  useEffect(() => { if (!rows.some((row) => ACTIVE.includes(row.status) || ACTIVE.includes(row.latestInstanceStatus ?? ''))) return undefined; const timer = window.setInterval(() => void load(), 10000); return () => window.clearInterval(timer); }, [load, rows]);

  const path = `/realtime/${taskType}`;
  const openEdit = (row?: ManagedTask) => navigate(row ? `${path}/${row.id}/edit` : `${path}/new`, { state: { returnTo: `${location.pathname}${location.search}`, taskName: row?.name } });
  const openView = (row: ManagedTask) => navigate(`${path}/${row.id}/view`, { state: { returnTo: `${location.pathname}${location.search}`, taskName: row.name } });
  const openDetail = async (row: ManagedTask) => { try { setDetail(await getManagedTask(row.id)); } catch (error) { message.error((error as Error).message); } };
  const openDebug = async (row: ManagedTask) => {
    const sequence = ++debugRequestRef.current;
    try { const value = await getManagedTask(row.id); if (sequence === debugRequestRef.current) setDebugTask(value); }
    catch (error) { if (sequence === debugRequestRef.current) message.error((error as Error).message); }
  };
  const action = async (name: string, operation: () => Promise<unknown>) => { try { await operation(); message.success(`${name}请求已提交`); await load(); } catch (error) { message.error((error as Error).message); } };
  const changeOnlineStatus = async (row: ManagedTask, checked: boolean) => {
    setStatusLoadingIds((current) => new Set(current).add(row.id));
    try { await updateRealtimeTaskStatus(row.id, checked ? 1 : 0); setRows((current) => current.map((item) => item.id === row.id ? { ...item, taskStatus: checked ? 1 : 0 } : item)); void load(); }
    catch (error) { message.error((error as Error).message); }
    finally { setStatusLoadingIds((current) => { const next = new Set(current); next.delete(row.id); return next; }); }
  };
  const reset = () => { const empty = { keyword: '', taskStatus: 'all', instanceStatus: 'all', tableKeyword: '', owner: '', lastOperator: '' }; setQuery(empty); setSubmitted(empty); setPage(1); };
  const ownerOptions = useMemo(() => Array.from(new Set(rows.map((row) => row.owner).filter(Boolean))).map((value) => ({ label: value, value })), [rows]);

  return <div className="page-content realtime-page realtime-sync-tasks-page"><section className="realtime-sync-main-panel">
    {!workspace && <div className="realtime-page-header"><Typography.Title level={4}>实时{label}任务</Typography.Title><Space><Tooltip title="刷新"><Button aria-label="刷新" icon={<ReloadOutlined />} onClick={() => void load()} /></Tooltip><Tooltip title={`新建${label}任务`}><Button aria-label={`新建${label}任务`} type="primary" icon={<PlusOutlined />} onClick={() => openEdit()} /></Tooltip></Space></div>}
    <div className="realtime-sync-filter-section"><div className="realtime-sync-query-filter">
      <div className="realtime-sync-filter-item"><label>任务关键字：</label><Input allowClear placeholder="任务ID / 名称 / 描述" value={query.keyword} onChange={(event) => setQuery({ ...query, keyword: event.target.value })} onPressEnter={() => { setSubmitted(query); setPage(1); }} /></div>
      <div className="realtime-sync-filter-item"><label>任务状态：</label><Select value={query.taskStatus} onChange={(taskStatus) => setQuery({ ...query, taskStatus })} options={[{ value: 'all', label: '全部' }, { value: '1', label: '上线' }, { value: '0', label: '下线' }]} /></div>
      <div className="realtime-sync-filter-item"><label>运行状态：</label><Select value={query.instanceStatus} onChange={(instanceStatus) => setQuery({ ...query, instanceStatus })} options={[{ value: 'all', label: '全部' }, ...Object.entries(statusLabel).filter(([key]) => ['not_running', 'submitting', 'running', 'stopping', 'restarting', 'failed'].includes(key)).map(([value, text]) => ({ value, label: text }))]} /></div>
      {expanded && <><div className="realtime-sync-filter-item"><label>实时表：</label><Input allowClear placeholder="库名、表名或完整表名" value={query.tableKeyword} onChange={(event) => setQuery({ ...query, tableKeyword: event.target.value })} onPressEnter={() => { setSubmitted(query); setPage(1); }} /></div><div className="realtime-sync-filter-item"><label>责任人：</label><Select showSearch optionFilterProp="label" value={query.owner || 'all'} onChange={(owner) => setQuery({ ...query, owner: owner === 'all' ? '' : owner })} options={[{ label: '全部', value: 'all' }, ...ownerOptions]} placeholder="请输入成员名称进行搜索" /></div><div className="realtime-sync-filter-item"><label>最近操作人：</label><Input allowClear value={query.lastOperator} onChange={(event) => setQuery({ ...query, lastOperator: event.target.value })} placeholder="输入操作人" onPressEnter={() => { setSubmitted(query); setPage(1); }} /></div></>}
      <Space className="realtime-sync-filter-actions"><Button onClick={reset}>重置</Button><Button type="primary" icon={<SearchOutlined />} onClick={() => { setSubmitted(query); setPage(1); }}>查询</Button><Button type="link" aria-expanded={expanded} onClick={() => setExpanded((value) => !value)}>{expanded ? <>收起 <UpOutlined /></> : <>展开 <DownOutlined /></>}</Button><Tooltip title="刷新"><Button aria-label="刷新" icon={<ReloadOutlined />} onClick={() => void load()} /></Tooltip><Tooltip title={`新建${label}任务`}><Button aria-label={`新建${label}任务`} type="primary" icon={<PlusOutlined />} onClick={() => openEdit()} /></Tooltip></Space>
    </div></div>
    <div className="realtime-sync-table-section"><Table rowKey="id" loading={loading} dataSource={rows} scroll={{ x: 1500 }} rowClassName="clickable-task-row" onRow={(row) => ({ onClick: () => void openDetail(row), onKeyDown: (event) => { if (event.key === 'Enter') void openDetail(row); }, role: 'button', tabIndex: 0 })} pagination={{ current: page, pageSize, total, showSizeChanger: true, pageSizeOptions: [20, 50, 100], showTotal: (value) => `共 ${value} 条`, onChange: (next, size) => { setPage(next); setPageSize(size); } }} columns={[
      { title: '任务 ID', dataIndex: 'id', width: 100, fixed: 'left' }, { title: '任务名称', dataIndex: 'name', width: 240, fixed: 'left', ellipsis: true, render: (value) => <Typography.Link>{value}</Typography.Link> },
      { title: '任务描述', dataIndex: 'description', width: 260, ellipsis: { showTitle: false }, render: (value) => <Tooltip title={value}><span>{value || '-'}</span></Tooltip> },
      { title: '任务状态', dataIndex: 'taskStatus', width: 120, render: (_, row) => { const active = ACTIVE.includes(row.instanceStatus ?? row.status) || ACTIVE.includes(row.latestInstanceStatus ?? ''); return <Tooltip title={active ? '存在活动正式实例，请先完成 Savepoint 停止' : undefined}><span onClick={(event) => event.stopPropagation()}><Switch checked={row.taskStatus === 1} checkedChildren="上线" unCheckedChildren="下线" disabled={active} loading={statusLoadingIds.has(row.id)} onChange={(checked) => void changeOnlineStatus(row, checked)} /></span></Tooltip>; } },
      { title: '运行状态', dataIndex: 'instanceStatus', width: 120, render: (value, row) => <Tag color={statusColor[value ?? row.status]}>{statusLabel[value ?? row.status] || value || row.status}</Tag> },
      { title: '实时表依赖', width: 360, ellipsis: { showTitle: false }, render: (_, row) => { const value = row.tableReferences?.map((item) => `${item.referenceRole === 'INPUT' ? '输入' : '输出'}：${item.databaseName}.${item.tableName}`).join('；') || '-'; return <Tooltip title={value}><span>{value}</span></Tooltip>; } },
      { title: '负责人', dataIndex: 'owner', width: 110 }, { title: '最近操作人', dataIndex: 'lastOperator', width: 120, render: (value) => value || '-' }, { title: '最近操作时间', dataIndex: 'lastOperationTime', width: 175, render: (value) => value || '-' },
      { title: '操作', fixed: 'right', width: 300, render: (_, row) => { const state = row.instanceStatus || row.status; const active = ACTIVE.includes(state); const editable = !active && row.taskStatus !== 1; return <Space size={12} onClick={(event) => event.stopPropagation()}>{state === 'running' ? <Typography.Link onClick={() => void action('停止', () => stopManagedTask(row.id))}>停止</Typography.Link> : <Typography.Link disabled={active || row.taskStatus !== 1} onClick={() => !active && row.taskStatus === 1 && void action('启动', () => enableManagedTask(row.id))}>启动</Typography.Link>}<Typography.Link onClick={() => openView(row)}>查看</Typography.Link><Typography.Link disabled={!editable} onClick={() => editable && openEdit(row)}>编辑</Typography.Link><Typography.Link onClick={() => void openDebug(row)}>调试</Typography.Link><Typography.Link type="danger" disabled={!editable} onClick={() => editable && Modal.confirm({ title: `确认删除任务 ${row.name}？`, content: '历史版本和实例记录保留，实时表不会删除。', okText: '删除', cancelText: '取消', okButtonProps: { danger: true }, onOk: () => action('删除', () => deleteManagedTask(row.id)) })}><DeleteOutlined /> 删除</Typography.Link></Space>; } },
    ]} /></div>
  </section><RealtimeManagedTaskDetailModal task={detail} initialTab={requestedTab} onClose={() => setDetail(undefined)} />
    {debugTask && <RealtimeManagedTaskEditorPage taskType={taskType} debugTask={debugTask} onDebugClose={() => setDebugTask(undefined)} onDebugStarted={() => { setDetail(debugTask); setDebugTask(undefined); void load(); }} />}
  </div>;
}
