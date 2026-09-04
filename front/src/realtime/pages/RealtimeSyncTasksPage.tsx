import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  Button,
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
  listSyncTasks,
  startSyncTask,
  stopSyncTask,
} from '../api';
import type {
  SyncTask,
  SyncTaskListItem,
  TaskMapping,
} from '../types';
import SyncDebugDrawer from '../components/SyncDebugDrawer';
import SyncTaskDetailDrawer from '../components/SyncTaskDetailDrawer';

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
  const [actionTask, setActionTask] = useState<{ row: SyncTaskListItem; task?: SyncTask }>();
  const [actionLoading, setActionLoading] = useState(false);
  const [debugTask, setDebugTask] = useState<SyncTask>();
  const [taskStopTarget, setTaskStopTarget] = useState<SyncTaskListItem>();
  const [taskStopType] = useState('savepoint');
  const [actionForm] = Form.useForm();
  const [stateHistory, setStateHistory] = useState<Record<string, unknown>[]>([]);
  const submitActionRef = useRef(false);
  const detailRequestRef = useRef(0);
  const actionRequestRef = useRef(0);
  const debugRequestRef = useRef(0);

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
    const next = new URLSearchParams();
    next.set('page', String(page)); next.set('pageSize', String(pageSize)); next.set('sort', sortField); next.set('order', sortOrder);
    const urlKeys: Record<keyof typeof submittedQuery, string> = {
      keyword: 'q', status: 'status', paimonTableKeyword: 'paimonTable', owner: 'owner', lastOperator: 'operator',
    };
    Object.entries(submittedQuery).forEach(([key, value]) => {
      if (value && value !== 'all') next.set(urlKeys[key as keyof typeof submittedQuery], value);
    });
    setSearchParams(next, { replace: true });
  }, [page, pageSize, setSearchParams, sortField, sortOrder, submittedQuery]);
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
        message.warning(qualification.reason || '当前任务不满足启动条件');
        return;
      }
      const task = await getSyncTask(row.id);
      if (sequence !== actionRequestRef.current) return;
      const requiredType = task.editPolicy?.requiredStartType ?? 'direct';
      const requiredPath = task.editPolicy?.requiredStatePath;
      actionForm.setFieldsValue({ startType: requiredType, statePath: requiredPath });
      if (requiredPath) setStateHistory([{ label: requiredPath, path: requiredPath }]);
      setActionTask({ row, task });
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
      await startSyncTask(actionTask.row.id, values, false);
      message.success('同步任务已提交');
      setActionTask(undefined); await load();
    } catch (error) { message.error((error as Error).message); }
    finally { submitActionRef.current = false; setActionLoading(false); }
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
              ? <Tooltip title={row.managed === false ? '历史导入实例只读，不能在本平台停止' : undefined}><Typography.Link disabled={row.managed === false} onClick={() => { if (row.managed === false) return; setTaskStopTarget(row); }}>停止</Typography.Link></Tooltip>
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
    <div className="realtime-page realtime-sync-tasks-page">
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

      <SyncDebugDrawer task={debugTask} open={Boolean(debugTask)} onClose={() => setDebugTask(undefined)} />

      <Modal className="sync-mapping-modal" title={`同步表映射 - ${mappingTask?.name ?? ''}`} open={Boolean(mappingTask)} onCancel={() => setMappingTask(undefined)} footer={null} width={900} destroyOnHidden>
        <Table rowKey="id" size="small" pagination={false} scroll={{ y: 480 }} locale={{ emptyText: '暂无同步表映射' }} dataSource={mappingRows} columns={[
          { title: '序号', width: 72, render: (_: unknown, __: TaskMapping, index: number) => index + 1 },
          { title: 'Server', width: 180, render: (_: unknown, row: TaskMapping) => [row.serverName, row.sourceDatabase].filter(Boolean).join('/') || '-' },
          { title: '源表', width: 360, render: (_: unknown, row: TaskMapping) => <span className="sync-mapping-full-name">{[row.sourceDatabase, row.sourceTable].filter(Boolean).join('.')}</span> },
          { title: '目标 Paimon 表', render: (_: unknown, row: TaskMapping) => <span className="sync-mapping-full-name">{[row.targetDatabase, row.targetTable].filter(Boolean).join('.')}</span> },
        ]} />
      </Modal>

      <Modal title={`启动任务（任务：${actionTask?.row.name ?? ''}）`} open={Boolean(actionTask)} onCancel={() => setActionTask(undefined)} onOk={() => void submitAction()} okText="确认启动" cancelText="取消" confirmLoading={actionLoading} destroyOnHidden>
        <Form form={actionForm} layout="vertical" onValuesChange={async (changed) => {
          if (!actionTask || !changed.startType || changed.startType === 'direct') { setStateHistory([]); return; }
          actionForm.setFieldValue('statePath', undefined);
          try {
            setActionLoading(true);
            const rows = await getStateHistory(actionTask.row.id, changed.startType);
            setStateHistory(rows);
            actionForm.setFieldValue('statePath', rows[0]?.path);
          } catch { setStateHistory([]); }
          finally { setActionLoading(false); }
        }}>
          <Form.Item name="startType" label="启动类型" rules={[{ required: true }]}><Select disabled={Boolean(actionTask?.task?.editPolicy?.requiredStartType)} options={[{ label: '直接启动', value: 'direct' }, { label: 'checkpoint', value: 'checkpoint' }, { label: 'savepoint', value: 'savepoint' }].filter((item) => !actionTask?.task?.editPolicy?.requiredStartType || item.value === actionTask.task.editPolicy.requiredStartType)} /></Form.Item>
          <Form.Item noStyle shouldUpdate={(previous, current) => previous.startType !== current.startType}>{({ getFieldValue }) => getFieldValue('startType') !== 'direct' && (
            <>
              <Form.Item name="statePath" label="历史状态" rules={[{ required: true, message: '请选择历史状态' }]}><Select disabled={Boolean(actionTask?.task?.editPolicy?.requiredStatePath)} loading={actionLoading} placeholder="请选择历史状态" options={stateHistory.map((item) => ({ label: String(item.label ?? item.path), value: String(item.path) }))} /></Form.Item>
              {actionTask?.task?.editPolicy?.syncTableSetChanged && <Alert showIcon type="info" message="同步表集合已发生变化" description="本次只能从最近一次正式停止产生的 Savepoint 恢复；恢复失败时保留新配置，不会自动回滚。" />}
            </>
          )}</Form.Item>
        </Form>
      </Modal>

      <Modal title={`停止任务（任务：${taskStopTarget?.name ?? ''}）`} open={Boolean(taskStopTarget)} onCancel={() => setTaskStopTarget(undefined)} okText="确认停止" cancelText="取消" onOk={async () => {
        if (!taskStopTarget) return;
        try { await stopSyncTask(taskStopTarget.id, { stopType: taskStopType }); message.success('停止请求已提交'); setTaskStopTarget(undefined); await load(); }
        catch (error) { message.error((error as Error).message); }
      }}>
        <Form layout="horizontal" labelCol={{ span: 6 }} wrapperCol={{ span: 16 }}><Form.Item label="停止类型" required><Select value={taskStopType} disabled options={[{ label: 'Savepoint 停止', value: 'savepoint' }]} /></Form.Item></Form>
      </Modal>

      <SyncTaskDetailDrawer task={detail} loading={detailLoading} onClose={() => setDetail(undefined)} />
    </div>
  );
}
