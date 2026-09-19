import { CheckOutlined, EyeOutlined, ReloadOutlined } from '@ant-design/icons';
import { Alert, Button, Descriptions, Drawer, Empty, Input, Modal, Pagination, Select, Space, Spin, Table, Tabs, Tag, Typography, message } from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { applySyncSchemaChange, getChangeLogDetail, getInstanceInfo, getSyncProgress, getVersionConfig, listAlerts, listChangeLogs, listInstances, listSyncDirtyRecords, listSyncSchemaChanges, listVersions, previewSavedSyncTask, resolveSyncDirtyRecord, stopInstance } from '../api';
import type { RealtimeAlert, SyncDirtyRecord, SyncProgressSnapshot, SyncSchemaChange, SyncTask, TaskChangeLog, TaskInstance, TaskMapping } from '../types';
import InstanceInspectorModal, { type InstanceInspectorKind } from './InstanceInspectorModal';
import RealtimeRuntimeMonitor from './RealtimeRuntimeMonitor';
import RealtimeDiagnosticPanel from './RealtimeDiagnosticPanel';
import RealtimeRecoveryModal from './RealtimeRecoveryModal';
import { normalizeChangeAction } from './changeActions';
import { selectRuntimeInstance } from './runtimeSelection';
import SyncTaskConfigDetail from './SyncTaskConfigDetail';
import { syncStartMethodLabel } from './syncStartMethod';
import RealtimeTaskDetailDrawer from './RealtimeTaskDetailDrawer';
import RealtimeAlertTable from './RealtimeAlertTable';
import RealtimeInstanceStopModal from './RealtimeInstanceStopModal';
import RealtimeInstanceList from './RealtimeInstanceList';
import RealtimeChangeLogTable from './RealtimeChangeLogTable';
import { analyzeAssetImpact } from '../../api/assets';
import { ImpactSummary } from './RealtimeTableLifecyclePanels';

interface Props {
  task?: SyncTask;
  loading?: boolean;
  initialTab?: string;
  onClose: () => void;
}

const ACTIVE = ['submitting', 'running', 'stopping', 'restarting'];
const STOPPABLE = ['submitting', 'running', 'restarting'];
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

export const schemaImpactColumns = (event: SyncSchemaChange) => [
  ...(event.change?.addColumns ?? []).map((column) => column.name),
  ...(event.change?.incompatibleColumns ?? []).map((column) => String(column.column ?? column.name ?? column.columnName ?? '')).filter(Boolean),
];

const objectValue = (input: unknown) => input && typeof input === 'object' && !Array.isArray(input)
  ? input as Record<string, unknown> : {};

const stopMethodLabels: Record<string, string> = {
  flink_cancel: 'Flink cancel', flink_savepoint: 'Flink savepoint 停止',
  yarn_kill: 'YARN application kill', none: '无活动实例',
};

