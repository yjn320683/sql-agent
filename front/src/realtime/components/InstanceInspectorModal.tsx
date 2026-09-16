import { Descriptions, Empty, Modal, Spin, Table, Tag, Typography } from 'antd';
import type { ReactNode } from 'react';

export type InstanceInspectorKind = 'config' | 'startup-log' | 'runtime-log' | 'runtime' | 'resources' | 'checkpoints' | 'log-components';

interface Props {
  open: boolean;
  title?: string;
  kind?: InstanceInspectorKind;
  value?: unknown;
  loading?: boolean;
  renderConfig?: (value: unknown) => ReactNode;
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

/** 兼容参考平台历史快照（{task:{...}}）、本平台旧快照和统一 v1 配置。 */
const normalizeTaskSnapshot = (value: unknown) => {
  const outer = asRecord(value);
  const config = asRecord(outer.config);
  const candidate = Object.keys(config).length ? config : outer;
  const nestedTask = asRecord(candidate.task);
  const root = Object.keys(nestedTask).length ? nestedTask : candidate;
  const specific = asRecord(root.taskConfig);
  const alarm = asRecord(root.alarmConfig);
  const flink = asRecord(root.flinkConf);
  const unified = Object.keys(alarm).length > 0 || Object.keys(flink).length > 0;
  if (!unified) return root;
  return {
    ...root,
    sourceType: specific.sourceType ?? root.sourceType,
    sourceServerId: specific.sourceServerId ?? root.sourceServerId,
    targetDatabase: asRecord(specific.cdcConfig).targetDatabase ?? root.targetDatabase,
    taskConfig: {
      ...specific,
      alarmType: alarm.alarmType,
      alarmGroup: alarm.alarmGroup,
      parallelism: flink.parallelism,
      checkpointInterval: flink.checkpointIntervalSeconds,
      taskManagerMemory: flink.taskManagerMemoryGb == null ? undefined : `${String(flink.taskManagerMemoryGb)}GB`,
      jobManagerMemory: flink.jobManagerMemoryGb == null ? undefined : `${String(flink.jobManagerMemoryGb)}GB`,
      flinkConfOverrides: flink.flinkConfOverrides,
    },
  };
};

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
  const root = normalizeTaskSnapshot(value);
  const taskConfig = asRecord(root.taskConfig ?? root.config);
  const taskType = String(root.taskType ?? '');
  const computeConfig = asRecord(taskConfig.computeConfig);
  const exportConfig = asRecord(taskConfig.exportConfig);
  const flinkConf = asRecord(root.flinkConf);
  const alarmConfig = asRecord(root.alarmConfig);
  const tableReferences = Array.isArray(root.tableReferences) ? root.tableReferences : [];
  if (taskType === 'compute' || taskType === 'export') {
    return <div className="realtime-instance-config-view">
      <Section title="基础信息">
        <Descriptions bordered size="small" column={2}>
          {item('任务 ID', root.id ?? root.taskId)}{item('任务类型', taskType === 'compute' ? '实时计算' : '实时出仓')}
          {item('任务名称', root.name)}{item('负责人', root.owner)}{item('描述', root.description, 2)}
          {item('状态', root.status)}{item('Flink 版本', root.flinkVersion)}
        </Descriptions>
      </Section>
      <Section title="告警配置"><Descriptions bordered size="small" column={2}>{item('告警类型', alarmConfig.alarmType)}{item('告警组', alarmConfig.alarmGroup)}</Descriptions></Section>
      {taskType === 'compute' ? <Section title="SQL 编辑">
        <Descriptions bordered size="small" column={2}>{item('默认 Paimon 数据库', computeConfig.defaultDatabase, 2)}{item('受管表依赖', tableReferences, 2)}</Descriptions>
        <Typography.Text strong>Flink SQL</Typography.Text>
        <pre className="managed-task-json-detail">{String(computeConfig.sql ?? '') || '-'}</pre>
      </Section> : <Section title="出仓设置">
        <Descriptions bordered size="small" column={2}>
          {item('Paimon 源数据库', exportConfig.sourceDatabase)}{item('目标 MySQL Server ID', exportConfig.targetServerId)}
          {item('受管表依赖', tableReferences, 2)}{item('表映射', exportConfig.mappings, 2)}{item('Sink 参数', exportConfig.sink, 2)}
        </Descriptions>
      </Section>}
      <Section title="资源与运行">
        <Descriptions bordered size="small" column={2}>
          {item('并行度', flinkConf.parallelism)}{item('Checkpoint 周期', flinkConf.checkpointIntervalSeconds == null ? undefined : `${String(flinkConf.checkpointIntervalSeconds)}s`)}
          {item('TaskManager 内存', flinkConf.taskManagerMemoryGb == null ? undefined : `${String(flinkConf.taskManagerMemoryGb)}GB`)}
          {item('JobManager 内存', flinkConf.jobManagerMemoryGb == null ? undefined : `${String(flinkConf.jobManagerMemoryGb)}GB`)}
        </Descriptions>
        {Object.keys(asRecord(flinkConf.flinkConfOverrides)).length > 0 && <div className="realtime-instance-flink-params"><StructuredKeyValueTable value={flinkConf.flinkConfOverrides} /></div>}
      </Section>
    </div>;
  }
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
        {item('flink版本', root.flinkVersion, 2)}
      </Descriptions>
    </Section>
    <Section title="告警配置">
      <Descriptions bordered size="small" column={2}>{item('报警设置类型', taskConfig.alarmType === 'task-failed' ? '任务失败' : taskConfig.alarmType)}{item('告警组', taskConfig.alarmGroup)}</Descriptions>
    </Section>
    {Boolean(taskConfig.startType) && <Section title="启动设置">
      <Descriptions bordered size="small" column={2}>
        {item('启动类型', taskConfig.startType)}
        {item('历史状态', String(taskConfig.startType) === 'direct' ? '直接启动不需要历史状态' : taskConfig.statePath)}
      </Descriptions>
    </Section>}
    <Section title="源端&目标Paimon配置">
      <Descriptions bordered size="small" column={2}>
        {item('源端类型', (root.sourceType ?? taskConfig.sourceType) === 'mysql-cdc' ? 'Mysql CDC' : root.sourceType ?? taskConfig.sourceType)}{item('Server', root.sourceServerName ?? root.sourceServerId ?? taskConfig.sourceServerId)}
        {item('源库', cdc.databaseName, 2)}{item('源表列表', cdc.selectedTables, 2)}
        {item('目标Paimon库', cdc.targetDatabase ?? root.targetDatabase)}{item('目标Paimon表所属域', cdc.domainPrefix)}
        {item('目标Paimon表前缀', cdc.tablePrefix)}{item('目标Paimon表列表', targetTables)}
        {item('目标Paimon表同步元数据列', cdc.metadataColumns, 2)}{item('目标Paimon表类型映射', cdc.typeMappings, 2)}
        {item('整库模式', cdc.mode ?? 'combined', 2)}
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
    {Object.keys(asRecord(cdc.mysqlConfOverrides)).length > 0 && <Section title="Mysql配置"><StructuredKeyValueTable value={cdc.mysqlConfOverrides} /></Section>}
    {Object.keys(asRecord(cdc.tableConfOverrides)).length > 0 && <Section title="目标Paimon表配置"><StructuredKeyValueTable value={cdc.tableConfOverrides} /></Section>}
    <Section title="资源与运行">
      <Descriptions bordered size="small" column={2}>
        {item('并行度', taskConfig.parallelism)}{item('Checkpoint 周期', taskConfig.checkpointInterval ? `${taskConfig.checkpointInterval}s` : undefined)}
        {item('TaskManager 内存', taskConfig.taskManagerMemory)}{item('JobManager 内存', taskConfig.jobManagerMemory)}
      </Descriptions>
      {Object.keys(asRecord(taskConfig.flinkConfOverrides)).length > 0 && <div className="realtime-instance-flink-params"><StructuredKeyValueTable value={taskConfig.flinkConfOverrides} /></div>}
    </Section>
  </div>;
}

const logText = (kind: InstanceInspectorKind | undefined, value: unknown) => {
  const record = asRecord(value);
  const raw = kind === 'startup-log' ? record.startupLog : kind === 'runtime-log' ? record.runtimeLog : value;
  const text = typeof raw === 'string' ? raw : JSON.stringify(raw ?? '', null, 2);
  return text.replace(/\\r\\n/g, '\n').replace(/\\n/g, '\n').replace(/\\t/g, '  ');
};

export default function InstanceInspectorModal({ open, title, kind, value, loading = false, renderConfig, onClose }: Props) {
  const isLog = kind === 'startup-log' || kind === 'runtime-log';
  return <Modal className="realtime-instance-inspector-modal" title={title} open={open} footer={null} width={kind === 'config' ? 1120 : 1050} onCancel={onClose} destroyOnHidden>
    {loading ? <div className="realtime-instance-inspector-loading"><Spin size="large" /></div> : kind === 'config'
      ? renderConfig?.(value) ?? <InstanceConfigView value={value} />
      : isLog ? <pre className="realtime-log-console">{logText(kind, value) || '暂无日志'}</pre> : <StructuredKeyValueTable value={value} />}
  </Modal>;
}
