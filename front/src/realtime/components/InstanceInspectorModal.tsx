import { Descriptions, Empty, Modal, Table, Tag, Typography } from 'antd';
import type { ReactNode } from 'react';

export type InstanceInspectorKind = 'config' | 'startup-log' | 'runtime-log' | 'runtime' | 'resources' | 'checkpoints' | 'log-components';

interface Props {
  open: boolean;
  title?: string;
  kind?: InstanceInspectorKind;
  value?: unknown;
  onClose: () => void;
}

const labels: Record<string, string> = {
  id: 'ID', taskId: '任务 ID', name: '任务名称', owner: '负责人', description: '描述', flinkVersion: 'Flink 版本',
  sourceServerId: 'Server ID', sourceServerName: '源端 Server', sourceType: '源端类型', targetDatabase: '目标 Paimon 库',
  status: '状态', executionMode: '运行模式', jobId: 'Job ID', yarnApplicationId: 'YARN Application ID', trackingUrl: 'Flink UI',
  parallelism: '并行度', taskManagerMemory: 'TaskManager 内存', jobManagerMemory: 'JobManager 内存', checkpointInterval: 'Checkpoint 周期',
  alarmType: '报警设置类型', alarmGroup: '告警组', databaseName: '源库', selectedTables: '源表列表', domainPrefix: '业务域前缀',
  tablePrefix: '目标表前缀', metadataColumns: '元数据列', typeMappings: '类型映射', mode: '整库模式', ignoreIncompatible: '忽略不兼容表',
};

const asRecord = (value: unknown): Record<string, unknown> => value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {};