export const ChangeDetailContent = ({ detail, sourceServerName }: { detail: Record<string, unknown>; sourceServerName?: string }) => {
  const kind = String(detail.detailKind ?? '').toLowerCase();
  const operation = objectValue(detail.operation);
  const request = objectValue(operation.request);
  const result = objectValue(operation.result);
  const instance = objectValue(detail.instance);
  if (kind === 'edit') {
    const diffs = Array.isArray(detail.diffs) ? detail.diffs as Record<string, unknown>[] : [];
    const changedPaths = diffs.map((diff) => String(diff.path ?? '')).filter(Boolean);
    return <div className="task-change-detail-split">
      <section className="task-change-detail-pane"><Typography.Title level={5}>变更前</Typography.Title>
        {detail.beforeConfig ? <SyncTaskConfigDetail value={detail.beforeConfig} highlightPaths={changedPaths} compareMode sourceServerName={sourceServerName} /> : <Empty description="缺少变更前版本" />}
      </section>
      <section className="task-change-detail-pane"><Typography.Title level={5}>变更后</Typography.Title>
        {detail.afterConfig ? <SyncTaskConfigDetail value={detail.afterConfig} highlightPaths={changedPaths} compareMode sourceServerName={sourceServerName} /> : <Empty description="缺少变更后版本" />}
      </section>
    </div>;
  }
  if (kind === 'start') return <Space direction="vertical" size={16} style={{ width: '100%' }}>
    <Descriptions bordered size="small" column={2} className="task-change-operation-descriptions">
      <Descriptions.Item label="启动方式">{syncStartMethodLabel(
        request.startType ?? result.startType,
        request.sourceStartupTimestampMillis ?? result.sourceStartupTimestampMillis,
      )}</Descriptions.Item>
      <Descriptions.Item label="实例 ID">{displayValue(detail.taskInstanceId ?? instance.id)}</Descriptions.Item>
      <Descriptions.Item label="实例状态"><Tag color={statusColor[String(instance.status ?? '')]}>{statusLabel[String(instance.status ?? '')] ?? displayValue(instance.status)}</Tag></Descriptions.Item>
      <Descriptions.Item label="JobID">{displayValue(result.jobId ?? instance.jobId)}</Descriptions.Item>
      <Descriptions.Item label="历史状态" span={2}>{displayValue(request.statePath)}</Descriptions.Item>
      <Descriptions.Item label="YARN Application ID" span={2}>{displayValue(result.yarnApplicationId ?? instance.yarnApplicationId)}</Descriptions.Item>
    </Descriptions>
    {detail.afterConfig ? <SyncTaskConfigDetail value={detail.afterConfig} sourceServerName={sourceServerName} /> : <Empty description="缺少启动使用参数" />}
  </Space>;
  if (kind === 'stop') return <Space direction="vertical" size={16} style={{ width: '100%' }}>
    <Descriptions bordered size="small" column={2} className="task-change-operation-descriptions">
      <Descriptions.Item label="停止类型">{displayValue(result.stopType ?? request.stopType)}</Descriptions.Item>
      <Descriptions.Item label="实际方式">{stopMethodLabels[String(result.actualMethod ?? '')] ?? displayValue(result.actualMethod)}</Descriptions.Item>
      <Descriptions.Item label="Fallback 到 YARN kill">{result.fallbackToYarnKill ? '是' : '否'}</Descriptions.Item>
      <Descriptions.Item label="最终实例状态">{displayValue(result.instanceStatus ?? instance.status)}</Descriptions.Item>
      <Descriptions.Item label="操作状态">{displayValue(operation.operationStatus)}</Descriptions.Item>
      <Descriptions.Item label="完成时间">{displayValue(operation.endTime)}</Descriptions.Item>
      <Descriptions.Item label="JobID" span={2}>{displayValue(result.jobId ?? instance.jobId)}</Descriptions.Item>
      <Descriptions.Item label="YARN Application ID" span={2}>{displayValue(result.yarnApplicationId ?? instance.yarnApplicationId)}</Descriptions.Item>
      <Descriptions.Item label="Savepoint" span={2}>{displayValue(result.savepointPath ?? instance.savepointPath)}</Descriptions.Item>
      <Descriptions.Item label="退出码">{displayValue(result.exitCode)}</Descriptions.Item>
      <Descriptions.Item label="执行命令" span={2}><Typography.Text code copyable>{displayValue(result.command)}</Typography.Text></Descriptions.Item>
      {Boolean(result.fallbackCommand) && <Descriptions.Item label="Fallback 命令" span={2}><Typography.Text code copyable>{displayValue(result.fallbackCommand)}</Typography.Text></Descriptions.Item>}
    </Descriptions>
    {Boolean(operation.errorMessage) && <Alert type="error" showIcon message={displayValue(operation.errorMessage)} />}
    {Boolean(result.output) && <Input.TextArea value={displayValue(result.output)} readOnly rows={12} wrap="off" />}
  </Space>;
  if (kind === 'create') return detail.afterConfig ? <SyncTaskConfigDetail value={detail.afterConfig} sourceServerName={sourceServerName} /> : <Empty description="缺少创建版本" />;
  return <Typography.Paragraph>{displayValue(detail.detail ?? detail.summary)}</Typography.Paragraph>;
};

