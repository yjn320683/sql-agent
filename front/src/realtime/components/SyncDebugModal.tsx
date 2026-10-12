import { EyeOutlined, QuestionCircleOutlined } from '@ant-design/icons';
import {
  Alert,
  Button,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd';
import { useEffect, useRef, useState } from 'react';
import {
  getStateHistory,
  previewSavedSyncTask,
  startSyncTask,
} from '../api';
import { MYSQL_METADATA_COLUMN_PREFIX, type RealtimeServer, type SyncTask, type TaskParam } from '../types';
import SyncMoreConfigRows from './SyncMoreConfigRows';
import SyncSectionNav from './SyncSectionNav';
import SyncTopologyConfigRows, { mergeSyncTopologyOverrides } from './SyncTopologyConfigRows';

interface Props {
  task?: SyncTask;
  open: boolean;
  params: TaskParam[];
  servers: RealtimeServer[];
  supportLoading?: boolean;
  onClose: () => void;
  onStarted: () => void;
}

const SYNC_TOPOLOGY_KEYS = new Set(['bucket', 'sink.parallelism']);
const DEBUG_TARGET_DATABASE = 'paimon_debug';

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

export default function SyncDebugModal({ task, open, params, servers, supportLoading = false, onClose, onStarted }: Props) {
  const [form] = Form.useForm();
  const startType = Form.useWatch('startType', form);
  const [stateHistory, setStateHistory] = useState<Record<string, unknown>[]>([]);
  const [stateHistoryLoading, setStateHistoryLoading] = useState(false);
  const [command, setCommand] = useState('');
  const [commandLoading, setCommandLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const initializedConfigKeyRef = useRef('');
  const submittingRef = useRef(false);
  const startSelectionVersionRef = useRef(0);

  useEffect(() => {
    if (!open || !task) return;
    initializedConfigKeyRef.current = '';
    setCommand(''); setStateHistory([]);
  }, [open, task?.id]);

  useEffect(() => {
    if (!open || !task || supportLoading) return;
    const key = String(task.id);
    if (initializedConfigKeyRef.current === key) return;
    initializedConfigKeyRef.current = key;
    form.resetFields();
    form.setFieldsValue({
      startType: 'direct',
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
  }, [open, form, params, supportLoading, task]);

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
    mysqlConfOverrides: normalizeOverrides(values.mysqlConfOverrides as Record<string, unknown>),
    tableConfOverrides: mergeSyncTopologyOverrides(
      normalizeOverrides(values.tableConfOverrides as Record<string, unknown>),
      values.tableConfOverrides as Record<string, unknown>,
      values.parallelism,
    ),
    flinkConfOverrides: normalizeOverrides(values.flinkConfOverrides as Record<string, unknown>),
  }) as unknown as Parameters<typeof startSyncTask>[1];

  const selectStartMethod = async (method: 'direct' | 'checkpoint' | 'savepoint') => {
    if (!task) return;
    const selectionVersion = ++startSelectionVersionRef.current;
    const nextStartType = method;
    setStateHistory([]);
    form.setFieldsValue({
      startType: nextStartType,
      statePath: undefined,
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

  const submit = async () => {
    if (!task || submittingRef.current) return;
    submittingRef.current = true;
    try {
      const values = buildAction(await form.validateFields());
      setSubmitting(true);
      await startSyncTask(task.id, values, true);
      message.success('调试请求已提交');
      onStarted();
    } catch (error) {
      if (error instanceof Error) message.error(error.message);
    } finally { submittingRef.current = false; setSubmitting(false); }
  };

  const debug = task ? debugTarget(task, servers) : { server: undefined, prefix: '', targets: [] as string[] };
  return <Modal className="realtime-debug-config-modal" title={task ? `隔离调试配置 · ${task.name}` : '隔离调试配置'} open={open} width={1520} okText="开始调试" cancelText="取消" confirmLoading={submitting} okButtonProps={{ disabled: supportLoading || commandLoading }} closable={!submitting} maskClosable={!submitting} keyboard={!submitting} destroyOnHidden onOk={() => void submit()} onCancel={() => !submitting && onClose()}>
      <Alert showIcon type="info" message="本次配置仅写入 DEBUG 实例快照，不会修改或保存正式任务配置。" description="入仓目标会由后端自动切换为隔离调试表。" />
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
          <Descriptions.Item label="目标Paimon表同步元数据列前缀"><Input value={MYSQL_METADATA_COLUMN_PREFIX} disabled /></Descriptions.Item>
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
            <Select aria-label="启动方式" style={{ width: '100%' }}
              value={startType}
              disabled={stateHistoryLoading}
              onChange={(value) => void selectStartMethod(value)}
              options={[
                { label: '首次全量同步', value: 'direct' },
                { label: '从 Savepoint 恢复', value: 'savepoint' },
                { label: '从 Checkpoint 恢复', value: 'checkpoint' },
              ]} />
          </Descriptions.Item>
          <Descriptions.Item label="历史状态"><Form.Item noStyle shouldUpdate={(previous, current) => previous.startType !== current.startType}>{({ getFieldValue }) => getFieldValue('startType') !== 'direct' ? <Form.Item name="statePath" noStyle rules={[{ required: true, message: '请选择历史状态' }]}><Select aria-label="历史状态" loading={stateHistoryLoading} notFoundContent={stateHistoryLoading ? '加载中…' : '暂无可用状态'} options={stateHistory.map((item) => ({ label: String(item.label ?? item.path), value: String(item.path) }))} /></Form.Item> : <Typography.Text type="secondary">全量同步不需要恢复点</Typography.Text>}</Form.Item></Descriptions.Item>
        </Descriptions>
        <Typography.Title id="sync-debug-config-runtime" level={5} className="realtime-detail-title sync-config-anchor-section">运行资源</Typography.Title>
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
        { key: 'runtime', label: '运行资源' }, { key: 'command', label: '命令预览' },
      ]} /></div>}
    </Modal>;
}
