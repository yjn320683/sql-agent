import { Empty, Modal, Table, Typography, message } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { getChangeLogDetail, getInstanceInfo, getVersionConfig, listAlerts, listChangeLogs, listInstances, listVersions, stopInstance } from '../api';
import type { ManagedTask, RealtimeAlert, TaskChangeLog, TaskInstance } from '../types';
import InstanceInspectorModal, { InstanceConfigView, StructuredKeyValueTable, type InstanceInspectorKind } from './InstanceInspectorModal';
import RealtimeRuntimeMonitor from './RealtimeRuntimeMonitor';
import RealtimeDiagnosticPanel from './RealtimeDiagnosticPanel';
import RealtimeRecoveryModal from './RealtimeRecoveryModal';
import RealtimeDebugReportPanel from './RealtimeDebugReportPanel';
import { selectRuntimeInstance } from './runtimeSelection';
import RealtimeTaskDetailDrawer from './RealtimeTaskDetailDrawer';
import RealtimeAlertTable from './RealtimeAlertTable';
import RealtimeInstanceStopModal from './RealtimeInstanceStopModal';
import RealtimeInstanceList from './RealtimeInstanceList';
import RealtimeChangeLogTable from './RealtimeChangeLogTable';

interface Props { task?: ManagedTask; loading?: boolean; initialTab?: 'instances' | 'detail' | 'runtime' | 'debug-report' | 'diagnostics' | 'alerts' | 'changes' | 'versions'; onClose: () => void }

