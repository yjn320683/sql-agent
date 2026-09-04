import { LinkOutlined, SearchOutlined } from '@ant-design/icons';
import { Button, Drawer, Empty, Input, Modal, Select, Space, Table, Tabs, Tag, Typography, message } from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { getChangeLogDetail, getInstanceInfo, getVersionConfig, listAlerts, listChangeLogs, listInstances, listVersions, stopInstance } from '../api';
import type { ManagedTask, RealtimeAlert, TaskChangeLog, TaskInstance } from '../types';
import InstanceInspectorModal, { InstanceConfigView, StructuredKeyValueTable, type InstanceInspectorKind } from './InstanceInspectorModal';
import InstanceListToolbar, { type InstanceSearchField, type InstanceSortOrder } from './InstanceListToolbar';
import InstanceLogPanel from './InstanceLogPanel';
import RealtimeRuntimeMonitor from './RealtimeRuntimeMonitor';
import { availableChangeActions, changeDetailButtonText, normalizeChangeAction } from './changeActions';
import { selectRuntimeInstance } from './runtimeSelection';

interface Props { task?: ManagedTask; loading?: boolean; onClose: () => void }

const ACTIVE = ['submitting', 'running', 'stopping', 'restarting'];
const statusLabel: Record<string, string> = {
  not_running: '未运行', submitting: '提交中', running: '运行中', debug_success_running: '运行中（调试成功）',
  stopping: '停止中', restarting: '重启中', finished: '已完成', failed: '失败', canceled: '已取消', killed_success: '已停止（调试成功）',
};
const statusColor: Record<string, string> = {
  submitting: 'processing', running: 'success', debug_success_running: 'success', stopping: 'warning', restarting: 'processing',
  finished: 'success', failed: 'error', canceled: 'default', killed_success: 'success', not_running: 'default',
};

