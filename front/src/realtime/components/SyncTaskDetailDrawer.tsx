import { Alert, Button, Descriptions, Drawer, Empty, Input, Modal, Space, Spin, Table, Tag, Typography, message } from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { getChangeLogDetail, getInstanceInfo, getVersionConfig, listAlerts, listChangeLogs, listInstances, listSyncDdlExceptions, listVersions, stopInstance } from '../api';
import type { RealtimeAlert, SyncDdlException, SyncSchemaChange, SyncTask, TaskChangeLog, TaskInstance, TaskMapping } from '../types';
import InstanceInspectorModal, { type InstanceInspectorKind } from './InstanceInspectorModal';
import RealtimeRuntimeMonitor from './RealtimeRuntimeMonitor';
import RealtimeDiagnosticPanel from './RealtimeDiagnosticPanel';
import RealtimeRecoveryModal from './RealtimeRecoveryModal';
import { normalizeChangeAction } from './changeActions';
import { selectRuntimeInstance } from './runtimeSelection';
import SyncTaskConfigDetail from './SyncTaskConfigDetail';
import SyncTaskSnapshotModal from './SyncTaskSnapshotModal';
import { syncStartMethodLabel } from './syncStartMethod';
import RealtimeTaskDetailDrawer from './RealtimeTaskDetailDrawer';
import RealtimeAlertTable from './RealtimeAlertTable';
import RealtimeInstanceStopModal from './RealtimeInstanceStopModal';
import RealtimeInstanceList from './RealtimeInstanceList';
import RealtimeChangeLogTable from './RealtimeChangeLogTable';

interface Props {
  task?: SyncTask;
  loading?: boolean;
  initialTab?: string;
  onClose: () => void;
}

const ACTIVE = ['submitting', 'running', 'debug_success_running', 'stopping', 'restarting'];
const STOPPABLE = ['submitting', 'running', 'debug_success_running', 'restarting'];
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

