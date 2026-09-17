import { Descriptions, Empty, Table, Tag, Typography } from 'antd';
import { isValidElement, type ReactNode } from 'react';
import { StructuredKeyValueTable } from './InstanceInspectorModal';
import SyncSectionNav from './SyncSectionNav';
import { syncStartMethodLabel } from './syncStartMethod';
import { MYSQL_METADATA_COLUMN_PREFIX } from '../types';

const asRecord = (value: unknown): Record<string, unknown> => value && typeof value === 'object' && !Array.isArray(value)
  ? value as Record<string, unknown> : {};

const display = (value: unknown): ReactNode => {
  if (value === undefined || value === null || value === '') return '-';
  if (isValidElement(value)) return value;
  if (typeof value === 'boolean') return value ? '是' : '否';
  if (Array.isArray(value)) return value.length
    ? value.map((entry, index) => <Tag key={`${String(entry)}-${index}`}>{String(entry)}</Tag>) : '-';
  if (typeof value === 'object') return <StructuredKeyValueTable value={value} />;
  return String(value);
};

const configRows = (value: unknown) => {
  const entries = Object.entries(asRecord(value));
  if (!entries.length) return '-';
  return <div className="sync-config-param-list">{entries.map(([key, entry]) => <div className="sync-config-param-row" key={key}>
    <span>{key}</span><strong>{display(entry)}</strong>
  </div>)}</div>;
};

/** 将历史任务快照和统一 v1 快照收敛成同一种只读展示结构。 */
const normalize = (value: unknown) => {
  const outer = asRecord(value);
  const nestedConfig = asRecord(outer.config);
  const source = Object.keys(nestedConfig).length ? nestedConfig : outer;
  const nestedTask = asRecord(source.task);
  const root = Object.keys(nestedTask).length ? nestedTask : source;
  const taskConfig = asRecord(root.taskConfig);
  const alarm = asRecord(root.alarmConfig);
  const flink = asRecord(root.flinkConf);
  return {
    root,
    taskConfig,
    cdc: asRecord(taskConfig.cdcConfig),
    alarm: Object.keys(alarm).length ? alarm : taskConfig,
    flink: Object.keys(flink).length ? flink : taskConfig,
  };
};

interface Props {
  value: unknown;
  highlightPaths?: Iterable<string>;
  compareMode?: boolean;
  sourceServerName?: string;
  showNavigation?: boolean;
  navigationPrefix?: string;
}

