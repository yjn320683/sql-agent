import { EyeOutlined, LinkOutlined, PlayCircleOutlined, QuestionCircleOutlined } from '@ant-design/icons';
import {
  Alert,
  Button,
  Descriptions,
  DatePicker,
  Drawer,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Table,
  Tabs,
  Tag,
  Tooltip,
  Typography,
  Progress,
  message,
} from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  getInstanceInfo,
  getStateHistory,
  listInstances,
  previewSavedSyncTask,
  startSyncTask,
  stopInstance,
} from '../api';
import type { RealtimeServer, SyncTask, TaskInstance, TaskParam, TaskRuntimeCheckpoints, TaskRuntimeSnapshot } from '../types';
import InstanceInspectorModal, { type InstanceInspectorKind } from './InstanceInspectorModal';
import InstanceLogPanel from './InstanceLogPanel';
import InstanceListToolbar, { type InstanceSearchField, type InstanceSortOrder } from './InstanceListToolbar';
import SyncMoreConfigRows from './SyncMoreConfigRows';
import SyncTaskConfigDetail from './SyncTaskConfigDetail';
import SyncSectionNav from './SyncSectionNav';
import SyncTopologyConfigRows, { mergeSyncTopologyOverrides } from './SyncTopologyConfigRows';

interface Props {
  task?: SyncTask;
  open: boolean;
  params: TaskParam[];
  servers: RealtimeServer[];
  supportLoading?: boolean;
  onClose: () => void;
}

const ACTIVE = ['submitting', 'running', 'debug_success_running', 'stopping', 'restarting'];
const SYNC_TOPOLOGY_KEYS = new Set(['bucket', 'sink.parallelism']);
const DEBUG_TARGET_DATABASE = 'paimon_debug';
const statusLabel: Record<string, string> = {
  submitting: '提交中', running: '运行中', debug_success_running: '运行中(调试成功)', stopping: '停止中', restarting: '重启中',
  canceled: '已取消', killed_success: '已停止(调试成功)', finished: '已完成', failed: '失败', not_running: '未运行',
};

const paramOptions = (param: TaskParam) => {
  try {
    const value = JSON.parse(param.paramValue ?? '[]') as Array<{ label?: string; value: string | number | boolean; default?: boolean }>;
    return Array.isArray(value) ? value.map((item) => ({ label: item.label ?? String(item.value), value: String(item.value), default: item.default })) : [];
  } catch { return []; }
};

const paramDefault = (param: TaskParam) => {
  const options = paramOptions(param);
  const selected = options.find((item) => item.default) ?? (param.required ? options[0] : undefined);
  if (selected) return selected.value;
  if (!param.paramValue || param.paramValue.trim().startsWith('[')) return undefined;
  return param.paramValue;
};

const mergeParamDefaults = (params: TaskParam[], type: TaskParam['paramType'], values?: Record<string, string>) => {
  const result: Record<string, string> = {};
  params.filter((item) => item.paramType === type && Boolean(item.required)).forEach((item) => {
    const value = paramDefault(item);
    if (value !== undefined) result[item.paramKey] = String(value);
  });
  Object.entries(values ?? {}).forEach(([key, value]) => { if (value !== undefined && value !== null && String(value) !== '') result[key] = String(value); });
  return result;
};

const debugTarget = (task: SyncTask, servers: RealtimeServer[]) => {
  const cdc = task.taskConfig.cdcConfig;
  const server = servers.find((item) => item.id === task.sourceServerId);
  const prefix = cdc.domainPrefix && (cdc.databaseName || server?.databaseName)
    ? [DEBUG_TARGET_DATABASE, server?.databasePrefix, cdc.databaseName || server?.databaseName, cdc.domainPrefix].filter(Boolean).join('_') + '_'
    : '';
  const targets = cdc.selectedTables.map((table) => {
    return `${prefix}${table}${cdc.tableSuffix ?? ''}`;
  });
  return { server, prefix, targets };
};