export const ChangeDetailContent = ({ detail }: { detail: Record<string, unknown> }) => {
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
        {detail.beforeConfig ? <SyncTaskConfigDetail value={detail.beforeConfig} highlightPaths={changedPaths} compareMode /> : <Empty description="缺少变更前版本" />}
      </section>
      <section className="task-change-detail-pane"><Typography.Title level={5}>变更后</Typography.Title>
        {detail.afterConfig ? <SyncTaskConfigDetail value={detail.afterConfig} highlightPaths={changedPaths} compareMode /> : <Empty description="缺少变更后版本" />}
      </section>
    </div>;
  }
  if (kind === 'start') return <Space direction="vertical" size={16} style={{ width: '100%' }}>
    <Descriptions bordered size="small" column={2} className="task-change-operation-descriptions">
      <Descriptions.Item label="启动方式">{syncStartMethodLabel(
        request.startType ?? result.startType,
        request.sourceStartupTimestampMillis ?? result.sourceStartupTimestampMillis,
        request.mysqlStartupMode ?? result.mysqlStartupMode,
      )}</Descriptions.Item>
      <Descriptions.Item label="实例 ID">{displayValue(detail.taskInstanceId ?? instance.id)}</Descriptions.Item>
      <Descriptions.Item label="实例状态"><Tag color={statusColor[String(instance.status ?? '')]}>{statusLabel[String(instance.status ?? '')] ?? displayValue(instance.status)}</Tag></Descriptions.Item>
      <Descriptions.Item label="JobID">{displayValue(result.jobId ?? instance.jobId)}</Descriptions.Item>
      <Descriptions.Item label="历史状态" span={2}>{displayValue(request.statePath)}</Descriptions.Item>
      <Descriptions.Item label="YARN Application ID" span={2}>{displayValue(result.yarnApplicationId ?? instance.yarnApplicationId)}</Descriptions.Item>
    </Descriptions>
    {detail.afterConfig ? <SyncTaskConfigDetail value={detail.afterConfig} /> : <Empty description="缺少启动使用参数" />}
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
  if (kind === 'create') return detail.afterConfig ? <SyncTaskConfigDetail value={detail.afterConfig} /> : <Empty description="缺少创建版本" />;
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
  const [inspector, setInspector] = useState<{ title: string; kind?: InstanceInspectorKind; value: unknown; instanceId?: number; loadError?: string }>();
  const [inspectorLoading, setInspectorLoading] = useState(false);
  const [changeDetail, setChangeDetail] = useState<Record<string, unknown>>();
  const [changeDetailLoadingId, setChangeDetailLoadingId] = useState<number>();
  const [activeChangeLog, setActiveChangeLog] = useState<TaskChangeLog>();
  const [changeDetailError, setChangeDetailError] = useState('');
  const [activeDrawerTab, setActiveDrawerTab] = useState('instances');
  const [ddlExceptions, setDdlExceptions] = useState<SyncDdlException[]>([]);
  const [dataLinkLoading, setDataLinkLoading] = useState(false);
  const instanceRequestSequenceRef = useRef(0);
  const tabRequestSequenceRef = useRef(0);
  const inspectorRequestSequenceRef = useRef(0);
  const changeDetailRequestRef = useRef(0);
  const runtimeInstance = useMemo(() => selectRuntimeInstance(instances.filter((item) => item.executionMode !== 'DEBUG')), [instances]);

  const reloadInstances = useCallback(async (silent = false) => {
    if (!task) return;
    const sequence = ++instanceRequestSequenceRef.current;
    if (!silent) setDataLoading(true);
    try {
      const allInstances = await listInstances(task.id, 'ALL');
      if (sequence !== instanceRequestSequenceRef.current) return;
      setInstances(allInstances);
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
    if (activeDrawerTab === 'ddl') {
      setDataLinkLoading(true);
      try {
        const rows = await listSyncDdlExceptions(task.id);
        if (sequence === tabRequestSequenceRef.current) setDdlExceptions(rows);
      } catch (error) { if (sequence === tabRequestSequenceRef.current) message.error((error as Error).message); }
      finally { if (sequence === tabRequestSequenceRef.current) setDataLinkLoading(false); }
      return;
    }
  }, [activeDrawerTab, task?.id]);

  useEffect(() => {
    if (!task) return;
    instanceRequestSequenceRef.current += 1; tabRequestSequenceRef.current += 1; inspectorRequestSequenceRef.current += 1;
    setInstances([]); setAlerts([]); setChanges([]); setVersions([]);
    setDdlExceptions([]); setActiveDrawerTab(initialTab === 'data-link' ? 'runtime' : initialTab === 'detail' ? 'instances' : initialTab); setActiveChangeLog(undefined); setChangeDetail(undefined); setChangeDetailError(''); setInspector(undefined); setInspectorLoading(false);
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
        title: `${task.name} · ${activeDrawerTab}`,
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
    setInspector({ title: kind === 'config' ? `${instance.executionMode === 'DEBUG' ? '调试配置' : '实例配置'} - 实例 ${instance.id}` : `实例 ${instance.id} - ${names[kind]}`, kind, value: undefined, instanceId: instance.id });
    setInspectorLoading(true);
    try {
      const value = await getInstanceInfo(task.id, instance.id, kind);
      if (sequence === inspectorRequestSequenceRef.current) setInspector((current) => current ? { ...current, value } : current);
    } catch (error) { if (sequence === inspectorRequestSequenceRef.current) {
      message.error((error as Error).message);
      setInspector((current) => current?.kind === 'config' ? { ...current, loadError: (error as Error).message } : current);
    } }
    finally { if (sequence === inspectorRequestSequenceRef.current) setInspectorLoading(false); }
  };

  const inspectVersion = async (row: Record<string, unknown>) => {
    if (!task) return;
    const sequence = ++inspectorRequestSequenceRef.current;
    const versionId = Number(row.id);
    setInspector({ title: `版本 ${String(row.versionNo ?? versionId)} - 配置快照`, kind: 'config', value: undefined });
    setInspectorLoading(true);
    try {
      const value = await getVersionConfig(task.id, versionId);
      if (sequence === inspectorRequestSequenceRef.current) setInspector((current) => current ? { ...current, value } : current);
    } catch (error) { if (sequence === inspectorRequestSequenceRef.current) {
      message.error((error as Error).message);
      setInspector((current) => current?.kind === 'config' ? { ...current, loadError: (error as Error).message } : current);
    } }
    finally { if (sequence === inspectorRequestSequenceRef.current) setInspectorLoading(false); }
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

  const stopProductionInstance = async (instance: TaskInstance) => {
    if (!task || !instance.managed) return;
    if (instance.executionMode === 'DEBUG') {
      try {
        setStoppingInstanceId(instance.id);
        await stopInstance(task.id, instance.id, 'direct');
        message.success('调试实例停止请求已提交');
        await reloadInstances();
      } catch (error) { message.error((error as Error).message); }
      finally { setStoppingInstanceId(undefined); }
      return;
    }
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

  if (!task) return null;
  const instanceContent = <RealtimeInstanceList taskId={task.id} instances={instances} loading={dataLoading} showModeFilter
    stoppingInstanceId={stoppingInstanceId} syncPlaceholders stoppableStatuses={STOPPABLE}
    onRefresh={() => void reloadInstances()} onInspect={(row) => void inspect(row, 'config')}
    onRecover={setRecoverySource} onStop={stopProductionInstance}
    renderExtraActions={(row) => <Typography.Link onClick={() => void showInstanceMappings(row)}>同步表</Typography.Link>} />;

  return <>
    <RealtimeTaskDetailDrawer title={task.name} loading={loading} activeKey={activeDrawerTab} onTabChange={setActiveDrawerTab} onClose={onClose} tabs={[
        { key: 'instances', label: '实例列表', children: instanceContent },
        { key: 'verification', label: '验数记录', children: <Table rowKey="id" size="small" dataSource={[]} pagination={false} locale={{ emptyText: '暂无验数记录' }} scroll={{ x: 1820 }} columns={[
          { title: 'ID', dataIndex: 'id', width: 90 },
          { title: '调试记录ID', dataIndex: 'debugRecordId', width: 190, ellipsis: true },
          { title: '线上表', dataIndex: 'onlineTable', width: 220, ellipsis: true },
          { title: '调试表', dataIndex: 'debugTable', width: 220, ellipsis: true },
          { title: '校验状态', dataIndex: 'checkStatus', width: 110 },
          { title: '是否分区表', dataIndex: 'partitioned', width: 120 },
          { title: '元数据是否一致', dataIndex: 'metadataMatched', width: 140 },
          { title: '行数是否一致', dataIndex: 'rowCountMatched', width: 130 },
          { title: 'CRC32值是否一致', dataIndex: 'crc32Matched', width: 150 },
          { title: '配置规则', dataIndex: 'rule', width: 120 },
          { title: '创建时间', dataIndex: 'createdAt', width: 170 },
          { title: '更新时间', dataIndex: 'updatedAt', width: 170 },
          { title: '操作人', dataIndex: 'operator', width: 120 },
        ]} /> },
        { key: 'runtime', label: '运行监控', children: <RealtimeRuntimeMonitor taskId={task.id} taskType="sync" instance={runtimeInstance} active={activeDrawerTab === 'runtime'} checkpointIntervalSeconds={task.taskConfig.checkpointInterval} /> },
        { key: 'diagnostics', label: '诊断', children: <RealtimeDiagnosticPanel taskId={task.id} instances={instances} onRecover={setRecoverySource} onOpenDdl={() => setActiveDrawerTab('ddl')} /> },
        { key: 'alerts', label: `告警记录（${alerts.length}）`, children: <RealtimeAlertTable alerts={alerts} loading={alertsLoading} /> },
        { key: 'ddl', label: `DDL 异常（${ddlExceptions.filter((item) => item.status === 'ACTIVE').length}）`, children: <Table<SyncDdlException>
          rowKey="id" size="small" loading={dataLinkLoading} dataSource={ddlExceptions} pagination={{ pageSize: 10 }}
          locale={{ emptyText: '暂无 DDL 异常' }} columns={[
            { title: '上报时间', dataIndex: 'createTime', width: 180 },
            { title: '源表', width: 260, render: (_, row) => `${row.databaseName}.${row.tableName}` },
            { title: 'DDL', dataIndex: 'ddlSql', ellipsis: true },
            { title: '状态', dataIndex: 'status', width: 120, render: (value: string) => <Tag color={value === 'ACTIVE' ? 'error' : 'success'}>{value === 'ACTIVE' ? '待剔除' : '已完成'}</Tag> },
          ]} /> },
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
          : changeDetail ? <ChangeDetailContent detail={changeDetail} /> : null}
    </Drawer>
    {inspector?.kind === 'config' ? <SyncTaskSnapshotModal open title={inspector.title} value={inspector.value} loadError={inspector.loadError}
      loading={inspectorLoading} snapshotKey={`${task.id}-${inspector.title}`} expectedTaskId={task.id} expectedInstanceId={inspector.instanceId}
      onClose={() => { inspectorRequestSequenceRef.current += 1; setInspector(undefined); setInspectorLoading(false); }} />
      : <InstanceInspectorModal open={Boolean(inspector)} title={inspector?.title} kind={inspector?.kind} value={inspector?.value} loading={inspectorLoading} onClose={() => { inspectorRequestSequenceRef.current += 1; setInspector(undefined); setInspectorLoading(false); }} />}
    <RealtimeRecoveryModal taskId={task.id} source={recoverySource} onClose={() => setRecoverySource(undefined)} onRecovered={() => { setRecoverySource(undefined); void reloadInstances(); }} />
  </>;
}