export default function SyncTaskConfigDetail({ value, highlightPaths, compareMode = false, sourceServerName, showNavigation = false, navigationPrefix = 'sync-instance-config' }: Props) {
  const { root, taskConfig, cdc, alarm, flink } = normalize(value);
  if (!Object.keys(root).length) return <Empty description="暂无配置" />;
  const changedPaths = new Set(highlightPaths ?? []);
  const changed = (...paths: string[]) => paths.some((path) => changedPaths.has(path)
    || [...changedPaths].some((changedPath) => changedPath.startsWith(`${path}.`)));
  const columns = compareMode ? 1 : 2;
  const fullSpan = compareMode ? 1 : 2;
  const selectedTables = Array.isArray(cdc.selectedTables) ? cdc.selectedTables.map(String) : [];
  const configuredTargets = Array.isArray(cdc.targetTableList) ? cdc.targetTableList.map(String) : [];
  const targetTables = configuredTargets.length ? configuredTargets
    : selectedTables.map((table) => `${String(cdc.tablePrefix ?? '')}${table}${String(cdc.tableSuffix ?? '')}`);
  const tableConfigs = asRecord(cdc.tableConfigs);
  const section = (key: string, title: string, content: ReactNode, className = '') => <section id={`${navigationPrefix}-${key}`} className={`realtime-instance-config-section sync-config-anchor-section ${className}`}>
    <Typography.Title level={5}>{title}</Typography.Title>{content}
  </section>;
  const item = (label: string, itemValue: unknown, paths: string[], span = 1) => <Descriptions.Item
    key={label} label={label} span={span} className={changed(...paths) ? 'task-detail-field-changed' : undefined}>
    {display(itemValue)}
  </Descriptions.Item>;

  const navItems = [
    { key: 'basic', label: '基础信息' },
    ...(Boolean(taskConfig.startType) ? [{ key: 'startup', label: '启动设置' }] : []),
    { key: 'alarm', label: '告警配置' },
    { key: 'source', label: '源端与目标' },
    { key: 'private', label: '私有配置' },
    { key: 'runtime', label: '运行与资源' },
  ];

  return <div className={`sync-config-detail-layout${showNavigation && !compareMode ? ' with-navigation' : ''}`}>
    <div className={`task-detail-sections realtime-instance-config-view sync-task-detail-single-page${compareMode ? ' sync-task-detail-compare' : ''}`}>
    {section('basic', '基础信息', <Descriptions bordered size="small" column={columns}>
      {item('任务名称', root.name, ['name'])}{item('负责人', root.owner, ['owner'])}
      {item('描述', root.description, ['description'], fullSpan)}
      {item('Flink 版本', root.flinkVersion, ['flinkVersion'], fullSpan)}
    </Descriptions>)}

    {Boolean(taskConfig.startType) && section('startup', '启动设置', <Descriptions bordered size="small" column={columns}>
      {item('启动方式', syncStartMethodLabel(taskConfig.startType,
        asRecord(cdc.mysqlConfOverrides)['scan.startup.timestamp-millis'],
        asRecord(cdc.mysqlConfOverrides)['scan.startup.mode']), ['taskConfig.startType'])}
      {item('历史状态', taskConfig.startType === 'direct' ? '本次启动未使用恢复点' : taskConfig.statePath, ['taskConfig.statePath'])}
    </Descriptions>)}

    {section('alarm', '告警配置', <Descriptions bordered size="small" column={columns}>
      {item('报警设置类型', alarm.alarmType === 'task-failed' ? '任务失败' : alarm.alarmType, ['alarmConfig.alarmType'])}
      {item('告警组', alarm.alarmGroup, ['alarmConfig.alarmGroup'])}
    </Descriptions>)}

    {section('source', '源端&目标Paimon配置', <>
      <Typography.Title level={5} className="realtime-config-subtitle">公共配置</Typography.Title>
      <Descriptions bordered size="small" column={columns}>
        {item('源端类型', (root.sourceType ?? taskConfig.sourceType) === 'mysql-cdc' ? 'Mysql CDC' : root.sourceType ?? taskConfig.sourceType, ['taskConfig.sourceType'])}
        {item('Server', root.sourceServerName ?? sourceServerName ?? root.sourceServerId ?? taskConfig.sourceServerId, ['taskConfig.sourceServerId'])}
      </Descriptions>
      <Descriptions bordered size="small" column={1} className="realtime-cdc-detail-table">
        {item('源库', cdc.databaseName, ['taskConfig.cdcConfig.databaseName'])}
        {item('源表列表', selectedTables, ['taskConfig.cdcConfig.selectedTables'])}
        {Object.keys(asRecord(cdc.mysqlConfOverrides)).length > 0 && item('Mysql配置', configRows(cdc.mysqlConfOverrides), ['taskConfig.cdcConfig.mysqlConfOverrides'])}
        {item('目标Paimon库', cdc.targetDatabase ?? root.targetDatabase, ['taskConfig.cdcConfig.targetDatabase'])}
        {item('目标Paimon表所属域', cdc.domainPrefix, ['taskConfig.cdcConfig.domainPrefix'])}
        {item('目标Paimon表前缀', cdc.tablePrefix, ['taskConfig.cdcConfig.tablePrefix'])}
        {item('目标Paimon表列表', targetTables, ['taskConfig.cdcConfig.targetTableList'])}
        {item('目标Paimon表同步元数据列', cdc.metadataColumns, ['taskConfig.cdcConfig.metadataColumns'])}
        {item('目标Paimon表同步元数据列前缀', MYSQL_METADATA_COLUMN_PREFIX, ['taskConfig.cdcConfig.metadataColumnPrefix'])}
        {item('目标Paimon表类型映射', cdc.typeMappings, ['taskConfig.cdcConfig.typeMappings'])}
        {Object.keys(asRecord(cdc.tableConfOverrides)).length > 0 && item('目标Paimon表配置', configRows(cdc.tableConfOverrides), ['taskConfig.cdcConfig.tableConfOverrides'])}
        {item('整库模式', cdc.mode ?? 'combined', ['taskConfig.cdcConfig.mode'])}
      </Descriptions>
    </>)}

    {section('private', '私有配置', <Table className="mysql-private-config-table mysql-private-config-readonly" size="small" pagination={false} tableLayout="fixed" rowKey="table" scroll={{ x: 1188, y: 384 }}
      dataSource={selectedTables.map((table, index) => ({ index: index + 1, table, config: asRecord(tableConfigs[table]) }))}
      locale={{ emptyText: '暂无源表' }} columns={[
        { title: '序号', dataIndex: 'index', width: 48, align: 'center' as const },
        { title: '源表', dataIndex: 'table', width: 190, ellipsis: true },
        { title: '计算列', width: 300, ellipsis: true, render: (_: unknown, row: { config: Record<string, unknown> }) => Array.isArray(row.config.computedColumns) && row.config.computedColumns.length ? row.config.computedColumns.join('；') : '未配置' },
        { title: '主键', width: 300, render: (_: unknown, row: { config: Record<string, unknown> }) => Array.isArray(row.config.primaryKeys) && row.config.primaryKeys.length ? row.config.primaryKeys.join(', ') : '继承源表' },
        { title: '分区键', width: 250, render: (_: unknown, row: { config: Record<string, unknown> }) => Array.isArray(row.config.partitionKeys) && row.config.partitionKeys.length ? row.config.partitionKeys.join(', ') : '不分区' },
        { title: '状态', width: 100, align: 'center' as const, render: (_: unknown, row: { config: Record<string, unknown> }) => Object.values(row.config).some((entry) => Array.isArray(entry) && entry.length) ? <Tag color="blue">已覆盖</Tag> : <Tag>继承源表</Tag> },
      ]} />, changed('taskConfig.cdcConfig.tableConfigs') ? 'task-detail-section-changed' : '')}

    {section('runtime', '资源与运行', <Descriptions bordered size="small" column={columns}>
      {item('并行度', flink.parallelism, ['flinkConf.parallelism'])}
      {item('Checkpoint 周期', flink.checkpointIntervalSeconds ?? flink.checkpointInterval, ['flinkConf.checkpointIntervalSeconds'])}
      {item('TaskManager 内存', flink.taskManagerMemoryGb ?? flink.taskManagerMemory, ['flinkConf.taskManagerMemoryGb'])}
      {item('JobManager 内存', flink.jobManagerMemoryGb ?? flink.jobManagerMemory, ['flinkConf.jobManagerMemoryGb'])}
      {Object.keys(asRecord(flink.flinkConfOverrides)).length > 0 && item('Flink 配置', configRows(flink.flinkConfOverrides), ['flinkConf.flinkConfOverrides'], fullSpan)}
    </Descriptions>)}
    </div>
    {showNavigation && !compareMode && <SyncSectionNav prefix={navigationPrefix} items={navItems} />}
  </div>;
}