const ACTIVE = ['submitting', 'running', 'stopping', 'restarting'];
export default function RealtimeManagedTaskDetailModal({ task, loading, initialTab = 'instances', onClose }: Props) {
  const [versions, setVersions] = useState<Record<string, unknown>[]>([]);
  const [instances, setInstances] = useState<TaskInstance[]>([]);
  const [alerts, setAlerts] = useState<RealtimeAlert[]>([]);
  const [changes, setChanges] = useState<TaskChangeLog[]>([]);
  const [dataLoading, setDataLoading] = useState(false);
  const [activeTab, setActiveTab] = useState<string>(initialTab);
  const [stoppingInstanceId, setStoppingInstanceId] = useState<number>();
  const [stopTarget, setStopTarget] = useState<TaskInstance>();
  const [recoverySource, setRecoverySource] = useState<TaskInstance>();
  const [inspector, setInspector] = useState<{ title: string; kind?: InstanceInspectorKind; value: unknown }>();
  const [changeDetail, setChangeDetail] = useState<Record<string, unknown>>();
  const [changeDetailLoadingId, setChangeDetailLoadingId] = useState<number>();
  const requestSequenceRef = useRef(0);

  const reload = useCallback(async (silent = false) => {
    if (!task) return;
    const sequence = ++requestSequenceRef.current;
    if (!silent) setDataLoading(true);
    try {
      const [production, debug, versionRows, taskChanges, allAlerts] = await Promise.all([
        listInstances(task.id), listInstances(task.id, 'DEBUG'), listVersions(task.id), listChangeLogs(task.id), listAlerts(),
      ]);
      if (sequence !== requestSequenceRef.current) return;
      setInstances([...production, ...debug]); setVersions(versionRows); setChanges(taskChanges);
      setAlerts(allAlerts.filter((item) => item.taskId === task.id));
    } catch (error) { if (sequence === requestSequenceRef.current) message.error((error as Error).message); }
    finally { if (!silent && sequence === requestSequenceRef.current) setDataLoading(false); }
  }, [task]);

  useEffect(() => {
    setActiveTab(initialTab);
    if (!task) { setVersions([]); setInstances([]); setAlerts([]); setChanges([]); return; }
    void reload();
  }, [initialTab, reload, task]);

  useEffect(() => {
    if (!task || !instances.some((item) => item.executionMode === 'PRODUCTION' && ACTIVE.includes(item.status))) return undefined;
    const timer = window.setInterval(() => void reload(true), 3000);
    return () => window.clearInterval(timer);
  }, [instances, reload, task]);

  useEffect(() => {
    if (!task) return;
    if (activeTab === 'runtime' && selectRuntimeInstance(instances.filter((item) => item.executionMode !== 'DEBUG'))) return;
    const contextType = task.taskType === 'compute' ? 'REALTIME_COMPUTE_TASK' : 'REALTIME_EXPORT_TASK';
    const publishAiContext = () => window.dispatchEvent(new CustomEvent('sql-agent:ai-context-update', {
      detail: {
        contextType,
        entityId: String(task.id),
        title: `${task.name} · ${activeTab}`,
        revision: task.updateTime ?? '0',
      },
    }));
    publishAiContext();
    window.addEventListener('sql-agent:ai-context-request', publishAiContext);
    return () => window.removeEventListener('sql-agent:ai-context-request', publishAiContext);
  }, [activeTab, instances, task]);

  const inspectInstance = async (instance: TaskInstance) => { if (!task) return; try { setInspector({ title: `实例配置 - 实例 ${instance.id}`, kind: 'config', value: await getInstanceInfo(task.id, instance.id, 'config') }); } catch (error) { message.error((error as Error).message); } };
  const inspectVersion = async (row: Record<string, unknown>) => { if (!task) return; const versionId = Number(row.id); try { setInspector({ title: `版本 ${String(row.versionNo ?? versionId)} - 配置快照`, kind: 'config', value: await getVersionConfig(task.id, versionId) }); } catch (error) { message.error((error as Error).message); } };
  const showChangeDetail = async (row: TaskChangeLog) => { try { setChangeDetailLoadingId(row.id); setChangeDetail(await getChangeLogDetail(row.id)); } catch (error) { message.error((error as Error).message); } finally { setChangeDetailLoadingId(undefined); } };
  const stopProductionInstance = (instance: TaskInstance) => {
    if (!task || instance.executionMode === 'DEBUG' || !instance.managed) return;
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
      message.success('停止请求已提交');
      setStopTarget(undefined);
      await reload();
    } catch (error) { message.error((error as Error).message); }
    finally { setStoppingInstanceId(undefined); }
  };

  if (!task) return null;
  const runtimeInstance = selectRuntimeInstance(instances.filter((item) => item.executionMode !== 'DEBUG'));
  const instanceContent = <RealtimeInstanceList taskId={task.id} instances={instances} loading={dataLoading}
    stoppingInstanceId={stoppingInstanceId} showDebugReport onRefresh={() => void reload()}
    onInspect={(row) => void inspectInstance(row)} onRecover={setRecoverySource} onStop={stopProductionInstance} />;

  return <>
    <RealtimeTaskDetailDrawer title={task.name} loading={loading} activeKey={activeTab} onTabChange={setActiveTab} onClose={onClose} tabs={[
        { key: 'instances', label: `实例列表（${instances.length}）`, children: instanceContent }, { key: 'detail', label: '任务详情', children: <div className="task-detail-sections"><InstanceConfigView value={task} /></div> },
        { key: 'runtime', label: '运行监控', children: <RealtimeRuntimeMonitor taskId={task.id} taskType={task.taskType} instance={runtimeInstance} active={activeTab === 'runtime'} checkpointIntervalSeconds={task.flinkConf.checkpointIntervalSeconds} /> },
        { key: 'debug-report', label: '调试报告', children: <RealtimeDebugReportPanel taskId={task.id} instances={instances} /> },
        { key: 'diagnostics', label: '诊断', children: <RealtimeDiagnosticPanel taskId={task.id} instances={instances} onRecover={setRecoverySource} /> },
        { key: 'alerts', label: `告警记录（${alerts.length}）`, children: <RealtimeAlertTable alerts={alerts} loading={dataLoading} /> },
        { key: 'changes', label: `变更记录（${changes.length}）`, children: <RealtimeChangeLogTable changes={changes} loading={dataLoading} detailLoadingId={changeDetailLoadingId} onOpenDetail={(row) => void showChangeDetail(row)} /> },
        { key: 'versions', label: `版本记录（${versions.length}）`, children: <Table size="small" rowKey={(row) => String(row.id)} pagination={false} dataSource={versions} locale={{ emptyText: '暂无版本记录' }} columns={[{ title: '版本', dataIndex: 'versionNo', width: 100 }, { title: '操作人', dataIndex: 'operator', width: 160 }, { title: '创建时间', dataIndex: 'createTime', width: 200 }, { title: '配置快照', render: (_: unknown, row: Record<string, unknown>) => <Typography.Link onClick={() => void inspectVersion(row)}>查看版本配置</Typography.Link> }]} /> },
      ]} />
    <Modal className="realtime-change-detail-modal" title="变更记录详情" open={Boolean(changeDetail)} footer={null} width={1100} onCancel={() => setChangeDetail(undefined)} destroyOnHidden>{changeDetail ? <StructuredKeyValueTable value={changeDetail} /> : <Empty description="暂无变更详情" />}</Modal>
    <InstanceInspectorModal open={Boolean(inspector)} title={inspector?.title} kind={inspector?.kind} value={inspector?.value} onClose={() => setInspector(undefined)} />
    <RealtimeRecoveryModal taskId={task.id} source={recoverySource} onClose={() => setRecoverySource(undefined)} onRecovered={() => { setRecoverySource(undefined); void reload(); }} />
    <RealtimeInstanceStopModal instance={stopTarget} loading={stoppingInstanceId !== undefined} onClose={() => setStopTarget(undefined)} onConfirm={confirmStopProductionInstance} />
  </>;
}
