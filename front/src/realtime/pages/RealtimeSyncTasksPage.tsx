import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  Button,
  Checkbox,
  DatePicker,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd';
import type { TableProps } from 'antd';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import {
  DeleteOutlined,
  DownOutlined,
  LinkOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
  UnorderedListOutlined,
  UpOutlined,
} from '@ant-design/icons';
import {
  canEnableSyncTask,
  deleteSyncTask,
  getStateHistory,
  getSyncTask,
  listMappings,
  listServers,
  listSyncTasks,
  listTaskParams,
  startSyncTask,
  stopSyncTask,
} from '../api';
import type {
  SyncTask,
  SyncTaskListItem,
  RealtimeServer,
  TaskMapping,
  TaskParam,
  SyncTaskStartPolicy,
} from '../types';
import SyncDebugDrawer from '../components/SyncDebugDrawer';
import SyncTaskDetailDrawer from '../components/SyncTaskDetailDrawer';
import { showStateRecoveryFallback, syncStartMethodOptions } from '../components/syncStartMethod';

const ACTIVE = ['submitting', 'running', 'stopping', 'restarting'];
const statusColor: Record<string, string> = {
  running: 'success', submitting: 'processing', restarting: 'processing', stopping: 'warning',
  failed: 'error', not_running: 'default', canceled: 'default', finished: 'success',
};
const statusLabel: Record<string, string> = {
  running: '运行中', submitting: '提交中', restarting: '重启中', stopping: '停止中', failed: '失败',
  not_running: '未运行', canceled: '已取消', finished: '已完成',
};

const emptyQuery = { keyword: '', status: 'all', paimonTableKeyword: '', owner: '', lastOperator: '' };
const positiveNumber = (value: string | null, fallback: number) => {
  const number = Number(value);
  return Number.isInteger(number) && number > 0 ? number : fallback;
};

interface Props {
  workspace?: boolean;
}