export default function SyncTaskDetailDrawer({ task, loading, initialTab = 'instances', onClose }: Props) {
  const [instances, setInstances] = useState<TaskInstance[]>([]);
  const [changes, setChanges] = useState<TaskChangeLog[]>([]);
  const [versions, setVersions] = useState<Record<string, unknown>[]>([]);
  const [alerts, setAlerts] = useState<RealtimeAlert[]>([]);
  const [dataLoading, setDataLoading] = useState(false);
  const [alertsLoading, setAlertsLoading] = useState(false);
  const [changesLoading, setChangesLoading] = useState(false);
  const [versionsLoading, setVersionsLoading] = useState(false);
  const [mappingOpen, setMappingOpen] = useState(false);
  const [mappingInstance, setMappingInstance] = useState<TaskInstance>();
  const [instanceMappings, setInstanceMappings] = useState<TaskMapping[]>([]);
  const [stoppingInstanceId, setStoppingInstanceId] = useState<number>();
  const [recoverySource, setRecoverySource] = useState<TaskInstance>();
  const [stopTarget, setStopTarget] = useState<TaskInstance>();
  const [inspector, setInspector] = useState<{ title: string; kind?: InstanceInspectorKind; value: unknown }>();
  const [inspectorLoading, setInspectorLoading] = useState(false);
  const [changeDetail, setChangeDetail] = useState<Record<string, unknown>>();
  const [changeDetailLoadingId, setChangeDetailLoadingId] = useState<number>();
  const [activeChangeLog, setActiveChangeLog] = useState<TaskChangeLog>();
  const [changeDetailError, setChangeDetailError] = useState('');
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
  const instanceRequestSequenceRef = useRef(0);
  const tabRequestSequenceRef = useRef(0);
  const inspectorRequestSequenceRef = useRef(0);
  const changeDetailRequestRef = useRef(0);
  const runtimeInstance = useMemo(() => selectRuntimeInstance(instances), [instances]);

  const reloadInstances = useCallback(async (silent = false) => {
    if (!task) return;
    const sequence = ++instanceRequestSequenceRef.current;
    if (!silent) setDataLoading(true);
    try {
      const allInstances = await listInstances(task.id);
      if (sequence !== instanceRequestSequenceRef.current) return;
      const production = allInstances.filter((item) => item.executionMode !== 'DEBUG');
      setInstances(production);
    } catch (error) { if (sequence === instanceRequestSequenceRef.current) message.error((error as Error).message); }
    finally { if (!silent && sequence === instanceRequestSequenceRef.current) setDataLoading(false); }
  }, [task?.id]);

  const loadActiveTab = useCallback(async () => {
    if (!task || activeDrawerTab === 'instances' || activeDrawerTab === 'detail' || activeDrawerTab === 'runtime') return;
    const sequence = ++tabRequestSequenceRef.current;
    if (activeDrawerTab === 'alerts') {
      setAlertsLoading(true);
      try {
        const rows = await listAlerts(task.id);
        if (sequence === tabRequestSequenceRef.current) setAlerts(rows);
      } catch (error) { if (sequence === tabRequestSequenceRef.current) message.error((error as Error).message); }
      finally { if (sequence === tabRequestSequenceRef.current) setAlertsLoading(false); }
      return;
    }
    if (activeDrawerTab === 'changes') {
      setChangesLoading(true);
      try {
        const rows = await listChangeLogs(task.id);
        if (sequence === tabRequestSequenceRef.current) setChanges(rows);
      } catch (error) { if (sequence === tabRequestSequenceRef.current) message.error((error as Error).message); }
      finally { if (sequence === tabRequestSequenceRef.current) setChangesLoading(false); }
      return;
    }
    if (activeDrawerTab === 'versions') {
      setVersionsLoading(true);
      try {
        const rows = await listVersions(task.id);
        if (sequence === tabRequestSequenceRef.current) setVersions(rows);
      } catch (error) { if (sequence === tabRequestSequenceRef.current) message.error((error as Error).message); }
      finally { if (sequence === tabRequestSequenceRef.current) setVersionsLoading(false); }
      return;
    }
    if (activeDrawerTab === 'data-link') {
      setDataLinkLoading(true);
      try {
        const [dirtyPage, schemaEvents, progress] = await Promise.all([
          listSyncDirtyRecords(task.id, unresolvedOnly, dirtyPageNo, 20),
          listSyncSchemaChanges(task.id),
          runtimeInstance ? getSyncProgress(task.id, runtimeInstance.id, false).catch(() => undefined) : Promise.resolve(undefined),
        ]);
        if (sequence !== tabRequestSequenceRef.current) return;
        setDirtyRecords(dirtyPage.items); setDirtyTotal(dirtyPage.total);
        setSchemaChanges(schemaEvents); setSyncProgress(progress);
      } catch (error) { if (sequence === tabRequestSequenceRef.current) message.error((error as Error).message); }
      finally { if (sequence === tabRequestSequenceRef.current) setDataLinkLoading(false); }
    }
  }, [activeDrawerTab, dirtyPageNo, runtimeInstance?.id, task?.id, unresolvedOnly]);

  useEffect(() => {
    if (!task) return;
    instanceRequestSequenceRef.current += 1; tabRequestSequenceRef.current += 1; inspectorRequestSequenceRef.current += 1;
    setInstances([]); setAlerts([]); setChanges([]); setVersions([]); setDirtyRecords([]); setDirtyTotal(0); setSchemaChanges([]);
    setDetailCommand(''); setSyncProgress(undefined); setUnresolvedOnly(true); setDirtyPageNo(1); setActiveDrawerTab(initialTab); setActiveChangeLog(undefined); setChangeDetail(undefined); setChangeDetailError(''); setInspector(undefined); setInspectorLoading(false);
  }, [initialTab, task?.id]);

  useEffect(() => { void reloadInstances(); }, [reloadInstances]);
  useEffect(() => { void loadActiveTab(); }, [loadActiveTab]);

  useEffect(() => {
    if (!task || !instances.some((item) => ACTIVE.includes(item.status))) return undefined;
    const timer = window.setInterval(() => { void reloadInstances(true); }, 3000);
    return () => window.clearInterval(timer);
  }, [instances, reloadInstances, task]);

  useEffect(() => {
    if (!task) return;
    const publishAiContext = () => window.dispatchEvent(new CustomEvent('sql-agent:ai-context-update', {
      detail: {
        contextType: 'REALTIME_SYNC_TASK',
        entityId: String(task.id),
        title: `${task.name} · ${activeDrawerTab === 'data-link' ? '脏数据与 Schema 演进' : activeDrawerTab}`,
        revision: task.updateTime,
      },
    }));
    publishAiContext();
    window.addEventListener('sql-agent:ai-context-request', publishAiContext);
    return () => window.removeEventListener('sql-agent:ai-context-request', publishAiContext);
  }, [activeDrawerTab, instances, task]);

  const inspect = async (instance: TaskInstance, kind: InstanceInspectorKind) => {
    if (!task) return;
    const sequence = ++inspectorRequestSequenceRef.current;
    const names: Record<InstanceInspectorKind, string> = { config: '实例配置', 'startup-log': '启动日志', 'runtime-log': '运行日志', runtime: '运行指标', resources: '资源', checkpoints: 'Checkpoint', 'log-components': '日志组件' };
    setInspector({ title: kind === 'config' ? `实例配置 - 实例 ${instance.id}` : `实例 ${instance.id} - ${names[kind]}`, kind, value: undefined });
    setInspectorLoading(true);
    try {
      const value = await getInstanceInfo(task.id, instance.id, kind);
      if (sequence === inspectorRequestSequenceRef.current) setInspector((current) => current ? { ...current, value } : current);
    } catch (error) { if (sequence === inspectorRequestSequenceRef.current) message.error((error as Error).message); }
    finally { if (sequence === inspectorRequestSequenceRef.current) setInspectorLoading(false); }
  };

  const inspectVersion = async (row: Record<string, unknown>) => {
    if (!task) return;
    const versionId = Number(row.id);
    try {
      setInspector({ title: `版本 ${String(row.versionNo ?? versionId)} - 配置快照`, kind: 'config', value: await getVersionConfig(task.id, versionId) });
    } catch (error) { message.error((error as Error).message); }
  };

  const showChangeDetail = async (row: TaskChangeLog) => {
    const requestId = ++changeDetailRequestRef.current;
    try {
      setActiveChangeLog(row);
      setChangeDetail(undefined);
      setChangeDetailError('');
      setChangeDetailLoadingId(row.id);
      const detail = await getChangeLogDetail(row.id);
      if (changeDetailRequestRef.current === requestId) setChangeDetail(detail);
    } catch (error) {
      if (changeDetailRequestRef.current === requestId) setChangeDetailError((error as Error).message);
    } finally {
      if (changeDetailRequestRef.current === requestId) setChangeDetailLoadingId(undefined);
    }
  };

  const closeChangeDetail = () => {
    changeDetailRequestRef.current += 1;
    setActiveChangeLog(undefined);
    setChangeDetail(undefined);
    setChangeDetailLoadingId(undefined);
    setChangeDetailError('');
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
    setStopTarget(instance);
  };

  const confirmStopProductionInstance = async (method: 'savepoint' | 'direct') => {
    if (!task || !stopTarget) return;
    if (method === 'savepoint' && stopTarget.status !== 'running') {
      message.error('当前正式实例不是运行中状态，无法生成 Savepoint；请勾选允许直接停止');
      return;
    }
    try {
      setStoppingInstanceId(stopTarget.id);
      await stopInstance(task.id, stopTarget.id, method);
      message.success('停止请求已提交'); setStopTarget(undefined); await reloadInstances();
    } catch (error) { message.error((error as Error).message); }
    finally { setStoppingInstanceId(undefined); }
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

  const showSchemaImpact = async (event: SyncSchemaChange) => {
    try {
      const columns = schemaImpactColumns(event);
      const impact = await analyzeAssetImpact({
        catalog: 'paimon',
        database: event.targetDatabase,
        table: event.targetTable,
        columns,
        changeType: event.changeType === 'INCOMPATIBLE' ? 'INCOMPATIBLE_SCHEMA_CHANGE' : 'COMPATIBLE_SCHEMA_CHANGE',
      });
      Modal.info({
        title: `Schema 变更影响 · ${event.targetDatabase}.${event.targetTable}`,
        content: <ImpactSummary value={impact} />,
        width: 900,
        okText: '关闭',
      });
    } catch (error) {
      message.error((error as Error).message);
    }
  };

  if (!task) return null;
  const snapshotFinished = syncProgress?.snapshotFinished;
  const snapshotRemaining = syncProgress?.snapshotRemaining;
  const snapshotTotal = snapshotFinished != null && snapshotRemaining != null ? snapshotFinished + snapshotRemaining : undefined;
  const snapshotPercent = snapshotTotal && snapshotFinished != null ? Math.round(snapshotFinished / snapshotTotal * 100) : undefined;
  const instanceContent = <RealtimeInstanceList taskId={task.id} instances={instances} loading={dataLoading}
    stoppingInstanceId={stoppingInstanceId} syncPlaceholders stoppableStatuses={STOPPABLE}
    onRefresh={() => void reloadInstances()} onInspect={(row) => void inspect(row, 'config')}
    onRecover={setRecoverySource} onStop={stopProductionInstance}
    renderExtraActions={(row) => <Typography.Link onClick={() => void showInstanceMappings(row)}>同步表</Typography.Link>} />;

  return <>
    <RealtimeTaskDetailDrawer title={task.name} loading={loading} activeKey={activeDrawerTab} onTabChange={setActiveDrawerTab} onClose={onClose} tabs={[
        { key: 'instances', label: '实例列表', children: instanceContent },
        { key: 'detail', label: '任务详情', children: <div className="task-detail-sections"><SyncTaskConfigDetail value={task} sourceServerName={task.sourceServerName} /><section className="realtime-instance-config-section"><Typography.Title level={5}>命令预览</Typography.Title><div className="task-command-preview"><Button type="link" icon={<EyeOutlined />} loading={detailCommandLoading} onClick={async () => { try { setDetailCommandLoading(true); setDetailCommand((await previewSavedSyncTask(task.id)).command); } catch (error) { message.error((error as Error).message); } finally { setDetailCommandLoading(false); } }}>预览</Button><Input.TextArea className="task-command-preview-textarea" value={detailCommand} placeholder="点击预览生成 Paimon Action 等价命令" readOnly rows={10} wrap="off" /></div></section></div> },
        { key: 'runtime', label: '运行监控', children: <RealtimeRuntimeMonitor taskId={task.id} taskType="sync" instance={runtimeInstance} active={activeDrawerTab === 'runtime'} checkpointIntervalSeconds={task.taskConfig.checkpointInterval} /> },
        { key: 'diagnostics', label: '诊断', children: <RealtimeDiagnosticPanel taskId={task.id} instances={instances} onRecover={setRecoverySource} /> },
        { key: 'data-link', label: '数据链路', children: <div className="sync-data-link-panel">
          <div className="sync-link-summary">
            <div><span>当前实例</span><strong>{runtimeInstance?.id ?? '-'}</strong></div>
            <div><span>全量快照</span><strong>{snapshotPercent == null ? '-' : `${snapshotPercent}%`}</strong></div>
            <div><span>源端延迟</span><strong>{syncProgress?.sourceLagMs == null ? '-' : `${syncProgress.sourceLagMs} ms`}</strong></div>
            <div><span>未处理脏数据</span><strong>{dirtyTotal}</strong></div>
          </div>
          <Tabs className="ui-flat-tabs" items={[
            { key: 'dirty', label: `脏数据（${dirtyTotal}）`, children: <><div className="realtime-detail-toolbar"><Space><Select value={unresolvedOnly ? 'unresolved' : 'all'} onChange={(value) => { setUnresolvedOnly(value === 'unresolved'); setDirtyPageNo(1); }} options={[{ label: '仅未处理', value: 'unresolved' }, { label: '全部记录', value: 'all' }]} /><Button icon={<ReloadOutlined />} loading={dataLinkLoading} onClick={() => void loadActiveTab()}>刷新</Button></Space></div><Table<SyncDirtyRecord> rowKey="id" size="small" loading={dataLinkLoading} dataSource={dirtyRecords} pagination={false} scroll={{ x: 1280 }} locale={{ emptyText: '暂无脏数据记录' }} columns={[
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
              { title: '操作', fixed: 'right', width: 210, render: (_, row) => <Space><Typography.Link onClick={() => setInspector({ title: `Schema 变更 #${row.id}`, value: row.change })}>查看</Typography.Link><Typography.Link onClick={() => void showSchemaImpact(row)}>查看影响</Typography.Link>{row.status === 'PENDING' && row.changeType === 'ADD_COLUMNS' && <Button type="link" size="small" loading={handlingId === row.id} onClick={() => applySchema(row)}>应用</Button>}</Space> },
            ]} /></> },
          ]} />
        </div> },
        { key: 'alerts', label: `告警记录（${alerts.length}）`, children: <RealtimeAlertTable alerts={alerts} loading={alertsLoading} /> },
        { key: 'changes', label: `变更记录（${changes.length}）`, children: <RealtimeChangeLogTable changes={changes} loading={changesLoading} detailLoadingId={changeDetailLoadingId} onOpenDetail={(row) => void showChangeDetail(row)} /> },
        { key: 'versions', label: `版本记录（${versions.length}）`, children: <Table size="small" rowKey={(row) => String(row.id)} loading={versionsLoading} pagination={false} dataSource={versions} locale={{ emptyText: '暂无版本记录' }} columns={[{ title: '版本', dataIndex: 'versionNo', width: 100 }, { title: '操作人', dataIndex: 'operator', width: 160 }, { title: '创建时间', dataIndex: 'createTime', width: 200 }, { title: '配置快照', render: (_: unknown, row: Record<string, unknown>) => <Typography.Link onClick={() => void inspectVersion(row)}>查看版本配置</Typography.Link> }]} /> },
      ]} />
    <Modal className="sync-mapping-modal" title={`同步表映射${mappingInstance ? ` - 实例 ${mappingInstance.id}` : ''}`} open={mappingOpen} footer={null} width={900} destroyOnHidden onCancel={() => { setMappingOpen(false); setMappingInstance(undefined); }}><Table rowKey="id" size="small" pagination={false} scroll={{ y: 480 }} locale={{ emptyText: '暂无同步表映射' }} dataSource={instanceMappings} columns={[{ title: '序号', width: 72, render: (_: unknown, __: TaskMapping, index: number) => index + 1 }, { title: '源表', width: 360, render: (_: unknown, row: TaskMapping) => <span className="sync-mapping-full-name">{`${row.sourceDatabase}.${row.sourceTable}`}</span> }, { title: '目标 Paimon 表', render: (_: unknown, row: TaskMapping) => <span className="sync-mapping-full-name">{`${row.targetDatabase}.${row.targetTable}`}</span> }]} /></Modal>
    <RealtimeInstanceStopModal instance={stopTarget} loading={stoppingInstanceId !== undefined} onClose={() => setStopTarget(undefined)} onConfirm={confirmStopProductionInstance} />
    <Drawer className="task-change-detail-drawer"
      title={activeChangeLog ? `${normalizeChangeAction(activeChangeLog.action)}明细 - ${activeChangeLog.createTime}` : '变更明细'}
      open={Boolean(activeChangeLog)} extra={<Button onClick={closeChangeDetail}>关闭</Button>} placement="right"
      width={activeChangeLog?.detailKind === 'edit' ? 1420 : 1100} onClose={closeChangeDetail} destroyOnClose>
      {changeDetailLoadingId !== undefined
        ? <div className="task-change-detail-loading"><Spin size="large" /><Typography.Text type="secondary">变更明细加载中</Typography.Text></div>
        : changeDetailError
          ? <Alert type="error" showIcon message="变更明细加载失败" description={changeDetailError} />
          : changeDetail ? <ChangeDetailContent detail={changeDetail} sourceServerName={task.sourceServerName} /> : null}
    </Drawer>
    <InstanceInspectorModal open={Boolean(inspector)} title={inspector?.title} kind={inspector?.kind} value={inspector?.value} loading={inspectorLoading} renderConfig={(value) => <SyncTaskConfigDetail value={value} sourceServerName={task.sourceServerName} showNavigation navigationPrefix="sync-production-instance" />} onClose={() => { inspectorRequestSequenceRef.current += 1; setInspector(undefined); setInspectorLoading(false); }} />
    <RealtimeRecoveryModal taskId={task.id} source={recoverySource} onClose={() => setRecoverySource(undefined)} onRecovered={() => { setRecoverySource(undefined); void reloadInstances(); }} />
  </>;
}
