import { useCallback, useEffect, useMemo, useState } from 'react';
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
  deleteSyncTask,
  getStateHistory,
  getSyncTask,
  listMappings,
  listServers,
  listSyncTasks,
  startSyncTask,
  stopSyncTask,
} from '../api';
import type {
  RealtimeServer,
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

const emptyQuery = { keyword: '', status: 'all', sourceServerId: '', owner: '', lastOperator: '', tableScope: 'all', tableKeyword: '' };
const positiveNumber = (value: string | null, fallback: number) => {
  const number = Number(value);
  return Number.isInteger(number) && number > 0 ? number : fallback;
};

export default function RealtimeSyncTasksPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const [query, setQuery] = useState(() => ({
    ...emptyQuery,
    keyword: searchParams.get('keyword') ?? '', status: searchParams.get('status') ?? 'all',
    sourceServerId: searchParams.get('sourceServerId') ?? '', owner: searchParams.get('owner') ?? '',
    lastOperator: searchParams.get('lastOperator') ?? '', tableScope: searchParams.get('tableScope') ?? 'all',
    tableKeyword: searchParams.get('tableKeyword') ?? '',
  }));
  const [submittedQuery, setSubmittedQuery] = useState(query);
  const [filtersExpanded, setFiltersExpanded] = useState(() => Boolean(query.owner || query.lastOperator || query.tableKeyword || query.tableScope !== 'all'));
  const [page, setPage] = useState(() => positiveNumber(searchParams.get('page'), 1));
  const [pageSize, setPageSize] = useState(() => positiveNumber(searchParams.get('pageSize'), 20));
  const [sortField, setSortField] = useState(() => searchParams.get('sort') || 'lastOperationTime');
  const [sortOrder, setSortOrder] = useState<'asc' | 'desc'>(() => searchParams.get('order') === 'asc' ? 'asc' : 'desc');
  const [rows, setRows] = useState<SyncTaskListItem[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [servers, setServers] = useState<RealtimeServer[]>([]);
  const [detail, setDetail] = useState<SyncTask>();
  const [detailLoading, setDetailLoading] = useState(false);
  const [mappingTask, setMappingTask] = useState<SyncTaskListItem>();
  const [mappingRows, setMappingRows] = useState<TaskMapping[]>([]);
  const [actionTask, setActionTask] = useState<{ row: SyncTaskListItem; task?: SyncTask }>();
  const [actionLoading, setActionLoading] = useState(false);
  const [debugTask, setDebugTask] = useState<SyncTask>();
  const [taskStopTarget, setTaskStopTarget] = useState<SyncTaskListItem>();
  const [taskStopType, setTaskStopType] = useState('direct');
  const [actionForm] = Form.useForm();
  const [stateHistory, setStateHistory] = useState<Record<string, unknown>[]>([]);

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

  useEffect(() => { void listServers().then(setServers).catch(() => undefined); }, []);
  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    const next = new URLSearchParams();
    next.set('page', String(page)); next.set('pageSize', String(pageSize)); next.set('sort', sortField); next.set('order', sortOrder);
    Object.entries(submittedQuery).forEach(([key, value]) => { if (value && value !== 'all') next.set(key, value); });
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
      state: { returnTo },
    });
  };

  const openDetail = async (row: SyncTaskListItem) => {
    setDetailLoading(true);
    try {
      setDetail(await getSyncTask(row.id));
    } catch (error) { message.error((error as Error).message); }
    finally { setDetailLoading(false); }
  };

  const openMappings = async (row: SyncTaskListItem) => {
    setMappingTask(row);
    try { setMappingRows(await listMappings(row.id)); }
    catch (error) { message.error((error as Error).message); }
  };

  const openAction = async (row: SyncTaskListItem) => {
    setActionTask({ row }); setStateHistory([]);
    actionForm.resetFields();
    actionForm.setFieldsValue({ startType: 'direct', statePath: undefined });
    try {
      setActionLoading(true);
      setActionTask({ row, task: await getSyncTask(row.id) });
    } catch (error) {
      message.error((error as Error).message);
    } finally {
      setActionLoading(false);
    }
  };

  const openDebug = async (row: SyncTaskListItem) => {
    try { setDebugTask(await getSyncTask(row.id)); }
    catch (error) { message.error((error as Error).message); }
  };

  const submitAction = async () => {
    if (!actionTask) return;
    try {
      const values = await actionForm.validateFields();
      setActionLoading(true);
      await startSyncTask(actionTask.row.id, values, false);
      message.success('同步任务已提交');
      setActionTask(undefined); await load();
    } catch (error) { message.error((error as Error).message); }
    finally { setActionLoading(false); }
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

  const columns = useMemo(() => [
    { title: '任务 ID', dataIndex: 'id', width: 100, fixed: 'left' as const, sorter: true },
    { title: '任务名称', dataIndex: 'name', width: 220, fixed: 'left' as const, ellipsis: true, sorter: true, render: (value: string) => <Typography.Link>{value}</Typography.Link> },
    { title: '任务描述', dataIndex: 'description', width: 260, ellipsis: { showTitle: false }, render: (value: string) => <Tooltip title={value}><span>{value || '-'}</span></Tooltip> },
    { title: '任务状态', dataIndex: 'status', width: 110, sorter: true, render: (value: string, row: SyncTaskListItem) => <Tooltip title={row.runtimeFailureMessage}><Tag color={statusColor[value]}>{statusLabel[value] ?? value}</Tag></Tooltip> },
    { title: 'Server', width: 210, ellipsis: true, render: (_: unknown, row: SyncTaskListItem) => [row.sourceServerName, row.sourceServerDatabase].filter(Boolean).join('/') || '-' },
    { title: '同步表', width: 170, render: (_: unknown, row: SyncTaskListItem) => <Button className="realtime-mapping-link" type="link" size="small" icon={<UnorderedListOutlined />} onClick={(event) => { event.stopPropagation(); void openMappings(row); }}>{row.mappingCount === undefined ? '查看同步表' : `查看全部 ${row.mappingCount} 组`}</Button> },
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
              ? <Tooltip title={row.managed === false ? '历史导入实例只读，不能在本平台停止' : undefined}><Typography.Link disabled={row.managed === false} onClick={() => { if (row.managed === false) return; setTaskStopTarget(row); setTaskStopType('direct'); }}>停止</Typography.Link></Tooltip>
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
        <div className="realtime-page-header">
          <Typography.Title level={4}>实时同步任务</Typography.Title>
          <Space><Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button><Button type="primary" icon={<PlusOutlined />} onClick={() => openEdit()}>新建同步任务</Button></Space>
        </div>
        <div className="realtime-sync-filter-section">
          <div className="realtime-sync-query-filter">
            <div className="realtime-sync-filter-item"><label>任务关键字：</label><Input allowClear placeholder="任务ID / 名称 / 描述" value={query.keyword} onChange={(event) => setQuery({ ...query, keyword: event.target.value })} onPressEnter={() => { setSubmittedQuery(query); setPage(1); }} /></div>
            <div className="realtime-sync-filter-item"><label>任务状态：</label><Select value={query.status} onChange={(status) => setQuery({ ...query, status })} options={[{ label: '全部', value: 'all' }, ...Object.entries(statusLabel).filter(([key]) => ['not_running', 'submitting', 'running', 'stopping', 'restarting', 'failed'].includes(key)).map(([value, label]) => ({ value, label }))]} /></div>
            <div className="realtime-sync-filter-item"><label>Server：</label><Select showSearch allowClear optionFilterProp="label" placeholder="Server名称 / 数据库" value={query.sourceServerId || undefined} onChange={(value) => setQuery({ ...query, sourceServerId: value ? String(value) : '' })} options={servers.map((item) => ({ label: `${item.name}/${item.databaseName || '-'}`, value: item.id }))} /></div>
            <Space className="realtime-sync-filter-actions">
              <Button onClick={() => { setQuery(emptyQuery); setSubmittedQuery(emptyQuery); setSortField('lastOperationTime'); setSortOrder('desc'); setPage(1); }}>重置</Button>
              <Button type="primary" icon={<SearchOutlined />} onClick={() => { setSubmittedQuery(query); setPage(1); }}>查询</Button>
              <Button type="link" aria-expanded={filtersExpanded} onClick={() => setFiltersExpanded((value) => !value)}>{filtersExpanded ? <>收起 <UpOutlined /></> : <>展开 <DownOutlined /></>}</Button>
            </Space>
            {filtersExpanded && <>
              <div className="realtime-sync-filter-item"><label>责任人：</label><Input allowClear placeholder="输入责任人" value={query.owner} onChange={(event) => setQuery({ ...query, owner: event.target.value })} onPressEnter={() => { setSubmittedQuery(query); setPage(1); }} /></div>
              <div className="realtime-sync-filter-item"><label>最近操作人：</label><Input allowClear placeholder="输入操作人" value={query.lastOperator} onChange={(event) => setQuery({ ...query, lastOperator: event.target.value })} onPressEnter={() => { setSubmittedQuery(query); setPage(1); }} /></div>
              <div className="realtime-sync-filter-item realtime-sync-table-filter"><label>同步表：</label><Space.Compact block><Select value={query.tableScope} onChange={(tableScope) => setQuery({ ...query, tableScope })} options={[{ label: '全部表', value: 'all' }, { label: '源表', value: 'source' }, { label: '目标表', value: 'target' }]} /><Input allowClear placeholder="表名或 database.table" value={query.tableKeyword} onChange={(event) => setQuery({ ...query, tableKeyword: event.target.value })} onPressEnter={() => { setSubmittedQuery(query); setPage(1); }} /></Space.Compact></div>
            </>}
          </div>
        </div>
        <div className="realtime-sync-table-section">
          <Table rowKey="id" rowClassName="clickable-task-row" onRow={(row) => ({ onClick: () => void openDetail(row), onKeyDown: (event) => { if (event.key === 'Enter') void openDetail(row); }, role: 'button', tabIndex: 0 })} onChange={handleTableChange} loading={loading} dataSource={rows} columns={columns} scroll={{ x: 2160 }} pagination={{ current: page, pageSize, total, showSizeChanger: true, pageSizeOptions: [20, 50, 100], showTotal: (value) => `共 ${value} 条`, onChange: (nextPage, nextSize) => { setPage(nextPage); setPageSize(nextSize); } }} />
        </div>
      </section>

      <SyncDebugDrawer task={debugTask} open={Boolean(debugTask)} onClose={() => setDebugTask(undefined)} />

      <Modal title={`同步表映射 - ${mappingTask?.name ?? ''}`} open={Boolean(mappingTask)} onCancel={() => setMappingTask(undefined)} footer={null} width={980}>
        <Table rowKey="id" pagination={false} dataSource={mappingRows} columns={[
          { title: '序号', dataIndex: 'sortOrder', width: 70 },
          { title: '源表', render: (_: unknown, row: TaskMapping) => `${row.sourceDatabase}.${row.sourceTable}` },
          { title: '目标 Paimon 表', render: (_: unknown, row: TaskMapping) => `${row.targetDatabase}.${row.targetTable}` },
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
          <Form.Item name="startType" label="启动类型" rules={[{ required: true }]}><Select options={[{ label: '直接启动', value: 'direct' }, { label: 'checkpoint', value: 'checkpoint' }, { label: 'savepoint', value: 'savepoint' }]} /></Form.Item>
          <Form.Item noStyle shouldUpdate={(previous, current) => previous.startType !== current.startType}>{({ getFieldValue }) => getFieldValue('startType') !== 'direct' && (
            <>
              <Form.Item name="statePath" label="历史状态" rules={[{ required: true, message: '请选择历史状态' }]}><Select loading={actionLoading} placeholder="请选择历史状态" options={stateHistory.map((item) => ({ label: String(item.label ?? item.path), value: String(item.path) }))} /></Form.Item>
              {actionTask?.task?.editPolicy?.topologyChanged && <Alert showIcon type="warning" message="源表集合已发生变化，历史状态可能与当前拓扑不兼容" description="仍可继续提交；若 Flink 恢复失败，请改用直接启动。系统不会自动回退。" />}
            </>
          )}</Form.Item>
        </Form>
      </Modal>

      <Modal title={`停止任务（任务：${taskStopTarget?.name ?? ''}）`} open={Boolean(taskStopTarget)} onCancel={() => setTaskStopTarget(undefined)} okText="确认停止" cancelText="取消" onOk={async () => {
        if (!taskStopTarget) return;
        try { await stopSyncTask(taskStopTarget.id, { stopType: taskStopType }); message.success('停止请求已提交'); setTaskStopTarget(undefined); await load(); }
        catch (error) { message.error((error as Error).message); }
      }}>
        <Form layout="horizontal" labelCol={{ span: 6 }} wrapperCol={{ span: 16 }}><Form.Item label="停止类型" required><Select value={taskStopType} onChange={setTaskStopType} options={[
          { label: '直接停止', value: 'direct' },
          ...((taskStopTarget?.runtimeStatus || taskStopTarget?.status) === 'running' ? [{ label: 'savepoint停止', value: 'savepoint' }] : []),
        ]} /></Form.Item></Form>
      </Modal>

      <SyncTaskDetailDrawer task={detail} loading={detailLoading} onClose={() => setDetail(undefined)} />
    </div>
  );
}