export default function RealtimeSyncTasksPage({ workspace = false }: Props) {
  const location = useLocation();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const requestedTaskId = positiveNumber(searchParams.get('taskId'), 0);
  const requestedTab = searchParams.get('tab') || 'instances';
  const [query, setQuery] = useState(() => ({
    ...emptyQuery,
    keyword: searchParams.get('q') ?? searchParams.get('keyword') ?? '', status: searchParams.get('status') ?? 'all',
    paimonTableKeyword: searchParams.get('paimonTable') ?? searchParams.get('paimonTableKeyword') ?? searchParams.get('tableKeyword') ?? '',
    owner: searchParams.get('owner') ?? '', lastOperator: searchParams.get('operator') ?? searchParams.get('lastOperator') ?? '',
  }));
  const [submittedQuery, setSubmittedQuery] = useState(query);
  const [filtersExpanded, setFiltersExpanded] = useState(() => Boolean(query.owner || query.lastOperator));
  const [page, setPage] = useState(() => positiveNumber(searchParams.get('page'), 1));
  const [pageSize, setPageSize] = useState(() => positiveNumber(searchParams.get('pageSize'), 20));
  const [sortField, setSortField] = useState(() => searchParams.get('sort') || 'lastOperationTime');
  const [sortOrder, setSortOrder] = useState<'asc' | 'desc'>(() => searchParams.get('order') === 'asc' ? 'asc' : 'desc');
  const [rows, setRows] = useState<SyncTaskListItem[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [detail, setDetail] = useState<SyncTask>();
  const [detailLoading, setDetailLoading] = useState(false);
  const [mappingTask, setMappingTask] = useState<SyncTaskListItem>();
  const [mappingRows, setMappingRows] = useState<TaskMapping[]>([]);
  const [actionTask, setActionTask] = useState<{ row: SyncTaskListItem; startPolicy?: SyncTaskStartPolicy }>();
  const [actionLoading, setActionLoading] = useState(false);
  const [debugTask, setDebugTask] = useState<SyncTask>();
  const [debugServers, setDebugServers] = useState<RealtimeServer[]>([]);
  const [debugParams, setDebugParams] = useState<TaskParam[]>([]);
  const [debugSupportLoading, setDebugSupportLoading] = useState(true);
  const [taskStopTarget, setTaskStopTarget] = useState<SyncTaskListItem>();
  const [allowDirectStop, setAllowDirectStop] = useState(false);
  const [actionForm] = Form.useForm();
  const actionStartType = Form.useWatch('startType', actionForm);
  const actionConsumePointMode = Form.useWatch('consumePointMode', actionForm);
  const actionStatePath = Form.useWatch('statePath', actionForm);
  const actionRecoveryFallbackMode = Form.useWatch('recoveryFallbackMode', actionForm);
  const actionSourceStartupTime = Form.useWatch('sourceStartupTime', actionForm);
  const [resetFailedStateRecovery, setResetFailedStateRecovery] = useState(false);
  const [stateHistory, setStateHistory] = useState<Record<string, unknown>[]>([]);
  const submitActionRef = useRef(false);
  const detailRequestRef = useRef(0);
  const actionRequestRef = useRef(0);
  const actionSelectionVersionRef = useRef(0);
  const debugRequestRef = useRef(0);
  const actionRequiredStatePath = actionTask?.startPolicy?.requiredStatePath;
  const actionShowStateRecoveryReset = showStateRecoveryFallback(actionTask?.startPolicy);
  const actionRecoveryFallbackIncomplete = Boolean(
    resetFailedStateRecovery
    && (!actionRecoveryFallbackMode
      || (actionRecoveryFallbackMode === 'checkpoint' && !actionStatePath)
      || (actionRecoveryFallbackMode === 'timestamp' && !actionSourceStartupTime)),
  );
  const actionConfirmationDisabled = actionRecoveryFallbackIncomplete
    || (actionStartType !== 'direct' && !actionStatePath)
    || (actionConsumePointMode === 'timestamp' && !actionSourceStartupTime);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams({ page: String(page), pageSize: String(pageSize), sort: sortField, order: sortOrder });
      Object.entries(submittedQuery).forEach(([key, value]) => { if (value && value !== 'all') params.set(key, value); });
      const result = await listSyncTasks(params);
      setRows(result.items); setTotal(result.total);
    } catch (error) {
      message.error((error as Error).message);
    } finally {
      setLoading(false);
    }
  }, [page, pageSize, sortField, sortOrder, submittedQuery]);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    if (!requestedTaskId || detail?.id === requestedTaskId) return;
    const sequence = ++detailRequestRef.current;
    setDetailLoading(true);
    void getSyncTask(requestedTaskId).then((value) => {
      if (sequence === detailRequestRef.current) setDetail(value);
    }).catch((error) => { if (sequence === detailRequestRef.current) message.error((error as Error).message); })
      .finally(() => { if (sequence === detailRequestRef.current) setDetailLoading(false); });
  }, [detail?.id, requestedTaskId]);
  useEffect(() => {
    let active = true;
    setDebugSupportLoading(true);
    void Promise.all([listServers(), listTaskParams()])
      .then(([servers, params]) => { if (active) { setDebugServers(servers); setDebugParams(params); } })
      .catch(() => { if (active) { setDebugServers([]); setDebugParams([]); } })
      .finally(() => { if (active) setDebugSupportLoading(false); });
    return () => { active = false; };
  }, []);
  useEffect(() => {
    const next = new URLSearchParams();
    next.set('page', String(page)); next.set('pageSize', String(pageSize)); next.set('sort', sortField); next.set('order', sortOrder);
    const urlKeys: Record<keyof typeof submittedQuery, string> = {
      keyword: 'q', status: 'status', paimonTableKeyword: 'paimonTable', owner: 'owner', lastOperator: 'operator',
    };
    Object.entries(submittedQuery).forEach(([key, value]) => {
      if (value && value !== 'all') next.set(urlKeys[key as keyof typeof submittedQuery], value);
    });
    if (requestedTaskId) next.set('taskId', String(requestedTaskId));
    if (requestedTab !== 'instances') next.set('tab', requestedTab);
    setSearchParams(next, { replace: true });
  }, [page, pageSize, requestedTab, requestedTaskId, setSearchParams, sortField, sortOrder, submittedQuery]);
  useEffect(() => {
    if (!rows.some((row) => ACTIVE.includes(row.status) || ACTIVE.includes(row.runtimeStatus ?? ''))) return undefined;
    const timer = window.setInterval(() => void load(), 10000);
    return () => window.clearInterval(timer);
  }, [rows, load]);

  const openEdit = (row?: SyncTaskListItem) => {
    const returnTo = `${location.pathname}${location.search}`;
    navigate(row ? `/realtime/sync-tasks/${row.id}/edit` : '/realtime/sync-tasks/new', {
      state: { returnTo, taskName: row?.name },
    });
  };

  const openDetail = async (row: SyncTaskListItem) => {
    const sequence = ++detailRequestRef.current;
    setDetailLoading(true);
    try {
      const value = await getSyncTask(row.id);
      if (sequence === detailRequestRef.current) setDetail(value);
    } catch (error) { if (sequence === detailRequestRef.current) message.error((error as Error).message); }
    finally { if (sequence === detailRequestRef.current) setDetailLoading(false); }
  };

  const openMappings = async (row: SyncTaskListItem) => {
    setMappingTask(row);
    try { setMappingRows(row.mapping?.length ? row.mapping : await listMappings(row.id)); }
    catch (error) { message.error((error as Error).message); }
  };

  const openAction = async (row: SyncTaskListItem) => {
    const sequence = ++actionRequestRef.current;
    setStateHistory([]);
    actionForm.resetFields();
    try {
      setActionLoading(true);
      const qualification = await canEnableSyncTask(row.id);
      if (sequence !== actionRequestRef.current) return;
      if (!qualification.canEnable) {
        message.warning(qualification.message || qualification.reason || '当前任务不满足启动条件');
        return;
      }
      let policy = qualification.startPolicy;
      if (!policy) {
        // 兼容前后端滚动更新；新接口返回策略时不会再请求完整任务详情。
        const task = await getSyncTask(row.id);
        if (sequence !== actionRequestRef.current) return;
        const editPolicy = task.editPolicy;
        policy = editPolicy ? {
          productionLocked: Boolean(editPolicy.productionLocked),
          syncTableSetChanged: Boolean(editPolicy.syncTableSetChanged),
          requiredStartType: editPolicy.requiredStartType,
          requiredStatePath: editPolicy.requiredStatePath,
          canResetConsumptionPoint: Boolean(editPolicy.productionLocked && !editPolicy.requiredStartType),
        } : undefined;
      }
      const requiredType = policy?.requiredStartType;
      const requiredPath = policy?.requiredStatePath;
      let initialStartType: 'direct' | 'checkpoint' | 'savepoint' = requiredType ?? 'direct';
      let initialStatePath = requiredPath;
      let initialFallbackMode: 'checkpoint' | 'timestamp' | undefined;
      let initialHistory: Record<string, unknown>[] = requiredPath
        ? [{ label: requiredPath, path: requiredPath }] : [];
      const mustUseFallback = Boolean(policy?.syncTableSetChanged) && !requiredPath;
      let requiresManualStartSelection = mustUseFallback;
      if (policy?.productionLocked && !requiredPath) {
        const shouldLoadSavepoints = !policy.syncTableSetChanged;
        const [savepoints, checkpoints] = await Promise.all([
          shouldLoadSavepoints ? getStateHistory(row.id, 'savepoint') : Promise.resolve([]),
          getStateHistory(row.id, 'checkpoint'),
        ]);
        if (sequence !== actionRequestRef.current) return;
        if (savepoints[0]) {
          initialStartType = 'savepoint';
          initialStatePath = String(savepoints[0].path);
          initialHistory = savepoints;
        } else if (checkpoints[0]) {
          initialStartType = 'checkpoint';
          initialStatePath = String(checkpoints[0].path);
          initialHistory = checkpoints;
          initialFallbackMode = mustUseFallback ? 'checkpoint' : undefined;
        } else {
          initialStartType = 'direct';
          initialStatePath = undefined;
          initialHistory = [];
          requiresManualStartSelection = true;
        }
      }
      setResetFailedStateRecovery(requiresManualStartSelection);
      setStateHistory(initialHistory);
      actionForm.setFieldsValue({
        startType: initialStartType, statePath: initialStatePath, consumePointMode: 'default',
        recoveryFallbackMode: initialFallbackMode, sourceStartupTime: undefined,
      });
      setActionTask({ row, startPolicy: policy });
    } catch (error) {
      if (sequence === actionRequestRef.current) message.error((error as Error).message);
    } finally {
      if (sequence === actionRequestRef.current) setActionLoading(false);
    }
  };

  const openDebug = async (row: SyncTaskListItem) => {
    const sequence = ++debugRequestRef.current;
    try { const value = await getSyncTask(row.id); if (sequence === debugRequestRef.current) setDebugTask(value); }
    catch (error) { if (sequence === debugRequestRef.current) message.error((error as Error).message); }
  };

  const submitAction = async () => {
    if (!actionTask || submitActionRef.current) return;
    submitActionRef.current = true;
    try {
      const values = await actionForm.validateFields();
      setActionLoading(true);
      await startSyncTask(actionTask.row.id, {
        startType: values.startType,
        ...(values.startType !== 'direct' && values.statePath ? { statePath: values.statePath } : {}),
        ...(values.consumePointMode === 'timestamp' && values.sourceStartupTime
          ? { sourceStartupTimestampMillis: values.sourceStartupTime.valueOf() } : {}),
      }, false);
      message.success('同步任务已提交');
      setActionTask(undefined); await load();
    } catch (error) { message.error((error as Error).message); }
    finally { submitActionRef.current = false; setActionLoading(false); }
  };

  const selectStartMethod = async (method: 'direct' | 'checkpoint' | 'savepoint' | 'timestamp') => {
    if (!actionTask) return;
    const selectionVersion = ++actionSelectionVersionRef.current;
    const startType = method === 'timestamp' ? 'direct' : method;
    const requiredStartType = actionTask.startPolicy?.requiredStartType;
    const requiredStatePath = actionTask.startPolicy?.requiredStatePath;
    const showStateRecoveryReset = Boolean(actionTask.startPolicy?.productionLocked
      && requiredStartType === 'savepoint' && requiredStatePath);
    const fallback = Boolean((actionTask.startPolicy?.syncTableSetChanged || showStateRecoveryReset)
      && (method === 'checkpoint' || method === 'timestamp'));
    setResetFailedStateRecovery(fallback);
    setStateHistory([]);
    actionForm.setFieldsValue({
      startType,
      consumePointMode: method === 'timestamp' ? 'timestamp' : 'default',
      recoveryFallbackMode: fallback ? method : undefined,
      statePath: method === 'savepoint' ? actionTask.startPolicy?.requiredStatePath : undefined,
      sourceStartupTime: undefined,
    });
    if (startType === 'direct' || (startType === 'savepoint' && actionTask.startPolicy?.requiredStatePath)) return;
    try {
      setActionLoading(true);
      const rows = await getStateHistory(actionTask.row.id, startType);
      if (selectionVersion !== actionSelectionVersionRef.current) return;
      setStateHistory(rows);
      actionForm.setFieldValue('statePath', rows[0]?.path);
    } catch (error) {
      if (selectionVersion === actionSelectionVersionRef.current) message.error((error as Error).message);
    } finally {
      if (selectionVersion === actionSelectionVersionRef.current) setActionLoading(false);
    }
  };

  const toggleStateRecoveryFallback = (checked: boolean) => {
    ++actionSelectionVersionRef.current;
    const requiredPath = actionTask?.startPolicy?.requiredStatePath;
    if (!checked) {
      setResetFailedStateRecovery(false);
      actionForm.setFieldsValue({
        startType: 'savepoint', statePath: requiredPath, consumePointMode: 'default',
        recoveryFallbackMode: undefined, sourceStartupTime: undefined,
      });
      if (requiredPath) setStateHistory([{ label: requiredPath, path: requiredPath }]);
      return;
    }
    setResetFailedStateRecovery(true);
    setStateHistory([]);
    actionForm.setFieldsValue({
      startType: 'direct', statePath: undefined, consumePointMode: 'default',
      recoveryFallbackMode: undefined, sourceStartupTime: undefined,
    });
  };

  const formatRuntime = (seconds?: number) => {
    if (seconds === undefined || seconds === null) return '-';
    if (seconds < 60) return `${seconds} s`;
    if (seconds < 3600) return `${(seconds / 60).toFixed(1)} min`;
    if (seconds < 86400) return `${(seconds / 3600).toFixed(1)} h`;
    return `${(seconds / 86400).toFixed(1)} d`;
  };

  const formatResourceMemory = (memoryMb?: number) => {
    if (memoryMb === undefined || memoryMb === null) return undefined;
    if (memoryMb < 1024) return `${memoryMb} MB`;
    const value = memoryMb / 1024;
    return `${Number.isInteger(value) ? value : value.toFixed(1)} GB`;
  };

  const renderResource = (row: SyncTaskListItem) => {
    const memory = formatResourceMemory(row.configuredMemoryMb);
    if (memory === undefined || row.configuredVCores === undefined) return '-';
    return <Tooltip title={<Space direction="vertical" size={0}><span>{row.taskManagerCount ?? '-'} 个 TaskManager + 1 个 JobManager</span><span>配置：并行度 {row.parallelism ?? '-'} / TM {row.taskManagerMemory ?? '-'} / JM {row.jobManagerMemory ?? '-'}</span></Space>}><span>{memory} / {row.configuredVCores} vCore</span></Tooltip>;
  };

  const ownerOptions = useMemo(() => Array.from(new Map(rows
    .filter((row) => Boolean(row.owner))
    .map((row) => [row.owner, { label: row.owner, value: row.owner }])).values()), [rows]);

  const columns = useMemo(() => [
    { title: '任务 ID', dataIndex: 'id', width: 100, fixed: 'left' as const, sorter: true },
    { title: '任务名称', dataIndex: 'name', width: 220, fixed: 'left' as const, ellipsis: true, sorter: true, render: (value: string) => <Typography.Link>{value}</Typography.Link> },
    { title: '任务描述', dataIndex: 'description', width: 260, ellipsis: { showTitle: false }, render: (value: string) => <Tooltip title={value}><span>{value || '-'}</span></Tooltip> },
    { title: '任务状态', dataIndex: 'status', width: 110, sorter: true, render: (value: string, row: SyncTaskListItem) => <Tooltip title={row.runtimeFailureMessage}><Tag color={statusColor[value]}>{statusLabel[value] ?? value}</Tag></Tooltip> },
    { title: '映射', width: 180, render: (_: unknown, row: SyncTaskListItem) => (row.mapping?.length ?? row.mappingCount ?? 0) > 0
      ? <Button className="realtime-mapping-link" type="link" size="small" icon={<UnorderedListOutlined />} onClick={(event) => { event.stopPropagation(); void openMappings(row); }}>{`查看全部 ${row.mapping?.length ?? row.mappingCount} 组`}</Button>
      : '-' },
    { title: '资源占用', width: 210, render: (_: unknown, row: SyncTaskListItem) => renderResource(row) },
    { title: 'Flink UI', width: 110, render: (_: unknown, row: SyncTaskListItem) => row.flinkUrl ? <Typography.Link href={row.flinkUrl} target="_blank" onClick={(event) => event.stopPropagation()}><LinkOutlined /> 打开</Typography.Link> : '-' },
    { title: '运行时长', dataIndex: 'runtimeSeconds', width: 120, sorter: true, render: (value: number) => formatRuntime(value) },
    { title: '负责人', dataIndex: 'owner', width: 110 },
    { title: '最近操作人', dataIndex: 'lastOperator', width: 120, render: (value: string) => value || '-' },
    { title: '最近操作时间', dataIndex: 'lastOperationTime', width: 175, sorter: true, render: (value: string) => value || '-' },
    {
      title: '操作', key: 'actions', fixed: 'right' as const, width: 260,
      render: (_: unknown, row: SyncTaskListItem) => {
        const status = row.runtimeStatus || row.status;
        const running = status === 'running';
        const restarting = status === 'restarting';
        const operating = status === 'submitting' || status === 'stopping' || restarting;
        const active = running || operating;
        return (
          <Space size={12} onClick={(event) => event.stopPropagation()}>
            {running || restarting
              ? <Tooltip title={row.managed === false ? '历史导入实例只读，不能在本平台停止' : undefined}><Typography.Link disabled={row.managed === false} onClick={() => { if (row.managed === false) return; setAllowDirectStop(false); setTaskStopTarget(row); }}>停止</Typography.Link></Tooltip>
              : <Typography.Link disabled={operating} onClick={() => !operating && void openAction(row)}>启动</Typography.Link>}
            <Typography.Link onClick={() => void openDebug(row)}>调试</Typography.Link>
            <Typography.Link disabled={active} onClick={() => !active && openEdit(row)}>编辑</Typography.Link>
            <Typography.Link type="danger" disabled={active} onClick={() => {
              if (active) return;
              Modal.confirm({
                title: `确认删除任务 ${row.name}？`,
                content: '删除后任务将从列表移除并停止后续同步；目标 Paimon 表和历史数据不会删除。',
                okText: '删除', cancelText: '取消', okButtonProps: { danger: true },
                onOk: async () => {
                  await deleteSyncTask(row.id);
                  setRows((current) => current.filter((item) => item.id !== row.id));
                  setTotal((current) => Math.max(0, current - 1));
                  message.success('任务已删除');
                  void load();
                },
              });
            }}><DeleteOutlined /> 删除</Typography.Link>
          </Space>
        );
      },
    },
  ], [load]);

  const handleTableChange: TableProps<SyncTaskListItem>['onChange'] = (_pagination, _filters, sorter) => {
    const selected = Array.isArray(sorter) ? sorter[0] : sorter;
    if (!selected?.order) {
      setSortField('lastOperationTime'); setSortOrder('desc'); setPage(1); return;
    }
    const fields: Record<string, string> = { id: 'id', name: 'name', status: 'status', runtimeSeconds: 'runtime', lastOperationTime: 'lastOperationTime' };
    setSortField(fields[String(selected.field)] ?? 'lastOperationTime');
    setSortOrder(selected.order === 'ascend' ? 'asc' : 'desc');
    setPage(1);
  };

  return (
    <div className="page-content realtime-page realtime-sync-tasks-page">
      <section className="realtime-sync-main-panel">
        {!workspace && <div className="realtime-page-header">
          <Typography.Title level={4}>实时同步任务</Typography.Title>
          <Space>
            <Tooltip title="刷新"><Button aria-label="刷新" icon={<ReloadOutlined />} onClick={() => void load()} /></Tooltip>
            <Tooltip title="新建同步任务"><Button aria-label="新建同步任务" type="primary" icon={<PlusOutlined />} onClick={() => openEdit()} /></Tooltip>
          </Space>
        </div>}
        <div className="realtime-sync-filter-section">
          <div className="realtime-sync-query-filter">
            <div className="realtime-sync-filter-item"><label>任务关键字：</label><Input allowClear placeholder="任务ID / 名称 / 描述" value={query.keyword} onChange={(event) => setQuery({ ...query, keyword: event.target.value })} onPressEnter={() => { setSubmittedQuery(query); setPage(1); }} /></div>
            <div className="realtime-sync-filter-item"><label>任务类型：</label><Select aria-label="任务类型" value="sync" disabled options={[{ label: '实时同步任务', value: 'sync' }]} /></div>
            <div className="realtime-sync-filter-item"><label>任务状态：</label><Select value={query.status} onChange={(status) => setQuery({ ...query, status })} options={[{ label: '全部', value: 'all' }, ...Object.entries(statusLabel).filter(([key]) => ['not_running', 'submitting', 'running', 'stopping', 'restarting', 'failed'].includes(key)).map(([value, label]) => ({ value, label }))]} /></div>
            <div className="realtime-sync-filter-item"><label>Paimon 表：</label><Input allowClear placeholder="库名、表名或完整表名" value={query.paimonTableKeyword} onChange={(event) => setQuery({ ...query, paimonTableKeyword: event.target.value })} onPressEnter={() => { setSubmittedQuery(query); setPage(1); }} /></div>
            <Space className="realtime-sync-filter-actions">
              <Button onClick={() => { setQuery(emptyQuery); setSubmittedQuery(emptyQuery); setSortField('lastOperationTime'); setSortOrder('desc'); setPage(1); }}>重置</Button>
              <Button type="primary" icon={<SearchOutlined />} onClick={() => { setSubmittedQuery(query); setPage(1); }}>查询</Button>
              <Button type="link" aria-expanded={filtersExpanded} onClick={() => setFiltersExpanded((value) => !value)}>{filtersExpanded ? <>收起 <UpOutlined /></> : <>展开 <DownOutlined /></>}</Button>
              <Tooltip title="刷新"><Button aria-label="刷新" icon={<ReloadOutlined />} onClick={() => void load()} /></Tooltip>
              <Tooltip title="新建同步任务"><Button aria-label="新建同步任务" type="primary" icon={<PlusOutlined />} onClick={() => openEdit()} /></Tooltip>
            </Space>
            {filtersExpanded && <>
              <div className="realtime-sync-filter-item"><label>责任人：</label><Select showSearch optionFilterProp="label" value={query.owner || 'all'} onChange={(owner) => setQuery({ ...query, owner: owner === 'all' ? '' : owner })} options={[{ label: '全部', value: 'all' }, ...ownerOptions]} placeholder="请输入成员名称进行搜索" /></div>
              <div className="realtime-sync-filter-item"><label>最近操作人：</label><Input allowClear placeholder="输入操作人" value={query.lastOperator} onChange={(event) => setQuery({ ...query, lastOperator: event.target.value })} onPressEnter={() => { setSubmittedQuery(query); setPage(1); }} /></div>
            </>}
          </div>
        </div>
        <div className="realtime-sync-table-section">
          <Table rowKey="id" rowClassName="clickable-task-row" onRow={(row) => ({ onClick: () => void openDetail(row), onKeyDown: (event) => { if (event.key === 'Enter') void openDetail(row); }, role: 'button', tabIndex: 0 })} onChange={handleTableChange} loading={loading} dataSource={rows} columns={columns} scroll={{ x: 1870 }} pagination={{ current: page, pageSize, total, showSizeChanger: true, pageSizeOptions: [20, 50, 100], showTotal: (value) => `共 ${value} 条`, onChange: (nextPage, nextSize) => { setPage(nextPage); setPageSize(nextSize); } }} />
        </div>
      </section>

      <SyncDebugDrawer task={debugTask} open={Boolean(debugTask)} params={debugParams} servers={debugServers}
        supportLoading={debugSupportLoading} onClose={() => setDebugTask(undefined)} />

      <Modal className="sync-mapping-modal" title={`同步表映射 - ${mappingTask?.name ?? ''}`} open={Boolean(mappingTask)} onCancel={() => setMappingTask(undefined)} footer={null} width={900} destroyOnHidden>
        <Table rowKey="id" size="small" pagination={false} scroll={{ y: 480 }} locale={{ emptyText: '暂无同步表映射' }} dataSource={mappingRows} columns={[
          { title: '序号', width: 72, render: (_: unknown, __: TaskMapping, index: number) => index + 1 },
          { title: '源表', width: 360, render: (_: unknown, row: TaskMapping) => <span className="sync-mapping-full-name">{[row.sourceDatabase, row.sourceTable].filter(Boolean).join('.')}</span> },
          { title: '目标 Paimon 表', render: (_: unknown, row: TaskMapping) => <span className="sync-mapping-full-name">{[row.targetDatabase, row.targetTable].filter(Boolean).join('.')}</span> },
        ]} />
      </Modal>

      <Modal title={`启动任务（任务：${actionTask?.row.name ?? ''}）`} open={Boolean(actionTask)} onCancel={() => { ++actionSelectionVersionRef.current; setActionTask(undefined); setResetFailedStateRecovery(false); }} onOk={() => void submitAction()} okText="确认启动" cancelText="取消" confirmLoading={actionLoading} okButtonProps={{ disabled: actionConfirmationDisabled }} destroyOnHidden>
        <Form form={actionForm} layout="vertical">
          <Form.Item name="startType" hidden><Input /></Form.Item>
          <Form.Item name="consumePointMode" hidden><Input /></Form.Item>
          <Form.Item name="recoveryFallbackMode" hidden><Input /></Form.Item>
          <Form.Item label="启动方式" required>
            <Select
              aria-label="启动方式"
              value={resetFailedStateRecovery && !actionRecoveryFallbackMode
                ? undefined : actionConsumePointMode === 'timestamp' ? 'timestamp' : actionStartType}
              placeholder="请选择启动方式"
              loading={actionLoading}
              disabled={actionLoading || (actionShowStateRecoveryReset && !resetFailedStateRecovery)}
              onChange={(value) => void selectStartMethod(value)}
              options={syncStartMethodOptions(actionTask?.startPolicy)}
            />
          </Form.Item>
          {actionConsumePointMode === 'timestamp' && (
            <>
              <Form.Item name="sourceStartupTime" label="消费起始时间" rules={[{ required: true, message: '请选择消费起始时间' }, { validator: (_, value) => !value || value.valueOf() <= Date.now() ? Promise.resolve() : Promise.reject(new Error('消费起始时间不能晚于当前时间')) }]}>
                <DatePicker showTime style={{ width: '100%' }} placeholder="请选择过去的时间" />
              </Form.Item>
              <Alert showIcon type="warning" message="本次从指定时间戳开始消费，目标 Paimon 表数据不会清空" description="选择较早时间可能重复消费，选择较晚时间可能跳过事件；时间早于 MySQL Binlog 保留范围时任务会失败。" />
            </>
          )}
          {actionShowStateRecoveryReset && (
            <Form.Item label="恢复失败处理">
              <Checkbox checked={resetFailedStateRecovery} onChange={(event) => toggleStateRecoveryFallback(event.target.checked)}>
                指定 Savepoint 无法恢复，使用其他方式启动
              </Checkbox>
            </Form.Item>
          )}
          <Form.Item noStyle shouldUpdate={(previous, current) => previous.startType !== current.startType}>{({ getFieldValue }) => getFieldValue('startType') !== 'direct' && (
            <>
              <Form.Item name="statePath" label="历史状态" rules={[{ required: true, message: '请选择历史状态' }]}><Select aria-label="历史状态" disabled={actionStartType === 'savepoint' && Boolean(actionRequiredStatePath) && !resetFailedStateRecovery} loading={actionLoading} placeholder="请选择历史状态" notFoundContent={actionStartType === 'checkpoint' ? '暂无可用 Checkpoint' : '暂无可用历史状态'} options={stateHistory.map((item) => ({ label: String(item.label ?? item.path), value: String(item.path) }))} /></Form.Item>
              {actionTask?.startPolicy?.requiredStatePath && !resetFailedStateRecovery && <Alert showIcon type="info" message="将从最近一次正式 Savepoint 恢复" description={actionTask.startPolicy.syncTableSetChanged ? '同步表集合已发生变化，本次只能使用该 Savepoint；恢复失败时可改用 Checkpoint 或按时间戳重置。' : '正式任务后续启动默认延续最近一次成功停止的状态。'} />}
            </>
          )}</Form.Item>
          {actionTask?.startPolicy?.syncTableSetChanged && actionConsumePointMode === 'default' && (
            <Alert showIcon type={actionTask.startPolicy.requiredStatePath ? 'info' : 'warning'} message="同步表集合已发生变化" description={resetFailedStateRecovery ? '请选择从 Checkpoint 恢复，或从指定时间戳开始消费。' : '默认从最近一次正式停止产生的 Savepoint 恢复；无法恢复时可选择从 Checkpoint 恢复或从指定时间戳开始消费。'} style={{ marginBottom: 16 }} />
          )}
        </Form>
      </Modal>

      <Modal title={`停止任务（任务：${taskStopTarget?.name ?? ''}）`} open={Boolean(taskStopTarget)} onCancel={() => { setTaskStopTarget(undefined); setAllowDirectStop(false); }} okText="确认停止" cancelText="取消" onOk={async () => {
        if (!taskStopTarget) return;
        if (!allowDirectStop && String(taskStopTarget.runtimeStatus ?? taskStopTarget.status).toLowerCase() !== 'running') {
          message.error('当前正式实例不是运行中状态，无法生成 Savepoint；请勾选允许直接停止');
          return;
        }
        try { await stopSyncTask(taskStopTarget.id, { stopType: allowDirectStop ? 'direct' : 'savepoint' }); message.success('停止请求已提交'); setTaskStopTarget(undefined); await load(); }
        catch (error) { message.error((error as Error).message); }
      }}>
        {taskStopTarget && String(taskStopTarget.runtimeStatus ?? taskStopTarget.status).toLowerCase() !== 'running' && <Alert showIcon type="error" message="当前正式实例不是运行中状态，无法生成 Savepoint" description="如需停止，请勾选允许直接停止。" style={{ marginBottom: 16 }} />}
        <Form layout="vertical">
          <Alert showIcon type="info" message="默认使用 Savepoint 停止" description="会在停止前生成新的 Savepoint，便于下次完整恢复。" style={{ marginBottom: 16 }} />
          <Checkbox checked={allowDirectStop} onChange={(event) => setAllowDirectStop(event.target.checked)}>
            Savepoint 停止失败，允许直接停止（不会生成 Savepoint，已有 Checkpoint 会保留）
          </Checkbox>
        </Form>
      </Modal>

      <SyncTaskDetailDrawer task={detail} loading={detailLoading} initialTab={requestedTab} onClose={() => setDetail(undefined)} />
    </div>
  );
}