export default function RealtimeManagedTaskDetailModal({ task, loading, onClose }: Props) {
  const [versions, setVersions] = useState<Record<string, unknown>[]>([]);
  const [instances, setInstances] = useState<TaskInstance[]>([]);
  const [alerts, setAlerts] = useState<RealtimeAlert[]>([]);
  const [changes, setChanges] = useState<TaskChangeLog[]>([]);
  const [dataLoading, setDataLoading] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState('all');
  const [searchField, setSearchField] = useState<InstanceSearchField>('all');
  const [sortOrder, setSortOrder] = useState<InstanceSortOrder>('startedAtDesc');
  const [changeAction, setChangeAction] = useState('all');
  const [changeKeyword, setChangeKeyword] = useState('');
  const [activeTab, setActiveTab] = useState('instances');
  const [logInstance, setLogInstance] = useState<TaskInstance>();
  const [stoppingInstanceId, setStoppingInstanceId] = useState<number>();
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
    setKeyword(''); setStatus('all'); setSearchField('all'); setSortOrder('startedAtDesc'); setChangeAction('all'); setChangeKeyword(''); setLogInstance(undefined); setActiveTab('instances');
    if (!task) { setVersions([]); setInstances([]); setAlerts([]); setChanges([]); return; }
    void reload();
  }, [reload, task]);

  useEffect(() => {
    if (!task || !instances.some((item) => item.executionMode === 'PRODUCTION' && ACTIVE.includes(item.status))) return undefined;
    const timer = window.setInterval(() => void reload(true), 3000);
    return () => window.clearInterval(timer);
  }, [instances, reload, task]);

  const filteredInstances = useMemo(() => instances.filter((item) => {
    if (status !== 'all' && item.status !== status) return false;
    if (!keyword.trim()) return true;
    const value = searchField === 'all' ? [item.id, item.jobId, item.yarnApplicationId, item.failureMessage].join(' ') : String(item[searchField] ?? '');
    return value.toLowerCase().includes(keyword.trim().toLowerCase());
  }).sort((left, right) => {
    if (sortOrder === 'idAsc' || sortOrder === 'idDesc') return (left.id - right.id) * (sortOrder === 'idAsc' ? 1 : -1);
    const comparison = String(left.startedAt ?? left.createTime).localeCompare(String(right.startedAt ?? right.createTime));
    return comparison * (sortOrder === 'startedAtAsc' ? 1 : -1);
  }), [instances, keyword, searchField, sortOrder, status]);

  const filteredChanges = useMemo(() => { const search = changeKeyword.trim().toLowerCase(); return changes.filter((item) => (changeAction === 'all' || normalizeChangeAction(item.action) === changeAction) && (!search || [item.operator, normalizeChangeAction(item.action), item.detail, item.summary].join(' ').toLowerCase().includes(search))); }, [changeAction, changeKeyword, changes]);
  const changeActionOptions = useMemo(() => availableChangeActions(changes.map((item) => item.action)).map((value) => ({ label: value, value })), [changes]);

  const inspectInstance = async (instance: TaskInstance) => { if (!task) return; try { setInspector({ title: `实例配置 - 实例 ${instance.id}`, kind: 'config', value: await getInstanceInfo(task.id, instance.id, 'config') }); } catch (error) { message.error((error as Error).message); } };
  const inspectVersion = async (row: Record<string, unknown>) => { if (!task) return; const versionId = Number(row.id); try { setInspector({ title: `版本 ${String(row.versionNo ?? versionId)} - 配置快照`, kind: 'config', value: await getVersionConfig(task.id, versionId) }); } catch (error) { message.error((error as Error).message); } };
  const showChangeDetail = async (row: TaskChangeLog) => { try { setChangeDetailLoadingId(row.id); setChangeDetail(await getChangeLogDetail(row.id)); } catch (error) { message.error((error as Error).message); } finally { setChangeDetailLoadingId(undefined); } };
  const stopProductionInstance = (instance: TaskInstance) => {
    if (!task || instance.executionMode === 'DEBUG' || !instance.managed) return;
    Modal.confirm({ title: `确认停止实例 ${instance.id}？`, content: '将执行 Stop-with-Savepoint，成功保存状态后停止正式实例。', okText: '停止', cancelText: '取消', okButtonProps: { danger: true }, onOk: async () => { try { setStoppingInstanceId(instance.id); await stopInstance(task.id, instance.id, 'savepoint'); message.success('停止请求已提交'); await reload(); } catch (error) { message.error((error as Error).message); } finally { setStoppingInstanceId(undefined); } } });
  };

  if (!task) return null;
  const runtimeInstance = selectRuntimeInstance(instances.filter((item) => item.executionMode !== 'DEBUG'));
  const instanceContent = logInstance ? <InstanceLogPanel taskId={task.id} instance={logInstance} backLabel="返回实例列表" onBack={() => setLogInstance(undefined)} /> : <>
    <InstanceListToolbar keyword={keyword} searchField={searchField} status={status} sortOrder={sortOrder} statusOptions={[{ label: '全部状态', value: 'all' }, ...Object.entries(statusLabel).map(([value, label]) => ({ value, label }))]} loading={dataLoading} refreshLabel="刷新实例" onKeywordChange={setKeyword} onSearchFieldChange={setSearchField} onStatusChange={setStatus} onSortOrderChange={setSortOrder} onReset={() => { setKeyword(''); setSearchField('all'); setStatus('all'); setSortOrder('startedAtDesc'); }} onRefresh={() => void reload()} />
    <Table rowKey="id" size="small" loading={dataLoading} dataSource={filteredInstances} pagination={{ pageSize: 6, showSizeChanger: true, pageSizeOptions: [6, 10, 20], showTotal: (value) => `共 ${value} 条` }} locale={{ emptyText: '暂无运行实例' }} scroll={{ x: 1770 }} columns={[
      { title: '实例 ID', dataIndex: 'id', width: 90, fixed: 'left' }, { title: '状态', dataIndex: 'status', width: 130, fixed: 'left', render: (value: string) => <Tag color={statusColor[value]}>{statusLabel[value] ?? value}</Tag> },
      { title: '运行模式', dataIndex: 'executionMode', width: 110, render: (value: string) => <Tag color={value === 'DEBUG' ? 'blue' : undefined}>{value === 'DEBUG' ? '调试' : '正式'}</Tag> },
      { title: 'JobID', dataIndex: 'jobId', width: 260, ellipsis: true, render: (value: string) => value || '-' }, { title: 'YARN Application ID', dataIndex: 'yarnApplicationId', width: 220, ellipsis: true, render: (value: string) => value || '-' },
      { title: 'Flink UI', dataIndex: 'trackingUrl', width: 110, render: (value: string) => value ? <Typography.Link href={value} target="_blank"><LinkOutlined /> 打开</Typography.Link> : '-' }, { title: 'Savepoint', dataIndex: 'savepointPath', width: 240, ellipsis: true, render: (value: string) => value || '-' },
      { title: '失败原因', dataIndex: 'failureMessage', width: 230, ellipsis: true, render: (value: string, row: TaskInstance) => row.status === 'failed' ? (value || '请查看运行日志') : '-' }, { title: '开始时间', dataIndex: 'startedAt', width: 180, render: (value: string) => value || '-' },
      { title: '结束时间', dataIndex: 'endedAt', width: 180, render: (value: string, row: TaskInstance) => value || (ACTIVE.includes(row.status) ? '运行中' : '-') }, { title: '操作', fixed: 'right', width: 220, render: (_: unknown, row: TaskInstance) => <Space size={12}><Typography.Link onClick={() => void inspectInstance(row)}>实例配置</Typography.Link><Typography.Link onClick={() => setLogInstance(row)}>日志</Typography.Link>{row.executionMode !== 'DEBUG' && ACTIVE.includes(row.status) && <Typography.Link type="danger" disabled={!row.managed || stoppingInstanceId === row.id} onClick={() => stopProductionInstance(row)}>停止</Typography.Link>}</Space> },
    ]} />
  </>;

  return <>
    <Drawer className="sync-task-detail-drawer" title={task.name} open onClose={onClose} placement="bottom" height="72vh" loading={loading} destroyOnHidden>
      <Tabs className="ui-flat-tabs" activeKey={activeTab} onChange={setActiveTab} items={[
        { key: 'instances', label: `实例列表（${instances.length}）`, children: instanceContent }, { key: 'detail', label: '任务详情', children: <div className="task-detail-sections"><InstanceConfigView value={task} /></div> },
        { key: 'runtime', label: '运行监控', children: <RealtimeRuntimeMonitor taskId={task.id} instance={runtimeInstance} active={activeTab === 'runtime'} checkpointIntervalSeconds={task.flinkConf.checkpointIntervalSeconds} /> },
        { key: 'alerts', label: `告警记录（${alerts.length}）`, children: <Table rowKey="id" size="small" dataSource={alerts} locale={{ emptyText: '暂无告警记录' }} columns={[{ title: '级别', dataIndex: 'severity', width: 100, render: (value: string) => <Tag color={value === 'critical' ? 'red' : 'orange'}>{value}</Tag> }, { title: '状态', dataIndex: 'status', width: 100 }, { title: '标题', dataIndex: 'title', width: 240 }, { title: '详情', dataIndex: 'detail' }, { title: '时间', dataIndex: 'createTime', width: 180 }]} /> },
        { key: 'changes', label: `变更记录（${changes.length}）`, children: <><div className="realtime-detail-toolbar"><Space><Select showSearch optionFilterProp="label" value={changeAction} onChange={setChangeAction} options={[{ label: '全部操作', value: 'all' }, ...changeActionOptions]} /><Input allowClear prefix={<SearchOutlined />} value={changeKeyword} onChange={(event) => setChangeKeyword(event.target.value)} placeholder="搜索操作人 / 操作类型 / 变更说明" /></Space></div><Table rowKey="id" size="small" dataSource={filteredChanges} pagination={false} locale={{ emptyText: '暂无变更记录' }} columns={[{ title: '操作时间', dataIndex: 'createTime', width: 180 }, { title: '操作人', dataIndex: 'operator', width: 120 }, { title: '操作类型', dataIndex: 'action', width: 150, render: (value: string) => normalizeChangeAction(value) }, { title: '变更说明', dataIndex: 'detail', render: (value: string, row: TaskChangeLog) => row.detailKind !== 'text' ? <Button className="task-change-detail-button" type="link" size="small" loading={changeDetailLoadingId === row.id} onClick={() => void showChangeDetail(row)}>{changeDetailButtonText(row.action)}</Button> : (row.summary || value || '-') }]} /></> },
        { key: 'versions', label: `版本记录（${versions.length}）`, children: <Table size="small" rowKey={(row) => String(row.id)} pagination={false} dataSource={versions} locale={{ emptyText: '暂无版本记录' }} columns={[{ title: '版本', dataIndex: 'versionNo', width: 100 }, { title: '操作人', dataIndex: 'operator', width: 160 }, { title: '创建时间', dataIndex: 'createTime', width: 200 }, { title: '配置快照', render: (_: unknown, row: Record<string, unknown>) => <Typography.Link onClick={() => void inspectVersion(row)}>查看版本配置</Typography.Link> }]} /> },
      ]} />
    </Drawer>
    <Modal className="realtime-change-detail-modal" title="变更记录详情" open={Boolean(changeDetail)} footer={null} width={1100} onCancel={() => setChangeDetail(undefined)} destroyOnHidden>{changeDetail ? <StructuredKeyValueTable value={changeDetail} /> : <Empty description="暂无变更详情" />}</Modal>
    <InstanceInspectorModal open={Boolean(inspector)} title={inspector?.title} kind={inspector?.kind} value={inspector?.value} onClose={() => setInspector(undefined)} />
  </>;
}