export default function SyncDebugDrawer({ task, open, params, servers, supportLoading = false, onClose }: Props) {
  const [instances, setInstances] = useState<TaskInstance[]>([]);
  const [loading, setLoading] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState('all');
  const [searchField, setSearchField] = useState<InstanceSearchField>('all');
  const [sortOrder, setSortOrder] = useState<InstanceSortOrder>('startedAtDesc');
  const [configOpen, setConfigOpen] = useState(false);
  const [form] = Form.useForm();
  const consumePointMode = Form.useWatch('consumePointMode', form);
  const startType = Form.useWatch('startType', form);
  const [stateHistory, setStateHistory] = useState<Record<string, unknown>[]>([]);
  const [stateHistoryLoading, setStateHistoryLoading] = useState(false);
  const [command, setCommand] = useState('');
  const [commandLoading, setCommandLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [inspector, setInspector] = useState<{ title: string; kind: InstanceInspectorKind; value: unknown }>();
  const [inspectorLoading, setInspectorLoading] = useState(false);
  const [mappingInstance, setMappingInstance] = useState<TaskInstance>();
  const [logInstance, setLogInstance] = useState<TaskInstance>();
  const [selectedInstanceId, setSelectedInstanceId] = useState<number>();
  const [stoppingInstanceId, setStoppingInstanceId] = useState<number>();
  const [qualificationRuntime, setQualificationRuntime] = useState<TaskRuntimeSnapshot>();
  const [qualificationCheckpoints, setQualificationCheckpoints] = useState<TaskRuntimeCheckpoints>();
  const [qualificationError, setQualificationError] = useState('');
  const initializedConfigKeyRef = useRef('');
  const submittingRef = useRef(false);
  const acceptedInstancesRef = useRef(new Map<number, TaskInstance>());
  const instanceRequestSequenceRef = useRef(0);
  const inspectorRequestSequenceRef = useRef(0);
  const startSelectionVersionRef = useRef(0);
  const qualifyingInstance = instances.find((item) => item.status === 'running' || item.status === 'debug_success_running');

  const reload = useCallback(async (silent = false) => {
    if (!task) return;
    const sequence = ++instanceRequestSequenceRef.current;
    if (!silent) setLoading(true);
    try {
      const rows = await listInstances(task.id, 'DEBUG');
      if (sequence === instanceRequestSequenceRef.current) {
        const serverIds = new Set(rows.map((item) => item.id));
        serverIds.forEach((id) => acceptedInstancesRef.current.delete(id));
        const accepted = [...acceptedInstancesRef.current.values()].filter((item) => !serverIds.has(item.id));
        setInstances([...accepted, ...rows]);
      }
    } catch (error) {
      if (sequence === instanceRequestSequenceRef.current) message.error((error as Error).message);
    } finally {
      if (!silent && sequence === instanceRequestSequenceRef.current) setLoading(false);
    }
  }, [task]);

  useEffect(() => {
    if (!open || !task) return;
    setKeyword(''); setStatus('all'); setSearchField('all'); setSortOrder('startedAtDesc'); setCommand('');
    setLogInstance(undefined); setMappingInstance(undefined); setSelectedInstanceId(undefined);
    acceptedInstancesRef.current.clear();
    void reload();
  }, [open, reload, task]);

  useEffect(() => {
    if (!open || !instances.some((item) => ACTIVE.includes(item.status))) return undefined;
    const timer = window.setInterval(() => void reload(true), 3000);
    return () => window.clearInterval(timer);
  }, [instances, open, reload]);

  useEffect(() => {
    if (!logInstance) return;
    const latest = instances.find((item) => item.id === logInstance.id);
    if (latest && latest !== logInstance) setLogInstance(latest);
  }, [instances, logInstance]);

  useEffect(() => {
    if (!configOpen || !task || supportLoading) return;
    const key = String(task.id);
    if (initializedConfigKeyRef.current === key) return;
    initializedConfigKeyRef.current = key;
    form.resetFields();
    form.setFieldsValue({
      startType: 'direct', consumePointMode: 'default', sourceStartupTime: undefined,
      parallelism: task.taskConfig.parallelism ?? 3,
      checkpointInterval: task.taskConfig.checkpointInterval ?? 60,
      taskManagerMemory: task.taskConfig.taskManagerMemory || '3GB',
      jobManagerMemory: task.taskConfig.jobManagerMemory || '1GB',
      flinkConfOverrides: mergeParamDefaults(params, 'flink_conf', task.taskConfig.flinkConfOverrides),
      mysqlConfOverrides: mergeParamDefaults(params, 'mysql_conf', task.taskConfig.cdcConfig.mysqlConfOverrides),
      tableConfOverrides: mergeSyncTopologyOverrides(
        mergeParamDefaults(params, 'table_conf', task.taskConfig.cdcConfig.tableConfOverrides),
        task.taskConfig.cdcConfig.tableConfOverrides,
        task.taskConfig.parallelism,
      ),
    });
  }, [configOpen, form, params, supportLoading, task]);

  useEffect(() => {
    if (!open || !task || !qualifyingInstance?.jobId) {
      setQualificationRuntime(undefined); setQualificationCheckpoints(undefined); setQualificationError('');
      return undefined;
    }
    let disposed = false;
    const loadQualification = async () => {
      try {
        const [runtime, checkpoints] = await Promise.all([
          getInstanceInfo(task.id, qualifyingInstance.id, 'runtime') as Promise<TaskRuntimeSnapshot>,
          getInstanceInfo(task.id, qualifyingInstance.id, 'checkpoints') as Promise<TaskRuntimeCheckpoints>,
        ]);
        if (!disposed) { setQualificationRuntime(runtime); setQualificationCheckpoints(checkpoints); setQualificationError(''); }
      } catch (error) {
        if (!disposed) setQualificationError(error instanceof Error ? error.message : '调试资格读取失败');
      }
    };
    void loadQualification();
    const timer = window.setInterval(() => void loadQualification(), 5000);
    return () => { disposed = true; window.clearInterval(timer); };
  }, [open, qualifyingInstance?.id, qualifyingInstance?.jobId, task]);

  const filtered = useMemo(() => instances.filter((item) => {
    if (status !== 'all' && item.status !== status) return false;
    if (!keyword.trim()) return true;
    const text = searchField === 'all'
      ? [item.id, item.jobId, item.yarnApplicationId, item.failureMessage].join(' ').toLowerCase()
      : String(item[searchField] ?? '').toLowerCase();
    return text.includes(keyword.trim().toLowerCase());
  }).sort((left, right) => {
    if (sortOrder === 'idAsc' || sortOrder === 'idDesc') return (left.id - right.id) * (sortOrder === 'idAsc' ? 1 : -1);
    const comparison = String(left.startedAt ?? left.createTime).localeCompare(String(right.startedAt ?? right.createTime));
    return comparison * (sortOrder === 'startedAtAsc' ? 1 : -1);
  }), [instances, keyword, searchField, sortOrder, status]);

  const dynamicParam = (param: TaskParam) => {
    const options = paramOptions(param);
    const name = [param.paramType === 'mysql_conf' ? 'mysqlConfOverrides' : param.paramType === 'table_conf' ? 'tableConfOverrides' : 'flinkConfOverrides', param.paramKey];
    const rules = param.required ? [{ required: true, message: `请输入${param.keyDesc || param.paramKey}` }] : undefined;
    return <Form.Item key={`${param.paramType}-${param.paramKey}`} name={name} label={<Space size={4}><span>{param.keyDesc || param.paramKey}</span><Tooltip title={param.paramKey}><QuestionCircleOutlined /></Tooltip></Space>} rules={rules} className="realtime-dynamic-param">
      {param.inputType === 'select' && options.length
        ? <Select allowClear={!param.required} options={options} />
        : param.inputType === 'switch'
          ? <Select allowClear={!param.required} options={[{ label: '是', value: 'true' }, { label: '否', value: 'false' }]} />
          : param.inputType === 'input_number'
            ? <InputNumber min={param.minValue} max={param.maxValue} step={param.stepValue} precision={param.precisionValue} style={{ width: '100%' }} />
            : <Input placeholder={param.paramKey} />}
    </Form.Item>;
  };

  const normalizeOverrides = (value?: Record<string, unknown>) => Object.fromEntries(Object.entries(value ?? {})
    .filter(([, item]) => item !== undefined && item !== null && String(item) !== '')
    .map(([key, item]) => [key, String(item)]));

  const buildAction = (values: Record<string, unknown>) => ({
    ...values,
    statePath: values.startType === 'direct' ? undefined : values.statePath,
    sourceStartupTimestampMillis: values.consumePointMode === 'timestamp'
      && values.sourceStartupTime && typeof (values.sourceStartupTime as { valueOf?: unknown }).valueOf === 'function'
      ? (values.sourceStartupTime as { valueOf: () => number }).valueOf() : undefined,
    mysqlConfOverrides: normalizeOverrides(values.mysqlConfOverrides as Record<string, unknown>),
    tableConfOverrides: mergeSyncTopologyOverrides(
      normalizeOverrides(values.tableConfOverrides as Record<string, unknown>),
      values.tableConfOverrides as Record<string, unknown>,
      values.parallelism,
    ),
    flinkConfOverrides: normalizeOverrides(values.flinkConfOverrides as Record<string, unknown>),
  }) as unknown as Parameters<typeof startSyncTask>[1];

  const openConfig = () => {
    initializedConfigKeyRef.current = '';
    setCommand(''); setStateHistory([]); setConfigOpen(true);
  };

  const selectStartMethod = async (method: 'direct' | 'checkpoint' | 'savepoint' | 'timestamp') => {
    if (!task) return;
    const selectionVersion = ++startSelectionVersionRef.current;
    const nextStartType = method === 'timestamp' ? 'direct' : method;
    setStateHistory([]);
    form.setFieldsValue({
      startType: nextStartType,
      consumePointMode: method === 'timestamp' ? 'timestamp' : 'default',
      statePath: undefined,
      sourceStartupTime: undefined,
    });
    if (nextStartType === 'direct') return;
    try {
      setStateHistoryLoading(true);
      const rows = await getStateHistory(task.id, nextStartType);
      if (selectionVersion !== startSelectionVersionRef.current) return;
      setStateHistory(rows);
      form.setFieldValue('statePath', rows[0]?.path);
    } catch (error) {
      if (selectionVersion === startSelectionVersionRef.current) message.error((error as Error).message);
    } finally {
      if (selectionVersion === startSelectionVersionRef.current) setStateHistoryLoading(false);
    }
  };

  const inspect = async (instance: TaskInstance, kind: 'config' | 'startup-log' | 'runtime-log') => {
    if (!task) return;
    const sequence = ++inspectorRequestSequenceRef.current;
    setInspector({ title: kind === 'config' ? `调试配置 - 调试实例 ${instance.id}` : `调试实例 ${instance.id} - ${kind === 'startup-log' ? '启动日志' : '运行日志'}`, kind, value: undefined });
    setInspectorLoading(true);
    try {
      const value = await getInstanceInfo(task.id, instance.id, kind);
      if (sequence === inspectorRequestSequenceRef.current) setInspector((current) => current ? { ...current, value } : current);
    } catch (error) { if (sequence === inspectorRequestSequenceRef.current) message.error((error as Error).message); }
    finally { if (sequence === inspectorRequestSequenceRef.current) setInspectorLoading(false); }
  };

  const submit = async () => {
    if (!task || submittingRef.current) return;
    submittingRef.current = true;
    try {
      const values = buildAction(await form.validateFields());
      setSubmitting(true);
      const instance = await startSyncTask(task.id, values, true);
      acceptedInstancesRef.current.set(instance.id, instance);
      setInstances((current) => [instance, ...current.filter((item) => item.id !== instance.id)]);
      setSelectedInstanceId(instance.id);
      setKeyword(''); setSearchField('all'); setStatus('all'); setSortOrder('startedAtDesc');
      message.success('调试请求已提交');
      setConfigOpen(false);
      await reload();
    } catch (error) {
      if (error instanceof Error) message.error(error.message);
    } finally { submittingRef.current = false; setSubmitting(false); }
  };

  const records = logInstance ? (
    <InstanceLogPanel taskId={task!.id} instance={logInstance} backLabel="返回调试记录" onBack={() => setLogInstance(undefined)} />
  ) : (
    <div className="debug-records-panel realtime-debug-records">
      <Alert showIcon type={qualifyingInstance?.status === 'debug_success_running' ? 'success' : 'info'}
        message={qualifyingInstance?.status === 'debug_success_running' ? '已具备正式启动资格' : '调试成功资格进度'}
        description={qualifyingInstance ? <Space direction="vertical" size={4} style={{ width: '100%' }}>
          <span>持续运行：{Math.floor((qualificationRuntime?.runningDurationMs ?? 0) / 1000)} / {qualificationRuntime?.debugSuccessMinRunningSeconds ?? 120} 秒</span>
          <Progress size="small" percent={qualifyingInstance.status === 'debug_success_running' ? 100 : Math.min(100, Math.floor((qualificationRuntime?.runningDurationMs ?? 0) / 10 / (qualificationRuntime?.debugSuccessMinRunningSeconds ?? 120)))} />
          <span>成功 Checkpoint：{(qualificationCheckpoints?.counts?.completed ?? 0) > 0 || qualifyingInstance.status === 'debug_success_running' ? '已满足' : '等待中'}</span>
          {qualificationError && <Typography.Text type="warning">资格数据暂不可用：{qualificationError}</Typography.Text>}
        </Space> : '实例需达到配置的持续运行时长（默认 2 分钟）且至少产生一次成功 Checkpoint。'}
        style={{ marginBottom: 12 }} />
      <Alert showIcon type="info" message="调试成功条件" description="实例需达到配置的持续运行时长（默认 2 分钟）且至少产生一次成功 Checkpoint；提前停止会记为已取消，不能用于正式启动。" style={{ marginBottom: 12 }} />
      <InstanceListToolbar keyword={keyword} searchField={searchField} status={status} sortOrder={sortOrder}
        statusOptions={[{ label: '全部状态', value: 'all' }, ...Object.entries(statusLabel).map(([value, label]) => ({ value, label }))]}
        loading={loading} refreshLabel="刷新调试记录"
        primaryAction={<Button type="primary" icon={<PlayCircleOutlined />} onClick={openConfig}>调试</Button>}
        onKeywordChange={setKeyword} onSearchFieldChange={setSearchField} onStatusChange={setStatus} onSortOrderChange={setSortOrder}
        onRefresh={() => void reload()} />
      <Table
        rowKey="id"
        size="small"
        loading={loading}
        dataSource={filtered}
        locale={{ emptyText: '暂无调试实例' }}
        scroll={{ x: 1690 }}
        rowClassName={(row) => row.id === selectedInstanceId ? 'debug-record-selected' : ''}
        pagination={{ pageSize: 6, showSizeChanger: true, pageSizeOptions: [6, 10, 20], showTotal: (value) => `共 ${value} 条` }}
        columns={[
          { title: '实例 ID', dataIndex: 'id', width: 95, fixed: 'left' },
          { title: '状态', dataIndex: 'status', width: 150, fixed: 'left', render: (value: string) => <Tag color={value === 'running' || value === 'debug_success_running' || value === 'killed_success' ? 'green' : value === 'failed' ? 'red' : 'default'}>{statusLabel[value] ?? value}</Tag> },
          { title: 'JobID', dataIndex: 'jobId', width: 230, ellipsis: true, render: (value: string, row: TaskInstance) => value || (ACTIVE.includes(row.status) ? '同步中' : '-') },
          { title: 'YARN Application ID', dataIndex: 'yarnApplicationId', width: 210, ellipsis: true, render: (value: string, row: TaskInstance) => value || (ACTIVE.includes(row.status) ? '提交中' : '-') },
          { title: 'Flink UI', dataIndex: 'trackingUrl', width: 120, render: (value: string, row: TaskInstance) => value ? <Typography.Link href={value} target="_blank"><LinkOutlined /> 打开</Typography.Link> : (ACTIVE.includes(row.status) ? '同步中' : '-') },
          { title: 'Savepoint', dataIndex: 'savepointPath', width: 230, ellipsis: true, render: (value: string, row: TaskInstance) => value || (ACTIVE.includes(row.status) ? '停止后生成' : '-') },
          { title: '失败原因', dataIndex: 'failureMessage', width: 220, ellipsis: true, render: (value: string, row: TaskInstance) => row.status === 'failed' ? (value || '请查看运行日志') : '-' },
          { title: '开始时间', dataIndex: 'startedAt', width: 170, render: (value: string) => value || '-' },
          { title: '结束时间', dataIndex: 'endedAt', width: 170, render: (value: string, row: TaskInstance) => value || (ACTIVE.includes(row.status) ? '运行中' : '-') },
          {
            title: '操作', fixed: 'right', width: 230, render: (_: unknown, row: TaskInstance) => <Space size={12}>
              <Typography.Link onClick={() => void inspect(row, 'config')}>实例配置</Typography.Link>
              <Typography.Link onClick={() => setLogInstance(row)}>日志</Typography.Link>
              <Typography.Link onClick={() => setMappingInstance(row)}>同步表</Typography.Link>
              {ACTIVE.includes(row.status) && <Typography.Link disabled={!row.managed} onClick={async () => {
                if (!task || !row.managed) return;
                try { setStoppingInstanceId(row.id); await stopInstance(task.id, row.id); message.success('停止请求已提交'); await reload(); }
                catch (error) { message.error((error as Error).message); }
                finally { setStoppingInstanceId(undefined); }
              }}>{stoppingInstanceId === row.id ? '停止中…' : '停止'}</Typography.Link>}
            </Space>,
          },
        ]}
      />
    </div>
  );

  const debug = task ? debugTarget(task, servers) : { server: undefined, prefix: '', targets: [] as string[] };
  const debugMappings = task ? task.taskConfig.cdcConfig.selectedTables.map((sourceTable, index) => ({
    id: index + 1,
    source: `${task.taskConfig.cdcConfig.databaseName || debug.server?.databaseName || '-'}.${sourceTable}`,
    target: `${DEBUG_TARGET_DATABASE}.${debug.targets[index] || '-'}`,
  })) : [];

  return <>
    <Drawer className="sync-task-detail-drawer sync-debug-drawer realtime-debug-drawer" title={`${task?.name ?? ''} 调试`} open={open} onClose={onClose} placement="bottom" height="72vh">
      <Tabs className="ui-flat-tabs" items={[
        { key: 'records', label: '调试记录', children: records },
        { key: 'verify', label: '验数记录', children: <Table rowKey="id" size="small" dataSource={[]} pagination={false} locale={{ emptyText: '暂无验数记录' }} scroll={{ x: 1820 }} columns={[
          { title: 'ID', dataIndex: 'id', width: 90 },
          { title: '调试记录ID', dataIndex: 'debugRecordId', width: 190, ellipsis: true },
          { title: '线上表', dataIndex: 'onlineTable', width: 220, ellipsis: true },
          { title: '调试表', dataIndex: 'debugTable', width: 220, ellipsis: true },
          { title: '校验状态', dataIndex: 'checkStatus', width: 110, render: (value: string) => <Typography.Text type={value === '不通过' ? 'danger' : undefined}>{value}</Typography.Text> },
          { title: '是否分区表', dataIndex: 'partitioned', width: 120 },
          { title: '元数据是否一致', dataIndex: 'metadataMatched', width: 140, render: (value: string) => <Typography.Text type={value === '否' ? 'danger' : undefined}>{value}</Typography.Text> },
          { title: '行数是否一致', dataIndex: 'rowCountMatched', width: 130 },
          { title: 'CRC32值是否一致', dataIndex: 'crc32Matched', width: 150 },
          { title: '配置规则', dataIndex: 'rule', width: 120, render: (value: string) => value ? <Typography.Link>{value}</Typography.Link> : '-' },
          { title: '创建时间', dataIndex: 'createdAt', width: 170 },
          { title: '更新时间', dataIndex: 'updatedAt', width: 170 },
          { title: '操作人', dataIndex: 'operator', width: 120 },
          { title: '操作', key: 'actions', fixed: 'right', width: 280, render: () => <Space split={<span className="table-action-split" />}><Typography.Link>对比规则</Typography.Link><Typography.Link>运行</Typography.Link><Typography.Link>差异报告</Typography.Link><Typography.Link>日志</Typography.Link></Space> },
        ]} /> },
      ]} />
    </Drawer>
    <Modal className="realtime-debug-config-modal" title="调试配置" open={configOpen} width={1280} okText="开始调试" cancelText="取消" confirmLoading={submitting} okButtonProps={{ disabled: supportLoading || commandLoading }} closable={!submitting} maskClosable={!submitting} keyboard={!submitting} destroyOnHidden onOk={() => void submit()} onCancel={() => !submitting && setConfigOpen(false)}>
      {task && <div className="sync-advanced-layout sync-debug-config-layout"><Form className="sync-debug-config-form sync-config-scroll-content" form={form} layout="vertical">
        <Typography.Title id="sync-debug-config-basic" level={5} className="realtime-detail-title sync-config-anchor-section">基础信息</Typography.Title>
        <Descriptions bordered size="small" column={2}>
          <Descriptions.Item label="任务名称">{task.name}</Descriptions.Item>
          <Descriptions.Item label="负责人">{task.owner || '-'}</Descriptions.Item>
          <Descriptions.Item label="描述" span={2}>{task.description || '-'}</Descriptions.Item>
          <Descriptions.Item label="flink版本" span={2}>{task.flinkVersion || '2.2.1'}</Descriptions.Item>
        </Descriptions>
        <Typography.Title id="sync-debug-config-alarm" level={5} className="realtime-detail-title sync-config-anchor-section">告警配置</Typography.Title>
        <Descriptions bordered size="small" column={2}>
          <Descriptions.Item label="报警设置类型">{!task.taskConfig.alarmType || task.taskConfig.alarmType === 'task-failed' ? '任务失败' : task.taskConfig.alarmType}</Descriptions.Item>
          <Descriptions.Item label="告警组">{task.taskConfig.alarmGroup || '-'}</Descriptions.Item>
        </Descriptions>
        <div id="sync-debug-config-source" className="sync-config-section-heading sync-config-anchor-section">
          <Typography.Title level={5}>源端配置</Typography.Title>
          <Typography.Text type="secondary">选择 MySQL Server，源库由 Server 配置自动带出。</Typography.Text>
        </div>
        <Descriptions bordered size="small" column={2}>
          <Descriptions.Item label="源端类型"><Select disabled value="mysql-cdc" options={[{ label: 'MySQL CDC', value: 'mysql-cdc' }]} /></Descriptions.Item>
          <Descriptions.Item label="Server"><Select disabled value={debug.server?.name || task.sourceServerName || String(task.sourceServerId)} options={[{ label: debug.server?.name || task.sourceServerName || String(task.sourceServerId), value: debug.server?.name || task.sourceServerName || String(task.sourceServerId) }]} /></Descriptions.Item>
        </Descriptions>
        <div id="sync-debug-config-public" className="sync-config-section-heading sync-config-anchor-section">
          <Typography.Title level={5}>公共配置</Typography.Title>
          <Typography.Text type="secondary">配置同步源表、MySQL CDC 参数、目标 Paimon 表结构及同步参数。</Typography.Text>
        </div>
        <Descriptions bordered size="small" column={1}>
          <Descriptions.Item label="源库"><Select disabled value={task.taskConfig.cdcConfig.databaseName || debug.server?.databaseName || '-'} options={[{ label: task.taskConfig.cdcConfig.databaseName || debug.server?.databaseName || '-', value: task.taskConfig.cdcConfig.databaseName || debug.server?.databaseName || '-' }]} /></Descriptions.Item>
          <Descriptions.Item label="源表列表"><Select mode="multiple" disabled value={task.taskConfig.cdcConfig.selectedTables} options={task.taskConfig.cdcConfig.selectedTables.map((table) => ({ label: table, value: table }))} /></Descriptions.Item>
          <Descriptions.Item label="Mysql配置"><div className="realtime-dynamic-param-grid">{params.filter((item) => item.paramType === 'mysql_conf' && Boolean(item.required)).map(dynamicParam)}</div><SyncMoreConfigRows paramType="mysql_conf" formNamePath={['mysqlConfOverrides']} taskParams={params} /></Descriptions.Item>
          <Descriptions.Item label="目标Paimon库">{DEBUG_TARGET_DATABASE}</Descriptions.Item>
          <Descriptions.Item label="目标Paimon表前缀">{debug.prefix || '-'}</Descriptions.Item>
          <Descriptions.Item label="目标Paimon表列表"><Input.TextArea disabled value={debug.targets.join('\n')} rows={Math.max(2, Math.min(debug.targets.length, 6))} /></Descriptions.Item>
          <Descriptions.Item label="目标Paimon表同步元数据列">{task.taskConfig.cdcConfig.metadataColumns?.join('、') || '-'}</Descriptions.Item>
          <Descriptions.Item label="目标Paimon表类型映射">{task.taskConfig.cdcConfig.typeMappings?.join('、') || '-'}</Descriptions.Item>
          <Descriptions.Item label="目标Paimon表配置"><div className="realtime-dynamic-param-grid">
            <SyncTopologyConfigRows tableConfPath={['tableConfOverrides']} parallelismPath={['parallelism']}
              flinkConfPath={['flinkConfOverrides']} readOnly={Boolean(task.editPolicy?.productionLocked)}
              frozenParallelism={task.editPolicy?.productionLocked ? task.taskConfig.parallelism : undefined} />
            {params.filter((item) => item.paramType === 'table_conf' && Boolean(item.required)
              && !SYNC_TOPOLOGY_KEYS.has(item.paramKey)).map(dynamicParam)}
          </div><SyncMoreConfigRows paramType="table_conf" formNamePath={['tableConfOverrides']} taskParams={params} /></Descriptions.Item>
          <Descriptions.Item label="整库模式">{task.taskConfig.cdcConfig.mode || '-'}</Descriptions.Item>
        </Descriptions>
        <Typography.Title id="sync-debug-config-private" level={5} className="realtime-detail-title sync-config-anchor-section">私有配置</Typography.Title>
        <Table size="small" pagination={false} rowKey="table" dataSource={task.taskConfig.cdcConfig.selectedTables.map((table, index) => ({ table, index: index + 1, config: task.taskConfig.cdcConfig.tableConfigs?.[table] }))} scroll={{ x: 980, y: 320 }} columns={[
          { title: '序号', dataIndex: 'index', width: 64 }, { title: '源表', dataIndex: 'table', width: 220, ellipsis: true },
          { title: '计算列', width: 300, ellipsis: true, render: (_: unknown, row: { config?: { computedColumns?: string[] } }) => row.config?.computedColumns?.join('；') || '未配置' },
          { title: '主键', width: 220, render: (_: unknown, row: { config?: { primaryKeys?: string[] } }) => row.config?.primaryKeys?.join('、') || '继承源表' },
          { title: '分区键', width: 220, render: (_: unknown, row: { config?: { partitionKeys?: string[] } }) => row.config?.partitionKeys?.join('、') || '不分区' },
          { title: '状态', width: 110, render: (_: unknown, row: { config?: { computedColumns?: string[]; primaryKeys?: string[]; partitionKeys?: string[] } }) => row.config?.computedColumns?.length || row.config?.primaryKeys?.length || row.config?.partitionKeys?.length ? <Tag color="blue">已覆盖</Tag> : <Tag>继承源表</Tag> },
        ]} />
        <Typography.Title id="sync-debug-config-startup" level={5} className="realtime-detail-title sync-config-anchor-section">启动设置</Typography.Title>
        <Descriptions bordered size="small" column={2}>
          <Descriptions.Item label="启动方式" span={2}>
            <Form.Item name="startType" hidden><Input /></Form.Item>
            <Form.Item name="consumePointMode" hidden><Input /></Form.Item>
            <Select aria-label="启动方式" style={{ width: '100%' }}
              value={consumePointMode === 'timestamp' ? 'timestamp' : startType}
              disabled={stateHistoryLoading}
              onChange={(value) => void selectStartMethod(value)}
              options={[
                { label: '首次全量同步', value: 'direct' },
                { label: '从 Savepoint 恢复', value: 'savepoint' },
                { label: '从 Checkpoint 恢复', value: 'checkpoint' },
                { label: '从指定时间戳开始消费', value: 'timestamp' },
              ]} />
          </Descriptions.Item>
          {consumePointMode === 'timestamp' && <Descriptions.Item label="消费起始时间" span={2}><Form.Item name="sourceStartupTime" noStyle rules={[{ required: true, message: '请选择消费起始时间' }, { validator: (_, value) => !value || value.valueOf() <= Date.now() ? Promise.resolve() : Promise.reject(new Error('消费起始时间不能晚于当前时间')) }]}><DatePicker showTime style={{ width: '100%' }} placeholder="请选择过去的时间" /></Form.Item></Descriptions.Item>}
          <Descriptions.Item label="历史状态"><Form.Item noStyle shouldUpdate={(previous, current) => previous.startType !== current.startType}>{({ getFieldValue }) => getFieldValue('startType') !== 'direct' ? <Form.Item name="statePath" noStyle rules={[{ required: true, message: '请选择历史状态' }]}><Select aria-label="历史状态" loading={stateHistoryLoading} notFoundContent={stateHistoryLoading ? '加载中…' : '暂无可用状态'} options={stateHistory.map((item) => ({ label: String(item.label ?? item.path), value: String(item.path) }))} /></Form.Item> : <Typography.Text type="secondary">全量同步不需要恢复点</Typography.Text>}</Form.Item></Descriptions.Item>
        </Descriptions>
        {consumePointMode === 'timestamp' && <Alert showIcon type="warning" message="本次调试从指定时间戳开始消费，调试目标表不会预先清空" description="选择较早时间可能重复消费，选择较晚时间可能跳过事件；时间早于 MySQL Binlog 保留范围时调试会失败。" style={{ marginTop: 12 }} />}
        <Typography.Title id="sync-debug-config-runtime" level={5} className="realtime-detail-title sync-config-anchor-section">资源与运行</Typography.Title>
        <Descriptions bordered size="small" column={2}>
          <Descriptions.Item label="并行度"><Form.Item name="parallelism" noStyle rules={[{ required: true }, { type: 'number', min: 1, max: 4 }]}><Tooltip title="由目标 Paimon 表 Sink 并行度统一决定"><InputNumber min={1} max={4} disabled style={{ width: '100%' }} /></Tooltip></Form.Item></Descriptions.Item>
          <Descriptions.Item label="Checkpoint 间隔"><Form.Item name="checkpointInterval" noStyle rules={[{ required: true }, { type: 'number', min: 10, max: 600 }]}><InputNumber min={10} max={600} addonAfter="秒" style={{ width: '100%' }} /></Form.Item></Descriptions.Item>
          <Descriptions.Item label="TaskManager 内存"><Form.Item name="taskManagerMemory" noStyle rules={[{ required: true }]}><Input placeholder="3GB" /></Form.Item></Descriptions.Item>
          <Descriptions.Item label="JobManager 内存"><Form.Item name="jobManagerMemory" noStyle rules={[{ required: true }]}><Input placeholder="1GB" /></Form.Item></Descriptions.Item>
          <Descriptions.Item label="Flink配置" span={2}><div className="realtime-dynamic-param-grid">{params.filter((item) => item.paramType === 'flink_conf' && Boolean(item.required)).map(dynamicParam)}</div><SyncMoreConfigRows paramType="flink_conf" formNamePath={['flinkConfOverrides']} taskParams={params} /></Descriptions.Item>
        </Descriptions>
        <Typography.Title id="sync-debug-config-command" level={5} className="realtime-detail-title sync-config-anchor-section">命令预览</Typography.Title>
        <Form.Item>
          <Button type="link" icon={<EyeOutlined />} loading={commandLoading} onClick={async () => { if (!task) return; try { setCommandLoading(true); const values = buildAction(await form.validateFields()); const value = await previewSavedSyncTask(task.id, true, values); setCommand(value.command); } catch (error) { if (error instanceof Error) message.error(error.message); } finally { setCommandLoading(false); } }}>预览</Button>
          <Input.TextArea className="task-command-preview-textarea" value={command} readOnly rows={10} wrap="off" placeholder="点击预览生成 Paimon Action 调试命令" />
        </Form.Item>
      </Form><SyncSectionNav prefix="sync-debug-config" items={[
        { key: 'basic', label: '基础信息' }, { key: 'alarm', label: '告警配置' },
        { key: 'source', label: '源端配置' }, { key: 'public', label: '公共配置' },
        { key: 'private', label: '私有配置' }, { key: 'startup', label: '启动设置' },
        { key: 'runtime', label: '运行与资源' }, { key: 'command', label: '命令预览' },
      ]} /></div>}
    </Modal>
    <Modal className="sync-mapping-modal" title={`同步表映射${mappingInstance ? ` - 调试实例 ${mappingInstance.id}` : ''}`} open={Boolean(mappingInstance)} footer={null} width={900} destroyOnHidden onCancel={() => setMappingInstance(undefined)}>
      <Table rowKey="id" size="small" pagination={false} scroll={{ y: 480 }} locale={{ emptyText: '暂无同步表映射' }} dataSource={debugMappings} columns={[
        { title: '序号', width: 72, render: (_: unknown, __: unknown, index: number) => index + 1 },
        { title: '源表', dataIndex: 'source', width: 360, render: (value: string) => <span className="sync-mapping-full-name">{value}</span> },
        { title: '目标 Paimon 表', dataIndex: 'target', render: (value: string) => <span className="sync-mapping-full-name">{value}</span> },
      ]} />
    </Modal>
    <InstanceInspectorModal open={Boolean(inspector)} title={inspector?.title} kind={inspector?.kind} value={inspector?.value} loading={inspectorLoading} renderConfig={(value) => <SyncTaskConfigDetail value={value} sourceServerName={task?.sourceServerName} showNavigation navigationPrefix="sync-debug-instance" />} onClose={() => { inspectorRequestSequenceRef.current += 1; setInspector(undefined); setInspectorLoading(false); }} />
  </>;
}