const display = (value: unknown): ReactNode => {
  if (value === undefined || value === null || value === '') return '-';
  if (typeof value === 'boolean') return value ? '是' : '否';
  if (Array.isArray(value)) {
    if (!value.length) return '-';
    if (value.some((item) => item && typeof item === 'object')) return <div className="realtime-structured-list">{value.map((entry, index) => <div className="realtime-structured-list-item" key={index}><Typography.Text strong>#{index + 1}</Typography.Text><StructuredKeyValueTable value={entry} /></div>)}</div>;
    return value.map((entry, index) => <Tag key={`${String(entry)}-${index}`}>{String(entry)}</Tag>);
  }
  if (typeof value === 'object') return <StructuredKeyValueTable value={value} />;
  return String(value);
};

export function StructuredKeyValueTable({ value }: { value: unknown }) {
  if (Array.isArray(value)) {
    const rows = value.map((entry, index) => ({ index: index + 1, entry }));
    if (!rows.length) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无数据" />;
    return <Table size="small" rowKey="index" pagination={false} dataSource={rows} columns={[
      { title: '序号', dataIndex: 'index', width: 72 },
      { title: '详情', dataIndex: 'entry', render: (entry: unknown) => typeof entry === 'object' ? <StructuredKeyValueTable value={entry} /> : display(entry) },
    ]} />;
  }
  const rows = Object.entries(asRecord(value)).map(([key, item]) => ({ key, name: labels[key] ?? key, value: item }));
  if (!rows.length) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无数据" />;
  return <Table size="small" rowKey="key" pagination={false} dataSource={rows} columns={[
    { title: '参数', dataIndex: 'name', width: 260 },
    { title: '值', dataIndex: 'value', render: (item: unknown) => display(item) },
  ]} />;
}

const Section = ({ title, children }: { title: string; children: ReactNode }) => <section className="realtime-instance-config-section">
  <Typography.Title level={5}>{title}</Typography.Title>
  {children}
</section>;

const item = (label: string, value: unknown, span = 1) => <Descriptions.Item key={label} label={label} span={span}>{display(value)}</Descriptions.Item>;

export function InstanceConfigView({ value }: { value: unknown }) {
  const outer = asRecord(value);
  const wrapped = asRecord(outer.config);
  const root = wrapped.taskConfig || wrapped.name ? wrapped : outer;
  const taskConfig = asRecord(root.taskConfig ?? root.config);
  const cdc = asRecord(taskConfig.cdcConfig);
  const selectedTables = Array.isArray(cdc.selectedTables) ? cdc.selectedTables.map(String) : [];
  const targetTables = Array.isArray(cdc.targetTableList) && cdc.targetTableList.length
    ? cdc.targetTableList.map(String)
    : cdc.tablePrefix ? selectedTables.map((table) => `${String(cdc.tablePrefix)}${table}`) : [];
  const tableConfigs = asRecord(cdc.tableConfigs);
  const hasRecognizedConfig = Boolean(root.name || root.taskConfig || taskConfig.cdcConfig);
  if (!hasRecognizedConfig) return <StructuredKeyValueTable value={value} />;
  return <div className="realtime-instance-config-view">
    <Section title="基础信息">
      <Descriptions bordered size="small" column={2}>
        {item('任务名称', root.name)}{item('负责人', root.owner)}{item('描述', root.description, 2)}
        {item('Flink 版本', root.flinkVersion)}{item('任务类型', 'MySQL CDC → Paimon')}
      </Descriptions>
    </Section>
    <Section title="告警配置">
      <Descriptions bordered size="small" column={2}>{item('报警设置类型', taskConfig.alarmType)}{item('告警组', taskConfig.alarmGroup)}</Descriptions>
    </Section>
    {Boolean(taskConfig.startType) && <Section title="启动设置">
      <Descriptions bordered size="small" column={2}>
        {item('启动类型', taskConfig.startType)}
        {item('历史状态', String(taskConfig.startType) === 'direct' ? '直接启动不需要历史状态' : taskConfig.statePath)}
      </Descriptions>
    </Section>}
    <Section title="源端与目标 Paimon 配置">
      <Descriptions bordered size="small" column={2}>
        {item('源端类型', root.sourceType ?? 'mysql-cdc')}{item('Server', root.sourceServerName ?? root.sourceServerId)}
        {item('源库', cdc.databaseName)}{item('目标 Paimon 库', cdc.targetDatabase ?? root.targetDatabase)}
        {item('源表列表', cdc.selectedTables, 2)}{item('业务域前缀', cdc.domainPrefix)}{item('目标表前缀', cdc.tablePrefix)}
        {item('目标 Paimon 表列表', targetTables, 2)}{item('排除表正则', cdc.excludingTables, 2)}
        {item('元数据列', cdc.metadataColumns, 2)}{item('类型映射', cdc.typeMappings, 2)}
        {item('整库模式', cdc.mode)}{item('忽略不兼容表', cdc.ignoreIncompatible)}
      </Descriptions>
    </Section>
    <Section title="源表私有配置">
      <Table size="small" pagination={false} rowKey="table" scroll={{ x: 1050 }} dataSource={selectedTables.map((table, index) => ({ index: index + 1, table, config: asRecord(tableConfigs[table]) }))} locale={{ emptyText: '暂无源表' }} columns={[
        { title: '序号', dataIndex: 'index', width: 70 },
        { title: '源表', dataIndex: 'table', width: 190, ellipsis: true },
        { title: '计算列', width: 300, ellipsis: true, render: (_: unknown, row: { config: Record<string, unknown> }) => display(row.config.computedColumns) },
        { title: '主键', width: 230, render: (_: unknown, row: { config: Record<string, unknown> }) => display(row.config.primaryKeys) },
        { title: '分区键', width: 230, render: (_: unknown, row: { config: Record<string, unknown> }) => display(row.config.partitionKeys) },
        { title: '状态', width: 100, render: (_: unknown, row: { config: Record<string, unknown> }) => Object.values(row.config).some((entry) => Array.isArray(entry) && entry.length) ? <Tag color="blue">已覆盖</Tag> : <Tag>继承源表</Tag> },
      ]} />
    </Section>
    <Section title="MySQL CDC 参数"><StructuredKeyValueTable value={cdc.mysqlConfOverrides} /></Section>
    <Section title="Paimon Table 参数"><StructuredKeyValueTable value={cdc.tableConfOverrides} /></Section>
    <Section title="资源与运行">
      <Descriptions bordered size="small" column={2}>
        {item('并行度', taskConfig.parallelism)}{item('Checkpoint 周期', taskConfig.checkpointInterval ? `${taskConfig.checkpointInterval}s` : undefined)}
        {item('TaskManager 内存', taskConfig.taskManagerMemory)}{item('JobManager 内存', taskConfig.jobManagerMemory)}
      </Descriptions>
      <div className="realtime-instance-flink-params"><StructuredKeyValueTable value={taskConfig.flinkConfOverrides} /></div>
    </Section>
  </div>;
}

const logText = (kind: InstanceInspectorKind | undefined, value: unknown) => {
  const record = asRecord(value);
  const raw = kind === 'startup-log' ? record.startupLog : kind === 'runtime-log' ? record.runtimeLog : value;
  const text = typeof raw === 'string' ? raw : JSON.stringify(raw ?? '', null, 2);
  return text.replace(/\\r\\n/g, '\n').replace(/\\n/g, '\n').replace(/\\t/g, '  ');
};

export default function InstanceInspectorModal({ open, title, kind, value, onClose }: Props) {
  const isLog = kind === 'startup-log' || kind === 'runtime-log';
  return <Modal className="realtime-instance-inspector-modal" title={title} open={open} footer={null} width={kind === 'config' ? 1120 : 1050} onCancel={onClose} destroyOnHidden>
    {kind === 'config' ? <InstanceConfigView value={value} /> : isLog ? <pre className="realtime-log-console">{logText(kind, value) || '暂无日志'}</pre> : <StructuredKeyValueTable value={value} />}
  </Modal>;
}
