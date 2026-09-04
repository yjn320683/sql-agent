import { EyeOutlined, LinkOutlined, PlayCircleOutlined, QuestionCircleOutlined } from '@ant-design/icons';
import {
  Button,
  Descriptions,
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
  message,
} from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  getInstanceInfo,
  getStateHistory,
  listInstances,
  listServers,
  listTaskParams,
  previewSavedSyncTask,
  startSyncTask,
  stopInstance,
} from '../api';
import type { RealtimeServer, SyncTask, TaskInstance, TaskParam } from '../types';
import InstanceInspectorModal, { type InstanceInspectorKind } from './InstanceInspectorModal';
import InstanceLogPanel from './InstanceLogPanel';
import InstanceListToolbar, { type InstanceSearchField, type InstanceSortOrder } from './InstanceListToolbar';
import SyncMoreConfigRows from './SyncMoreConfigRows';

interface Props {
  task?: SyncTask;
  open: boolean;
  onClose: () => void;
}

const ACTIVE = ['submitting', 'running', 'debug_success_running', 'stopping', 'restarting'];
const DEBUG_TARGET_DATABASE = 'paimon_debug';
const DEBUG_TABLE_SUFFIX = '_debug';
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
    const name = `${prefix}${table}`;
    return name.endsWith(DEBUG_TABLE_SUFFIX) ? name : `${name}${DEBUG_TABLE_SUFFIX}`;
  });
  return { server, prefix, targets };
};

