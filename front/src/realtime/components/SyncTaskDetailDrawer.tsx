import { CheckOutlined, EyeOutlined, LinkOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { Alert, Button, Descriptions, Drawer, Empty, Input, Modal, Pagination, Select, Space, Table, Tabs, Tag, Typography, message } from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { applySyncSchemaChange, getChangeLogDetail, getInstanceInfo, getSyncProgress, listAlerts, listChangeLogs, listInstances, listMappings, listSyncDirtyRecords, listSyncSchemaChanges, previewSavedSyncTask, resolveSyncDirtyRecord, stopInstance } from '../api';
import type { RealtimeAlert, SyncDirtyRecord, SyncProgressSnapshot, SyncSchemaChange, SyncTask, TaskChangeLog, TaskInstance, TaskMapping } from '../types';
import InstanceInspectorModal, { InstanceConfigView, StructuredKeyValueTable, type InstanceInspectorKind } from './InstanceInspectorModal';
import InstanceLogPanel from './InstanceLogPanel';
import InstanceListToolbar, { type InstanceSearchField, type InstanceSortOrder } from './InstanceListToolbar';
import RealtimeRuntimeMonitor from './RealtimeRuntimeMonitor';
import { availableChangeActions, changeDetailButtonText, normalizeChangeAction } from './changeActions';
import { selectRuntimeInstance } from './runtimeSelection';

interface Props {
  task?: SyncTask;
  loading?: boolean;
  onClose: () => void;
}

const ACTIVE = ['submitting', 'running', 'stopping', 'restarting'];
const statusLabel: Record<string, string> = {
  submitting: '提交中', running: '运行中', debug_success_running: '运行中(调试成功)', stopping: '停止中', restarting: '重启中',
  canceled: '已取消', killed_success: '已停止(调试成功)', finished: '已完成', failed: '失败', not_running: '未运行',
};
const statusColor: Record<string, string> = {
  submitting: 'processing', running: 'success', debug_success_running: 'success', stopping: 'warning', restarting: 'processing',
  canceled: 'default', killed_success: 'success', finished: 'success', failed: 'error', not_running: 'default',
};

const displayValue = (input: unknown) => {
  if (input === undefined || input === null || input === '') return '-';
  if (typeof input === 'object') return JSON.stringify(input);
  return String(input);
};

const objectValue = (input: unknown) => input && typeof input === 'object' && !Array.isArray(input)
  ? input as Record<string, unknown> : {};

const stopMethodLabels: Record<string, string> = {
  flink_cancel: 'Flink cancel', flink_savepoint: 'Flink savepoint 停止',
  yarn_kill: 'YARN application kill', none: '无活动实例',
};

const ChangeDetailContent = ({ detail }: { detail: Record<string, unknown> }) => {
  const kind = String(detail.detailKind ?? '').toLowerCase();
  const operation = objectValue(detail.operation);
  const request = objectValue(operation.request);
  const result = objectValue(operation.result);
  const instance = objectValue(detail.instance);
  if (kind === 'edit') {
    const diffs = Array.isArray(detail.diffs) ? detail.diffs as Record<string, unknown>[] : [];
    return <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <section><Typography.Title level={5}>本次变更字段</Typography.Title><Table rowKey={(row) => String(row.path)} size="small" pagination={false} dataSource={diffs} locale={{ emptyText: '配置内容无差异' }} columns={[
        { title: '配置项', dataIndex: 'label', width: 230 },
        { title: '变更前', dataIndex: 'beforeValue', render: displayValue },
        { title: '变更后', dataIndex: 'afterValue', render: displayValue },
      ]} /></section>
      <div className="realtime-change-compare"><section><Typography.Title level={5}>变更前完整配置</Typography.Title>{detail.beforeTask ?? detail.beforeConfig ? <InstanceConfigView value={detail.beforeTask ?? detail.beforeConfig} /> : <Empty description="缺少变更前版本" />}</section><section><Typography.Title level={5}>变更后完整配置</Typography.Title>{detail.afterTask ?? detail.afterConfig ? <InstanceConfigView value={detail.afterTask ?? detail.afterConfig} /> : <Empty description="缺少变更后版本" />}</section></div>
    </Space>;
  }
  if (kind === 'start') return <Space direction="vertical" size={16} style={{ width: '100%' }}>
    <Descriptions bordered size="small" column={2}>
      <Descriptions.Item label="启动类型">{displayValue(request.startType ?? result.startType)}</Descriptions.Item>
      <Descriptions.Item label="实例 ID">{displayValue(detail.taskInstanceId ?? instance.id)}</Descriptions.Item>
      <Descriptions.Item label="实例状态">{displayValue(instance.status)}</Descriptions.Item>
      <Descriptions.Item label="JobID">{displayValue(result.jobId ?? instance.jobId)}</Descriptions.Item>
      <Descriptions.Item label="历史状态" span={2}>{displayValue(request.statePath)}</Descriptions.Item>
      <Descriptions.Item label="YARN Application ID" span={2}>{displayValue(result.yarnApplicationId ?? instance.yarnApplicationId)}</Descriptions.Item>
    </Descriptions>
    {detail.afterTask ? <InstanceConfigView value={detail.afterTask} /> : <Empty description="缺少启动使用参数" />}
  </Space>;
  if (kind === 'stop') return <Space direction="vertical" size={16} style={{ width: '100%' }}>
    <Descriptions bordered size="small" column={2}>
      <Descriptions.Item label="停止类型">{displayValue(result.stopType ?? request.stopType)}</Descriptions.Item>
      <Descriptions.Item label="实际方式">{stopMethodLabels[String(result.actualMethod ?? '')] ?? displayValue(result.actualMethod)}</Descriptions.Item>
      <Descriptions.Item label="Fallback 到 YARN kill">{result.fallbackToYarnKill ? '是' : '否'}</Descriptions.Item>
      <Descriptions.Item label="最终实例状态">{displayValue(result.instanceStatus ?? instance.status)}</Descriptions.Item>
      <Descriptions.Item label="操作状态">{displayValue(operation.operationStatus)}</Descriptions.Item>
      <Descriptions.Item label="完成时间">{displayValue(operation.endTime)}</Descriptions.Item>
      <Descriptions.Item label="JobID" span={2}>{displayValue(result.jobId ?? instance.jobId)}</Descriptions.Item>
      <Descriptions.Item label="YARN Application ID" span={2}>{displayValue(result.yarnApplicationId ?? instance.yarnApplicationId)}</Descriptions.Item>
      <Descriptions.Item label="Savepoint" span={2}>{displayValue(result.savepointPath ?? instance.savepointPath)}</Descriptions.Item>
    </Descriptions>
    {Boolean(operation.errorMessage) && <Alert type="error" showIcon message={displayValue(operation.errorMessage)} />}
  </Space>;
  if (kind === 'create') return detail.afterTask ? <InstanceConfigView value={detail.afterTask} /> : <Empty description="缺少创建版本" />;
  return <StructuredKeyValueTable value={detail} />;
};

export default function SyncTaskDetailDrawer({ task, loading, onClose }: Props) {
  const [instances, setInstances] = useState<TaskInstance[]>([]);
  const [mappings, setMappings] = useState<TaskMapping[]>([]);
  const [changes, setChanges] = useState<TaskChangeLog[]>([]);
  const [alerts, setAlerts] = useState<RealtimeAlert[]>([]);
  const [dataLoading, setDataLoading] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState('all');
  const [searchField, setSearchField] = useState<InstanceSearchField>('all');
  const [sortOrder, setSortOrder] = useState<InstanceSortOrder>('startedAtDesc');
  const [changeAction, setChangeAction] = useState('all');
  const [changeKeyword, setChangeKeyword] = useState('');
  const [logInstance, setLogInstance] = useState<TaskInstance>();
  const [mappingOpen, setMappingOpen] = useState(false);
  const [mappingInstance, setMappingInstance] = useState<TaskInstance>();
  const [instanceMappings, setInstanceMappings] = useState<TaskMapping[]>([]);
  const [stoppingInstanceId, setStoppingInstanceId] = useState<number>();
  const [inspector, setInspector] = useState<{ title: string; kind?: InstanceInspectorKind; value: unknown }>();
  const [changeDetail, setChangeDetail] = useState<Record<string, unknown>>();
  const [changeDetailLoadingId, setChangeDetailLoadingId] = useState<number>();
  const [activeDrawerTab, setActiveDrawerTab] = useState('instances');
  const [detailCommand, setDetailCommand] = useState('');
  const [detailCommandLoading, setDetailCommandLoading] = useState(false);
  const [syncProgress, setSyncProgress] = useState<SyncProgressSnapshot>();
  const [dirtyRecords, setDirtyRecords] = useState<SyncDirtyRecord[]>([]);
  const [dirtyTotal, setDirtyTotal] = useState(0);
  const [dirtyPageNo, setDirtyPageNo] = useState(1);
  const [unresolvedOnly, setUnresolvedOnly] = useState(true);
  const [schemaChanges, setSchemaChanges] = useState<SyncSchemaChange[]>([]);
  const [dataLinkLoading, setDataLinkLoading] = useState(false);
  const [handlingId, setHandlingId] = useState<number>();
  const requestSequenceRef = useRef(0);

  const reload = useCallback(async (silent = false) => {
    if (!task) return;
    const sequence = ++requestSequenceRef.current;
    if (!silent) setDataLoading(true);
    try {
      const [allInstances, taskMappings, taskChanges, allAlerts] = await Promise.all([
        listInstances(task.id), listMappings(task.id), listChangeLogs(task.id), listAlerts(),
      ]);
      const [dirtyPage, schemaEvents] = await Promise.all([
        listSyncDirtyRecords(task.id, unresolvedOnly, dirtyPageNo, 20).catch(() => undefined),
        listSyncSchemaChanges(task.id).catch(() => undefined),
      ]);
      if (sequence !== requestSequenceRef.current) return;
      const production = allInstances.filter((item) => item.executionMode !== 'DEBUG');
      setInstances(production);
      setMappings(taskMappings);
      setChanges(taskChanges);
      setAlerts(allAlerts.filter((item) => item.taskId === task.id));
      if (dirtyPage) { setDirtyRecords(dirtyPage.items); setDirtyTotal(dirtyPage.total); }
      if (schemaEvents) setSchemaChanges(schemaEvents);
      const selected = selectRuntimeInstance(production);
      if (selected) {
        try { setSyncProgress(await getSyncProgress(task.id, selected.id, ACTIVE.includes(selected.status))); }
        catch { setSyncProgress(undefined); }
      } else setSyncProgress(undefined);
    } catch (error) { if (sequence === requestSequenceRef.current) message.error((error as Error).message); }
    finally { if (!silent && sequence === requestSequenceRef.current) setDataLoading(false); }
  }, [dirtyPageNo, task, unresolvedOnly]);

  useEffect(() => {
    if (!task) return;
    setKeyword(''); setStatus('all'); setSearchField('all'); setSortOrder('startedAtDesc'); setChangeAction('all'); setChangeKeyword(''); setLogInstance(undefined); setDetailCommand(''); setSyncProgress(undefined); setUnresolvedOnly(true); setDirtyPageNo(1); setActiveDrawerTab('instances');
  }, [task?.id]);

  useEffect(() => { void reload(); }, [reload]);

  useEffect(() => {
    if (!task || !instances.some((item) => ACTIVE.includes(item.status))) return undefined;
    const timer = window.setInterval(() => { void reload(true); }, 3000);
    return () => window.clearInterval(timer);
  }, [instances, reload, task]);

  useEffect(() => {
    if (!logInstance) return;
    const latest = instances.find((item) => item.id === logInstance.id);
    if (latest && latest !== logInstance) setLogInstance(latest);
  }, [instances, logInstance]);

  const filteredInstances = useMemo(() => instances.filter((item) => {
    if (status !== 'all' && item.status !== status) return false;
    if (!keyword.trim()) return true;
    const value = searchField === 'all'
      ? [item.id, item.jobId, item.yarnApplicationId, item.failureMessage].join(' ')
      : String(item[searchField] ?? '');
    return value.toLowerCase().includes(keyword.trim().toLowerCase());
  }).sort((left, right) => {
    if (sortOrder === 'idAsc' || sortOrder === 'idDesc') return (left.id - right.id) * (sortOrder === 'idAsc' ? 1 : -1);
    const comparison = String(left.startedAt ?? left.createTime).localeCompare(String(right.startedAt ?? right.createTime));
    return comparison * (sortOrder === 'startedAtAsc' ? 1 : -1);
  }), [instances, keyword, searchField, sortOrder, status]);

  const filteredChanges = useMemo(() => {
    const search = changeKeyword.trim().toLowerCase();
    return changes.filter((item) => {
      if (changeAction !== 'all' && normalizeChangeAction(item.action) !== changeAction) return false;
      if (!search) return true;
      return [item.operator, normalizeChangeAction(item.action), item.detail, item.summary]
        .join(' ').toLowerCase().includes(search);
    });
  }, [changeAction, changeKeyword, changes]);

  const changeActionOptions = useMemo(() => availableChangeActions(changes.map((item) => item.action))
    .map((value) => ({ label: value, value })), [changes]);

  const inspect = async (instance: TaskInstance, kind: InstanceInspectorKind) => {
    if (!task) return;
    try {
      const names: Record<InstanceInspectorKind, string> = { config: '实例配置', 'startup-log': '启动日志', 'runtime-log': '运行日志', runtime: '运行指标', resources: '资源', checkpoints: 'Checkpoint', 'log-components': '日志组件' };
      setInspector({ title: kind === 'config' ? `实例配置 - 实例 ${instance.id}` : `实例 ${instance.id} - ${names[kind]}`, kind, value: await getInstanceInfo(task.id, instance.id, kind) });
    } catch (error) { message.error((error as Error).message); }
  };

  const showChangeDetail = async (row: TaskChangeLog) => {
    try {
      setChangeDetailLoadingId(row.id);
      setChangeDetail(await getChangeLogDetail(row.id));
    } catch (error) {
      message.error((error as Error).message);
    } finally {
      setChangeDetailLoadingId(undefined);
    }
  };

  const showInstanceMappings = async (instance: TaskInstance) => {
    if (!task) return;
    try {
      const snapshot = objectValue(await getInstanceInfo(task.id, instance.id, 'config'));
      const taskConfig = objectValue(snapshot.taskConfig);
      const cdc = objectValue(taskConfig.cdcConfig);
      const selected = Array.isArray(cdc.selectedTables) ? cdc.selectedTables.map(String) : [];
      const targets = Array.isArray(cdc.targetTableList) ? cdc.targetTableList.map(String) : [];
      const prefix = String(cdc.tablePrefix ?? '');
      const suffix = String(cdc.tableSuffix ?? '');
      const sourceDatabase = String(cdc.databaseName ?? task.taskConfig.cdcConfig.databaseName ?? '');
      const targetDatabase = String(cdc.targetDatabase ?? task.targetDatabase ?? '');
      setInstanceMappings(selected.map((sourceTable, index) => ({
        id: index + 1, sourceDatabase, sourceTable, targetDatabase,
        targetTable: targets[index] || `${prefix}${sourceTable}${suffix}`, sortOrder: index,
      })));
      setMappingInstance(instance); setMappingOpen(true);
    } catch (error) { message.error((error as Error).message); }
  };

  const stopProductionInstance = (instance: TaskInstance) => {
    if (!task || !instance.managed) return;
    Modal.confirm({
      title: `确认停止实例 ${instance.id}？`,
      content: '将执行 Stop-with-Savepoint，成功保存状态后停止正式实例。',
      okText: '停止', cancelText: '取消', okButtonProps: { danger: true },
      onOk: async () => {
        try { setStoppingInstanceId(instance.id); await stopInstance(task.id, instance.id, 'savepoint'); message.success('停止请求已提交'); await reload(); }
        catch (error) { message.error((error as Error).message); }
        finally { setStoppingInstanceId(undefined); }
      },
    });
  };

  const refreshSchemaChanges = async () => {
    if (!task) return;
    try { setDataLinkLoading(true); setSchemaChanges(await listSyncSchemaChanges(task.id, true)); message.success('Schema 差异检测完成'); }
    catch (error) { message.error((error as Error).message); }
    finally { setDataLinkLoading(false); }
  };

  const resolveDirty = async (record: SyncDirtyRecord) => {
    if (!task) return;
    try {
      setHandlingId(record.id); await resolveSyncDirtyRecord(task.id, record.id);
      const page = await listSyncDirtyRecords(task.id, unresolvedOnly, dirtyPageNo, 20); setDirtyRecords(page.items); setDirtyTotal(page.total);
      message.success('已标记为处理完成');
    } catch (error) { message.error((error as Error).message); }
    finally { setHandlingId(undefined); }
  };

  const applySchema = (event: SyncSchemaChange) => {
    if (!task) return;
    Modal.confirm({ title: '应用新增字段？', content: '仅向受管 Paimon 表追加字段，不会删除字段或自动修改不兼容类型。', okText: '应用', cancelText: '取消',
      onOk: async () => { try { setHandlingId(event.id); await applySyncSchemaChange(task.id, event.id); setSchemaChanges(await listSyncSchemaChanges(task.id)); message.success('Schema 变更已应用'); } catch (error) { message.error((error as Error).message); } finally { setHandlingId(undefined); } },
    });
  };

  const renderChangeSummary = (value: string, row: TaskChangeLog) => {
    const summary = row.summary || value || `${row.operator} 执行 ${normalizeChangeAction(row.action)}`;
    const canOpen = row.detailKind !== 'text'
      && Boolean(row.beforeVersionId || row.afterVersionId || row.operationId || row.taskInstanceId);
    if (!canOpen) return summary;
    return <Button className="task-change-detail-button" type="link" size="small"
      loading={changeDetailLoadingId === row.id} onClick={() => void showChangeDetail(row)}>
      {changeDetailButtonText(row.action)}
    </Button>;
  };

  if (!task) return null;
  const runtimeInstance = selectRuntimeInstance(instances);
  const snapshotFinished = syncProgress?.snapshotFinished;
  const snapshotRemaining = syncProgress?.snapshotRemaining;
  const snapshotTotal = snapshotFinished != null && snapshotRemaining != null ? snapshotFinished + snapshotRemaining : undefined;
  const snapshotPercent = snapshotTotal && snapshotFinished != null ? Math.round(snapshotFinished / snapshotTotal * 100) : undefined;
  const instanceContent = logInstance ? (
    <InstanceLogPanel taskId={task.id} instance={logInstance} backLabel="返回实例列表" onBack={() => setLogInstance(undefined)} />
  ) : <>
    <InstanceListToolbar keyword={keyword} searchField={searchField} status={status} sortOrder={sortOrder}
      statusOptions={[{ label: '全部状态', value: 'all' }, ...Object.entries(statusLabel).map(([value, label]) => ({ value, label }))]}
      loading={dataLoading} refreshLabel="刷新实例" onKeywordChange={setKeyword} onSearchFieldChange={setSearchField}
      onStatusChange={setStatus} onSortOrderChange={setSortOrder}
      onReset={() => { setKeyword(''); setSearchField('all'); setStatus('all'); setSortOrder('startedAtDesc'); }} onRefresh={() => void reload()} />
    <Table rowKey="id" size="small" loading={dataLoading} dataSource={filteredInstances} pagination={{ pageSize: 6, showSizeChanger: true, pageSizeOptions: [6, 10, 20], showTotal: (value) => `共 ${value} 条` }} locale={{ emptyText: '暂无运行实例' }} scroll={{ x: 1700 }} columns={[
      { title: '实例 ID', dataIndex: 'id', width: 100, fixed: 'left' },
      { title: '状态', dataIndex: 'status', width: 120, fixed: 'left', render: (value: string) => <Tag color={statusColor[value]}>{statusLabel[value] ?? value}</Tag> },
      { title: 'JobID', dataIndex: 'jobId', width: 270, ellipsis: true, render: (value: string, row: TaskInstance) => value || (ACTIVE.includes(row.status) ? '同步中' : '-') },
      { title: 'YARN Application ID', dataIndex: 'yarnApplicationId', width: 220, ellipsis: true, render: (value: string, row: TaskInstance) => value || (ACTIVE.includes(row.status) ? '提交中' : '-') },
      { title: 'Flink UI', dataIndex: 'trackingUrl', width: 120, render: (value: string, row: TaskInstance) => value ? <Typography.Link href={value} target="_blank"><LinkOutlined /> 打开</Typography.Link> : (ACTIVE.includes(row.status) ? '同步中' : '-') },
      { title: 'Savepoint', dataIndex: 'savepointPath', width: 250, ellipsis: true, render: (value: string, row: TaskInstance) => value || (ACTIVE.includes(row.status) ? '停止后生成' : '-') },
      { title: '失败原因', dataIndex: 'failureMessage', width: 240, ellipsis: true, render: (value: string, row: TaskInstance) => row.status === 'failed' ? (value || '请查看运行日志') : '-' },
      { title: '开始时间', dataIndex: 'startedAt', width: 180, render: (value: string) => value || '-' },
      { title: '结束时间', dataIndex: 'endedAt', width: 180, render: (value: string, row: TaskInstance) => value || (ACTIVE.includes(row.status) ? '运行中' : '-') },
      { title: '操作', fixed: 'right', width: 270, render: (_: unknown, row: TaskInstance) => <Space size={12}><Typography.Link onClick={() => void inspect(row, 'config')}>实例配置</Typography.Link><Typography.Link onClick={() => setLogInstance(row)}>日志</Typography.Link><Typography.Link onClick={() => void showInstanceMappings(row)}>同步表</Typography.Link>{ACTIVE.includes(row.status) && <Typography.Link type="danger" disabled={!row.managed || stoppingInstanceId === row.id} onClick={() => stopProductionInstance(row)}>停止</Typography.Link>}</Space> },
    ]} />
  </>;

  return <>
    <Drawer className="sync-task-detail-drawer" title={task.name} open onClose={onClose} placement="bottom" height="72vh" loading={loading}>
      <Tabs className="ui-flat-tabs" activeKey={activeDrawerTab} onChange={setActiveDrawerTab} items={[
        { key: 'instances', label: '实例列表', children: instanceContent },
        { key: 'detail', label: '任务详情', children: <div className="task-detail-sections"><InstanceConfigView value={task} /><section className="realtime-instance-config-section"><Typography.Title level={5}>命令预览</Typography.Title><div className="task-command-preview"><Button type="link" icon={<EyeOutlined />} loading={detailCommandLoading} onClick={async () => { try { setDetailCommandLoading(true); setDetailCommand((await previewSavedSyncTask(task.id)).command); } catch (error) { message.error((error as Error).message); } finally { setDetailCommandLoading(false); } }}>预览</Button><Input.TextArea className="task-command-preview-textarea" value={detailCommand} placeholder="点击预览生成 Paimon Action 等价命令" readOnly rows={10} wrap="off" /></div></section></div> },
        { key: 'runtime', label: '运行监控', children: <RealtimeRuntimeMonitor taskId={task.id} instance={runtimeInstance} active={activeDrawerTab === 'runtime'} checkpointIntervalSeconds={task.taskConfig.checkpointInterval} /> },
        { key: 'data-link', label: '数据链路', children: <div className="sync-data-link-panel">
          <div className="sync-link-summary">
            <div><span>当前实例</span><strong>{runtimeInstance?.id ?? '-'}</strong></div>
            <div><span>全量快照</span><strong>{snapshotPercent == null ? '-' : `${snapshotPercent}%`}</strong></div>
            <div><span>源端延迟</span><strong>{syncProgress?.sourceLagMs == null ? '-' : `${syncProgress.sourceLagMs} ms`}</strong></div>
            <div><span>未处理脏数据</span><strong>{dirtyTotal}</strong></div>
          </div>
          <Tabs className="ui-flat-tabs" items={[
            { key: 'dirty', label: `脏数据（${dirtyTotal}）`, children: <><div className="realtime-detail-toolbar"><Space><Select value={unresolvedOnly ? 'unresolved' : 'all'} onChange={(value) => { setUnresolvedOnly(value === 'unresolved'); setDirtyPageNo(1); }} options={[{ label: '仅未处理', value: 'unresolved' }, { label: '全部记录', value: 'all' }]} /><Button icon={<ReloadOutlined />} loading={dataLoading} onClick={() => void reload()}>刷新</Button></Space></div><Table<SyncDirtyRecord> rowKey="id" size="small" loading={dataLoading} dataSource={dirtyRecords} pagination={false} scroll={{ x: 1280 }} locale={{ emptyText: '暂无脏数据记录' }} columns={[
              { title: '时间', dataIndex: 'createTime', width: 180 }, { title: '实例', dataIndex: 'taskInstanceId', width: 90, render: displayValue },
              { title: '源表', width: 260, render: (_, row) => [row.sourceDatabase, row.sourceTable].filter(Boolean).join('.') || '-' },
              { title: '操作', dataIndex: 'operationType', width: 100, render: displayValue }, { title: '主键', dataIndex: 'primaryKeyValue', width: 160, ellipsis: true, render: displayValue },
              { title: '错误', dataIndex: 'errorMessage', ellipsis: true }, { title: '状态', dataIndex: 'resolved', width: 100, render: (value) => value ? <Tag color="success">已处理</Tag> : <Tag color="error">未处理</Tag> },
              { title: '操作', fixed: 'right', width: 170, render: (_, row) => <Space><Typography.Link onClick={() => setInspector({ title: `脏数据 #${row.id}`, value: row })}>查看</Typography.Link>{!row.resolved && <Button type="link" size="small" icon={<CheckOutlined />} loading={handlingId === row.id} onClick={() => void resolveDirty(row)}>标记处理</Button>}</Space> },
            ]} /><Pagination size="small" current={dirtyPageNo} pageSize={20} total={dirtyTotal} showTotal={(value) => `共 ${value} 条`} onChange={setDirtyPageNo} /></> },
            { key: 'schema', label: `Schema 演进（${schemaChanges.filter((item) => item.status !== 'APPLIED').length}）`, children: <><div className="realtime-detail-toolbar"><Typography.Text type="secondary">自动识别安全新增字段；删列和不兼容类型必须人工处理。</Typography.Text><Button icon={<ReloadOutlined />} loading={dataLinkLoading} onClick={() => void refreshSchemaChanges()}>检测差异</Button></div><Table<SyncSchemaChange> rowKey="id" size="small" loading={dataLinkLoading} dataSource={schemaChanges} pagination={false} scroll={{ x: 1200 }} locale={{ emptyText: '暂无 Schema 变更' }} columns={[
              { title: '检测时间', dataIndex: 'detectedAt', width: 180 }, { title: '源表', width: 240, render: (_, row) => `${row.sourceDatabase}.${row.sourceTable}` }, { title: '目标表', width: 260, render: (_, row) => `${row.targetDatabase}.${row.targetTable}` },
              { title: '变更类型', dataIndex: 'changeType', width: 150, render: (value) => value === 'ADD_COLUMNS' ? '新增字段' : value === 'INCOMPATIBLE' ? '类型不兼容' : value },
              { title: '说明', dataIndex: 'message' }, { title: '状态', dataIndex: 'status', width: 110, render: (value) => <Tag color={value === 'APPLIED' ? 'success' : value === 'BLOCKED' ? 'error' : 'processing'}>{value === 'APPLIED' ? '已应用' : value === 'BLOCKED' ? '需人工处理' : '待应用'}</Tag> },
              { title: '操作', fixed: 'right', width: 150, render: (_, row) => <Space><Typography.Link onClick={() => setInspector({ title: `Schema 变更 #${row.id}`, value: row.change })}>查看</Typography.Link>{row.status === 'PENDING' && row.changeType === 'ADD_COLUMNS' && <Button type="link" size="small" loading={handlingId === row.id} onClick={() => applySchema(row)}>应用</Button>}</Space> },
            ]} /></> },
          ]} />
        </div> },
        { key: 'alerts', label: '告警记录', children: <Table rowKey="id" size="small" dataSource={alerts} locale={{ emptyText: '暂无告警记录' }} columns={[{ title: '级别', dataIndex: 'severity', width: 100, render: (value: string) => <Tag color={value === 'critical' ? 'red' : 'orange'}>{value}</Tag> }, { title: '标题', dataIndex: 'title', width: 240 }, { title: '详情', dataIndex: 'detail' }, { title: '时间', dataIndex: 'createTime', width: 180 }]} /> },
        { key: 'changes', label: '变更记录', children: <><div className="realtime-detail-toolbar"><Space><Select showSearch optionFilterProp="label" value={changeAction} onChange={setChangeAction} options={[{ label: '全部操作', value: 'all' }, ...changeActionOptions]} /><Input allowClear prefix={<SearchOutlined />} value={changeKeyword} onChange={(event) => setChangeKeyword(event.target.value)} placeholder="搜索操作人 / 操作类型 / 变更说明" /></Space></div><Table rowKey="id" size="small" dataSource={filteredChanges} pagination={false} locale={{ emptyText: changeKeyword.trim() || changeAction !== 'all' ? '没有匹配的变更记录' : '暂无变更记录' }} columns={[{ title: '操作时间', dataIndex: 'createTime', width: 180 }, { title: '操作人', dataIndex: 'operator', width: 120 }, { title: '操作类型', dataIndex: 'action', width: 150, render: (value: string) => normalizeChangeAction(value) }, { title: '变更明细', dataIndex: 'detail', render: renderChangeSummary }]} /></> },
      ]} />
    </Drawer>
    <Modal className="sync-mapping-modal" title={`同步表映射${mappingInstance ? ` - 实例 ${mappingInstance.id}` : ''}`} open={mappingOpen} footer={null} width={900} destroyOnHidden onCancel={() => { setMappingOpen(false); setMappingInstance(undefined); }}><Table rowKey="id" size="small" pagination={false} scroll={{ y: 480 }} locale={{ emptyText: '暂无同步表映射' }} dataSource={mappingInstance ? instanceMappings : mappings} columns={[{ title: '序号', width: 72, render: (_: unknown, __: TaskMapping, index: number) => index + 1 }, { title: '源表', width: 360, render: (_: unknown, row: TaskMapping) => <span className="sync-mapping-full-name">{`${row.sourceDatabase}.${row.sourceTable}`}</span> }, { title: '目标 Paimon 表', render: (_: unknown, row: TaskMapping) => <span className="sync-mapping-full-name">{`${row.targetDatabase}.${row.targetTable}`}</span> }]} /></Modal>
    <Modal className="realtime-change-detail-modal" title={String(changeDetail?.detailKind ?? '').toLowerCase() === 'edit' ? '任务配置变更' : '变更记录详情'} open={Boolean(changeDetail)} footer={null} width={String(changeDetail?.detailKind ?? '').toLowerCase() === 'edit' ? 1500 : 1050} onCancel={() => setChangeDetail(undefined)}>
      {changeDetail && <ChangeDetailContent detail={changeDetail} />}
    </Modal>
    <InstanceInspectorModal open={Boolean(inspector)} title={inspector?.title} kind={inspector?.kind} value={inspector?.value} onClose={() => setInspector(undefined)} />
  </>;
}