export default function SyncDebugDrawer({ task, open, onClose }: Props) {
  const [instances, setInstances] = useState<TaskInstance[]>([]);
  const [params, setParams] = useState<TaskParam[]>([]);
  const [servers, setServers] = useState<RealtimeServer[]>([]);
  const [loading, setLoading] = useState(false);
  const [supportLoading, setSupportLoading] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState('all');
  const [searchField, setSearchField] = useState<InstanceSearchField>('all');
  const [sortOrder, setSortOrder] = useState<InstanceSortOrder>('startedAtDesc');
  const [configOpen, setConfigOpen] = useState(false);
  const [form] = Form.useForm();
  const [stateHistory, setStateHistory] = useState<Record<string, unknown>[]>([]);
  const [stateHistoryLoading, setStateHistoryLoading] = useState(false);
  const [command, setCommand] = useState('');
  const [commandLoading, setCommandLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [inspector, setInspector] = useState<{ title: string; kind: InstanceInspectorKind; value: unknown }>();
  const [mappingInstance, setMappingInstance] = useState<TaskInstance>();
  const [logInstance, setLogInstance] = useState<TaskInstance>();
  const [selectedInstanceId, setSelectedInstanceId] = useState<number>();
  const [stoppingInstanceId, setStoppingInstanceId] = useState<number>();
  const initializedConfigKeyRef = useRef('');
  const submittingRef = useRef(false);
  const acceptedInstancesRef = useRef(new Map<number, TaskInstance>());
  const instanceRequestSequenceRef = useRef(0);
  const supportRequestSequenceRef = useRef(0);

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
    const supportSequence = ++supportRequestSequenceRef.current;
    setSupportLoading(true);
    void Promise.all([listTaskParams(), listServers()]).then(([taskParams, allServers]) => {
      if (supportSequence === supportRequestSequenceRef.current) { setParams(taskParams); setServers(allServers); }
    }).catch((error) => { if (supportSequence === supportRequestSequenceRef.current) message.error((error as Error).message); })
      .finally(() => { if (supportSequence === supportRequestSequenceRef.current) setSupportLoading(false); });
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
      startType: 'direct', parallelism: task.taskConfig.parallelism ?? 1,
      checkpointInterval: task.taskConfig.checkpointInterval ?? 60,
      taskManagerMemory: task.taskConfig.taskManagerMemory || '3GB',
      jobManagerMemory: task.taskConfig.jobManagerMemory || '1GB',
      flinkConfOverrides: mergeParamDefaults(params, 'flink_conf', task.taskConfig.flinkConfOverrides),
      mysqlConfOverrides: mergeParamDefaults(params, 'mysql_conf', task.taskConfig.cdcConfig.mysqlConfOverrides),
      tableConfOverrides: mergeParamDefaults(params, 'table_conf', task.taskConfig.cdcConfig.tableConfOverrides),
    });
  }, [configOpen, form, params, supportLoading, task]);

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
    mysqlConfOverrides: normalizeOverrides(values.mysqlConfOverrides as Record<string, unknown>),
    tableConfOverrides: normalizeOverrides(values.tableConfOverrides as Record<string, unknown>),
    flinkConfOverrides: normalizeOverrides(values.flinkConfOverrides as Record<string, unknown>),
  }) as Parameters<typeof startSyncTask>[1];

  const openConfig = () => {
    initializedConfigKeyRef.current = '';
    setCommand(''); setStateHistory([]); setConfigOpen(true);
  };

  const inspect = async (instance: TaskInstance, kind: 'config' | 'startup-log' | 'runtime-log') => {
    if (!task) return;
    try {
      setInspector({ title: kind === 'config' ? `调试配置 - 调试实例 ${instance.id}` : `调试实例 ${instance.id} - ${kind === 'startup-log' ? '启动日志' : '运行日志'}`, kind, value: await getInstanceInfo(task.id, instance.id, kind) });
    } catch (error) { message.error((error as Error).message); }
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
    <div className="realtime-debug-records">
      <InstanceListToolbar keyword={keyword} searchField={searchField} status={status} sortOrder={sortOrder}
        statusOptions={[{ label: '全部状态', value: 'all' }, ...Object.entries(statusLabel).map(([value, label]) => ({ value, label }))]}
        loading={loading} refreshLabel="刷新调试记录"
        primaryAction={<Button type="primary" icon={<PlayCircleOutlined />} onClick={openConfig}>调试</Button>}
        onKeywordChange={setKeyword} onSearchFieldChange={setSearchField} onStatusChange={setStatus} onSortOrderChange={setSortOrder}
        onReset={() => { setKeyword(''); setSearchField('all'); setStatus('all'); setSortOrder('startedAtDesc'); }} onRefresh={() => void reload()} />
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
    <Drawer className="realtime-debug-drawer" title={`${task?.name ?? ''} 调试`} open={open} onClose={onClose} placement="bottom" height="72vh">
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
      {task && <Form form={form} layout="vertical" onValuesChange={async (changed) => {
        if (!changed.startType || changed.startType === 'direct') { setStateHistory([]); return; }
        form.setFieldValue('statePath', undefined);
        try {
          setStateHistoryLoading(true);
          const history = await getStateHistory(task.id, changed.startType);
          setStateHistory(history);
          if (history[0]?.path) form.setFieldValue('statePath', String(history[0].path));
        } catch { setStateHistory([]); }
        finally { setStateHistoryLoading(false); }
      }}>
        <Typography.Title level={5} className="realtime-detail-title">基础信息</Typography.Title>
        <Descriptions bordered size="small" column={2}>
          <Descriptions.Item label="任务名称">{task.name}</Descriptions.Item>
          <Descriptions.Item label="负责人">{task.owner || '-'}</Descriptions.Item>
          <Descriptions.Item label="描述" span={2}>{task.description || '-'}</Descriptions.Item>
          <Descriptions.Item label="flink版本" span={2}>{task.flinkVersion || '2.2.1'}</Descriptions.Item>
        </Descriptions>
        <Typography.Title level={5} className="realtime-detail-title">告警配置</Typography.Title>
        <Descriptions bordered size="small" column={2}>
          <Descriptions.Item label="报警设置类型">{!task.taskConfig.alarmType || task.taskConfig.alarmType === 'task-failed' ? '任务失败' : task.taskConfig.alarmType}</Descriptions.Item>
          <Descriptions.Item label="告警组">{task.taskConfig.alarmGroup || '-'}</Descriptions.Item>
        </Descriptions>
        <Typography.Title level={5} className="realtime-detail-title">源端&调试目标Paimon配置</Typography.Title>
        <Typography.Title level={5} className="realtime-detail-title">公共配置</Typography.Title>
        <Descriptions bordered size="small" column={2}>
          <Descriptions.Item label="源端类型">Mysql CDC</Descriptions.Item>
          <Descriptions.Item label="Server">{debug.server?.name || task.sourceServerName || task.sourceServerId}</Descriptions.Item>
        </Descriptions>
        <Descriptions bordered size="small" column={1}>
          <Descriptions.Item label="源库">{task.taskConfig.cdcConfig.databaseName || debug.server?.databaseName || '-'}</Descriptions.Item>
          <Descriptions.Item label="源表列表">{task.taskConfig.cdcConfig.selectedTables.join('、') || '-'}</Descriptions.Item>
          <Descriptions.Item label="Mysql配置"><div className="realtime-dynamic-param-grid">{params.filter((item) => item.paramType === 'mysql_conf' && Boolean(item.required)).map(dynamicParam)}</div><SyncMoreConfigRows paramType="mysql_conf" formNamePath={['mysqlConfOverrides']} taskParams={params} /></Descriptions.Item>
          <Descriptions.Item label="目标Paimon库">{DEBUG_TARGET_DATABASE}</Descriptions.Item>
          <Descriptions.Item label="目标Paimon表前缀">{debug.prefix || '-'}</Descriptions.Item>
          <Descriptions.Item label="目标Paimon表列表"><Input.TextArea disabled value={debug.targets.join('\n')} rows={Math.max(2, Math.min(debug.targets.length, 6))} /></Descriptions.Item>
          <Descriptions.Item label="目标Paimon表同步元数据列">{task.taskConfig.cdcConfig.metadataColumns?.join('、') || '-'}</Descriptions.Item>
          <Descriptions.Item label="目标Paimon表类型映射">{task.taskConfig.cdcConfig.typeMappings?.join('、') || '-'}</Descriptions.Item>
          <Descriptions.Item label="目标Paimon表配置"><div className="realtime-dynamic-param-grid">{params.filter((item) => item.paramType === 'table_conf' && Boolean(item.required)).map(dynamicParam)}</div><SyncMoreConfigRows paramType="table_conf" formNamePath={['tableConfOverrides']} taskParams={params} /></Descriptions.Item>
          <Descriptions.Item label="整库模式">{task.taskConfig.cdcConfig.mode || '-'}</Descriptions.Item>
        </Descriptions>
        <Typography.Title level={5} className="realtime-detail-title">私有配置</Typography.Title>
        <Table size="small" pagination={false} rowKey="table" dataSource={task.taskConfig.cdcConfig.selectedTables.map((table, index) => ({ table, index: index + 1, config: task.taskConfig.cdcConfig.tableConfigs?.[table] }))} scroll={{ x: 980, y: 320 }} columns={[
          { title: '序号', dataIndex: 'index', width: 64 }, { title: '源表', dataIndex: 'table', width: 220, ellipsis: true },
          { title: '计算列', width: 300, ellipsis: true, render: (_: unknown, row: { config?: { computedColumns?: string[] } }) => row.config?.computedColumns?.join('；') || '未配置' },
          { title: '主键', width: 220, render: (_: unknown, row: { config?: { primaryKeys?: string[] } }) => row.config?.primaryKeys?.join('、') || '继承源表' },
          { title: '分区键', width: 220, render: (_: unknown, row: { config?: { partitionKeys?: string[] } }) => row.config?.partitionKeys?.join('、') || '不分区' },
          { title: '状态', width: 110, render: (_: unknown, row: { config?: { computedColumns?: string[]; primaryKeys?: string[]; partitionKeys?: string[] } }) => row.config?.computedColumns?.length || row.config?.primaryKeys?.length || row.config?.partitionKeys?.length ? <Tag color="blue">已覆盖</Tag> : <Tag>继承源表</Tag> },
        ]} />
        <Typography.Title level={5} className="realtime-detail-title">启动设置</Typography.Title>
        <Descriptions bordered size="small" column={2}>
          <Descriptions.Item label="启动类型"><Form.Item name="startType" noStyle rules={[{ required: true, message: '请选择启动类型' }]}><Select options={[{ label: '直接启动', value: 'direct' }, { label: 'checkpoint', value: 'checkpoint' }, { label: 'savepoint', value: 'savepoint' }]} /></Form.Item></Descriptions.Item>
          <Descriptions.Item label="历史状态"><Form.Item noStyle shouldUpdate={(previous, current) => previous.startType !== current.startType}>{({ getFieldValue }) => getFieldValue('startType') !== 'direct' ? <Form.Item name="statePath" noStyle rules={[{ required: true, message: '请选择历史状态' }]}><Select loading={stateHistoryLoading} notFoundContent={stateHistoryLoading ? '加载中…' : '暂无可用状态'} options={stateHistory.map((item) => ({ label: String(item.label ?? item.path), value: String(item.path) }))} /></Form.Item> : <Typography.Text type="secondary">直接启动不需要历史状态</Typography.Text>}</Form.Item></Descriptions.Item>
        </Descriptions>
        <Typography.Title level={5} className="realtime-detail-title">资源与运行</Typography.Title>
        <Descriptions bordered size="small" column={2}>
          <Descriptions.Item label="并行度"><Form.Item name="parallelism" noStyle rules={[{ required: true }, { type: 'number', min: 1, max: 128 }]}><InputNumber min={1} max={128} style={{ width: '100%' }} /></Form.Item></Descriptions.Item>
          <Descriptions.Item label="Checkpoint 间隔"><Form.Item name="checkpointInterval" noStyle rules={[{ required: true }, { type: 'number', min: 10, max: 600 }]}><InputNumber min={10} max={600} addonAfter="秒" style={{ width: '100%' }} /></Form.Item></Descriptions.Item>
          <Descriptions.Item label="TaskManager 内存"><Form.Item name="taskManagerMemory" noStyle rules={[{ required: true }]}><Input placeholder="3GB" /></Form.Item></Descriptions.Item>
          <Descriptions.Item label="JobManager 内存"><Form.Item name="jobManagerMemory" noStyle rules={[{ required: true }]}><Input placeholder="1GB" /></Form.Item></Descriptions.Item>
          <Descriptions.Item label="Flink配置" span={2}><div className="realtime-dynamic-param-grid">{params.filter((item) => item.paramType === 'flink_conf' && Boolean(item.required)).map(dynamicParam)}</div><SyncMoreConfigRows paramType="flink_conf" formNamePath={['flinkConfOverrides']} taskParams={params} /></Descriptions.Item>
        </Descriptions>
        <Typography.Title level={5} className="realtime-detail-title">命令预览</Typography.Title>
        <Form.Item>
          <Button type="link" icon={<EyeOutlined />} loading={commandLoading} onClick={async () => { if (!task) return; try { setCommandLoading(true); const values = buildAction(await form.validateFields()); const value = await previewSavedSyncTask(task.id, true, values); setCommand(value.command); } catch (error) { if (error instanceof Error) message.error(error.message); } finally { setCommandLoading(false); } }}>预览</Button>
          <Input.TextArea className="task-command-preview-textarea" value={command} readOnly rows={10} wrap="off" placeholder="点击预览生成 Paimon Action 调试命令" />
        </Form.Item>
      </Form>}
    </Modal>
    <Modal className="sync-mapping-modal" title={`同步表映射${mappingInstance ? ` - 调试实例 ${mappingInstance.id}` : ''}`} open={Boolean(mappingInstance)} footer={null} width={900} destroyOnHidden onCancel={() => setMappingInstance(undefined)}>
      <Table rowKey="id" size="small" pagination={false} scroll={{ y: 480 }} locale={{ emptyText: '暂无同步表映射' }} dataSource={debugMappings} columns={[
        { title: '序号', width: 72, render: (_: unknown, __: unknown, index: number) => index + 1 },
        { title: '源表', dataIndex: 'source', width: 360, render: (value: string) => <span className="sync-mapping-full-name">{value}</span> },
        { title: '目标 Paimon 表', dataIndex: 'target', render: (value: string) => <span className="sync-mapping-full-name">{value}</span> },
      ]} />
    </Modal>
    <InstanceInspectorModal open={Boolean(inspector)} title={inspector?.title} kind={inspector?.kind} value={inspector?.value} onClose={() => setInspector(undefined)} />
  </>;
}
