import { LinkOutlined, QuestionCircleOutlined, ReloadOutlined } from '@ant-design/icons';
import { Alert, Button, Descriptions, Empty, Progress, Space, Spin, Statistic, Table, Tag, Tooltip, Typography } from 'antd';
import type { ReactNode } from 'react';
import { useCallback, useEffect, useRef, useState } from 'react';
import { getInstanceInfo } from '../api';
import type { TaskInstance, TaskManagerRuntimeResource, TaskRuntimeCheckpointDetail, TaskRuntimeCheckpoints, TaskRuntimeResources, TaskRuntimeSnapshot, TaskRuntimeVertexMetric } from '../types';

interface Props {
  taskId: number;
  instance?: TaskInstance;
  active?: boolean;
  checkpointIntervalSeconds?: number;
}

const LIVE = new Set(['running', 'restarting']);
const normalizeStatus = (status?: string) => (status ?? '').toLowerCase();
const valueOrDash = (value?: string | number | null) => value === undefined || value === null || value === '' ? '-' : value;
const formatNumber = (value?: number | null) => value === undefined || value === null ? '-' : Number.isInteger(value) ? String(value) : value.toFixed(2);
const formatRate = (value?: number | null) => value === undefined || value === null ? '-' : `${formatNumber(value)} records/s`;
const formatDuration = (value?: number | null) => {
  if (value === undefined || value === null) return '-';
  if (value >= 3_600_000) return `${(value / 3_600_000).toFixed(1)} h`;
  if (value >= 60_000) return `${(value / 60_000).toFixed(1)} min`;
  return `${(value / 1000).toFixed(1)} s`;
};
const formatBytes = (value?: number | null) => {
  if (value === undefined || value === null) return '-';
  if (value >= 1024 ** 3) return `${(value / 1024 ** 3).toFixed(2)} GB`;
  if (value >= 1024 ** 2) return `${(value / 1024 ** 2).toFixed(1)} MB`;
  if (value >= 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${value} B`;
};
const formatTimestamp = (value?: number | string | null) => {
  if (!value) return '-';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString('zh-CN', { hour12: false });
};
const msPerSecondToPercent = (value?: number | null) => Math.min(100, Math.max(0, ((value ?? 0) / 1000) * 100));
const metricTitle = (label: string, description: string) => <Space size={5}>
  <span>{label}</span><Tooltip title={description}><QuestionCircleOutlined className="sync-runtime-metric-help" /></Tooltip>
</Space>;
const metricValue = (content: ReactNode, value?: number | string | null, reason?: string) => <Tooltip title={value === undefined || value === null || value === '' ? reason || '指标暂不可用' : undefined}>
  <span className="sync-runtime-metric-value">{content}</span>
</Tooltip>;

const renderFlinkState = (state?: string) => {
  const value = (state || '').toUpperCase();
  const color = value === 'RUNNING' ? 'green'
    : value === 'RESTARTING' || value === 'RECONCILING' ? 'gold'
      : value === 'FAILED' ? 'red'
        : value === 'CANCELED' || value === 'CANCELLED' ? 'default' : 'blue';
  return <Tag color={color}>{value || '-'}</Tag>;
};

const renderInstanceStatus = (status?: string) => {
  const statuses: Record<string, { color: string; text: string }> = {
    submitting: { color: 'processing', text: '提交中' }, submitted: { color: 'processing', text: '已提交' },
    running: { color: 'green', text: '运行中' }, stopping: { color: 'orange', text: '停止中' },
    restarting: { color: 'gold', text: '重启中' }, canceled: { color: 'default', text: '已取消' },
    cancelled: { color: 'default', text: '已取消' }, stopped: { color: 'default', text: '已停止' },
    finished: { color: 'blue', text: '已完成' }, failed: { color: 'red', text: '失败' },
  };
  const config = statuses[normalizeStatus(status)] ?? { color: 'red', text: status || '未知' };
  return <Tag color={config.color}>{config.text}</Tag>;
};

const renderBackpressure = (value?: number | null, reason?: string) => {
  if (value === undefined || value === null) return metricValue('-', value, reason);
  const percent = Math.round(msPerSecondToPercent(value));
  return <Space size={8} className="sync-runtime-progress-cell">
    <Progress percent={percent} size="small" status={percent >= 50 ? 'exception' : percent >= 20 ? 'active' : 'normal'} />
    <Typography.Text type={percent >= 50 ? 'danger' : undefined}>{percent}%</Typography.Text>
  </Space>;
};

export default function RealtimeRuntimeMonitor({ taskId, instance, active = true, checkpointIntervalSeconds = 60 }: Props) {
  const [runtime, setRuntime] = useState<TaskRuntimeSnapshot>();
  const [resources, setResources] = useState<TaskRuntimeResources>();
  const [checkpoints, setCheckpoints] = useState<TaskRuntimeCheckpoints>();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const requestRef = useRef<{ key: string; request: Promise<void> } | null>(null);
  const sequenceRef = useRef(0);
  const statusLive = Boolean(instance && LIVE.has(normalizeStatus(instance.status)));
  // managed 只限制状态刷新与启停操作；运行指标属于只读观测，导入实例仍应完整展示。
  const live = statusLive;
  const instanceKey = instance ? String(instance.id) : '';

  const load = useCallback((silent = false) => {
    if (!instance) return Promise.resolve();
    if (requestRef.current?.key === instanceKey) return requestRef.current.request;
    if (!live) { setError(''); return Promise.resolve(); }
    if (!instance.jobId || !instance.trackingUrl) {
      setError('运行实例还没有 JobID 或 Flink Tracking URL，等待后台同步后再展示实时指标。');
      return Promise.resolve();
    }
    if (!silent) setLoading(true);
    setError('');
    const sequence = sequenceRef.current;
    const request = Promise.allSettled([
      getInstanceInfo(taskId, instance.id, 'runtime'), getInstanceInfo(taskId, instance.id, 'resources'),
      getInstanceInfo(taskId, instance.id, 'checkpoints'),
    ]).then(([runtimeResult, resourcesResult, checkpointsResult]) => {
      if (sequence !== sequenceRef.current) return;
      const errors: string[] = [];
      if (runtimeResult.status === 'fulfilled') setRuntime(runtimeResult.value as TaskRuntimeSnapshot);
      else errors.push(runtimeResult.reason instanceof Error ? runtimeResult.reason.message : '运行指标读取失败');
      if (resourcesResult.status === 'fulfilled') setResources(resourcesResult.value as TaskRuntimeResources);
      else errors.push(resourcesResult.reason instanceof Error ? resourcesResult.reason.message : '资源指标读取失败');
      if (checkpointsResult.status === 'fulfilled') setCheckpoints(checkpointsResult.value as TaskRuntimeCheckpoints);
      else errors.push(checkpointsResult.reason instanceof Error ? checkpointsResult.reason.message : 'Checkpoint 读取失败');
      setError(errors.join('；'));
    }).finally(() => {
      if (requestRef.current?.request === request) requestRef.current = null;
      if (sequence === sequenceRef.current && !silent) setLoading(false);
    });
    requestRef.current = { key: instanceKey, request };
    return request;
  }, [instance, instanceKey, live, taskId]);

  useEffect(() => {
    sequenceRef.current += 1;
    requestRef.current = null;
    setRuntime(undefined); setResources(undefined); setCheckpoints(undefined);
    setLoading(false); setError('');
  }, [instanceKey]);
  useEffect(() => { if (active) void load(false); }, [active, load]);
  useEffect(() => {
    if (!active || !live) return undefined;
    const timer = window.setInterval(() => void load(true), 5000);
    return () => window.clearInterval(timer);
  }, [active, live, load]);

  if (!instance) return <Empty description="当前任务暂无生产实例" />;

  const latestCompleted = checkpoints?.latest?.completed;
  const latestFailed = checkpoints?.latest?.failed;
  const checkpointHealth = (completed?: TaskRuntimeCheckpointDetail, failed?: TaskRuntimeCheckpointDetail) => {
    if (!statusLive) return { text: '终态', color: 'default', reason: '当前实例不是运行态' };
    if (!checkpoints) return { text: '-', color: 'default', reason: 'Checkpoint REST 数据暂不可用' };
    const latestAck = completed?.latest_ack_timestamp;
    const intervalMs = Math.max(1, (resources?.configured?.checkpointIntervalSeconds ?? checkpointIntervalSeconds) * 1000);
    const latestFailureTime = failed?.latest_ack_timestamp ?? failed?.trigger_timestamp;
    if (latestFailureTime && (!latestAck || latestFailureTime > latestAck)) return { text: '最近失败', color: 'red', reason: failed?.failure_message || '最近一次 Checkpoint 失败' };
    if (!latestAck) {
      if (runtime?.uptimeMs !== undefined && runtime.uptimeMs <= intervalMs * 2) return { text: '等待首次', color: 'gold', reason: 'Job 启动不足两个 Checkpoint 周期' };
      return { text: '无成功', color: 'red', reason: '运行已超过两个周期，仍没有成功 Checkpoint' };
    }
    const ageMs = Date.now() - latestAck;
    if (ageMs > intervalMs * 5) return { text: '超时', color: 'red', reason: '最近成功时间已超过五个 Checkpoint 周期' };
    if (ageMs > intervalMs * 2) return { text: '偏慢', color: 'gold', reason: '最近成功时间已超过两个 Checkpoint 周期' };
    return { text: '正常', color: 'green', reason: '最近两个周期内存在成功 Checkpoint' };
  };
  const health = checkpointHealth(latestCompleted, latestFailed);
  const sync = runtime?.sync;
  const unavailableReasons = sync?.unavailableReasons ?? {};
  const unavailableReason = (key: string, fallback: string) => unavailableReasons[key] || fallback;
  const vertexRows = runtime?.vertices ?? [];
  const taskManagers = resources?.flink?.taskManagers ?? [];
  const roleText = { source: 'Source', sink: 'Sink', source_sink: 'Source / Sink', operator: '中间算子' } as const;
  const monitorError = runtime?.updatedAt ? `${error}；当前保留 ${formatTimestamp(runtime.updatedAt)} 的成功快照。` : error;

  return <div className="sync-runtime-monitor">
    <div className="sync-runtime-toolbar">
      <Space size={10} wrap>
        <Typography.Text strong>实例 {instance.id}</Typography.Text>{renderInstanceStatus(instance.status)}
        <Typography.Text type="secondary">JobID：{instance.jobId || '-'}</Typography.Text>
        <Typography.Text type="secondary">YARN：{instance.yarnApplicationId || '-'}</Typography.Text>
      </Space>
      <Space size={8}>
        {runtime?.updatedAt && <Typography.Text type="secondary" className="sync-runtime-updated-at">更新于 {formatTimestamp(runtime.updatedAt)}</Typography.Text>}
        {instance.trackingUrl && <Button icon={<LinkOutlined />} size="small" href={instance.trackingUrl} target="_blank">Flink UI</Button>}
        <Button icon={<ReloadOutlined />} loading={loading} disabled={!live} size="small" onClick={() => void load(false)}>刷新</Button>
      </Space>
    </div>
    {!instance.managed && <Alert showIcon type="info" message="历史导入实例为只读观测" description="可以查看 Flink 指标、资源、Checkpoint 和日志；新平台不会刷新任务状态、停止或接管参考环境作业。" />}
    {!live && <Alert showIcon type="info" message="当前实例不是运行态" description="实时指标只读取 running / restarting 的 Flink Job。历史实例请查看实例列表里的最终状态、失败原因和运行日志。" />}
    {error && <Alert showIcon type="warning" message="运行监控数据未完整返回" description={monitorError} />}
    {runtime?.latestException?.exception && <Alert showIcon type="error" message="最近异常" description={<Tooltip title={runtime.latestException.exception}><span>{runtime.latestException.exception}</span></Tooltip>} />}
    <Spin spinning={loading && live}>
      <div className="sync-runtime-card-grid">
        <div><span>{metricTitle('Job 状态', 'Flink Job 当前执行状态')}</span><strong>{renderFlinkState(runtime?.status ?? instance.status)}</strong></div>
        <div><span>{metricTitle('Source 输出', '所有 Source 顶点 numRecordsOutPerSecond 的 subtask 求和')}</span><strong>{metricValue(formatRate(sync?.sourceOutputRate), sync?.sourceOutputRate, unavailableReason('sourceOutputRate', 'Source 输出速率暂不可用'))}</strong></div>
        <div><span>{metricTitle('Paimon 提交', 'Paimon Committer 成功提交后的 sink.numRecordsOutPerSecond，按 subtask 求和')}</span><strong>{metricValue(formatRate(sync?.committedRate), sync?.committedRate, unavailableReason('committedRate', 'Paimon 提交速率暂不可用'))}</strong></div>
        <div><span>{metricTitle('Checkpoint 健康', '结合最近成功、最近失败、配置周期和运行时长判断')}</span><strong><Tooltip title={health.reason}><Tag color={health.color}>{health.text}</Tag></Tooltip></strong></div>
      </div>
      <div className="sync-runtime-section">
        <div className="sync-runtime-section-title"><Typography.Text strong>数据流</Typography.Text><Typography.Text type="secondary">Flink Subtask 聚合 / Paimon Committer</Typography.Text></div>
        <div className="runtime-stat-grid sync-runtime-stat-grid">
          <div><Statistic title={metricTitle('Sink 接收', '所有终端 Sink 顶点 numRecordsInPerSecond 的 subtask 求和')} value={valueOrDash(sync?.sinkInputRate)} suffix={sync?.sinkInputRate == null ? undefined : 'records/s'} valueRender={(node) => metricValue(node, sync?.sinkInputRate, unavailableReason('sinkInputRate', 'Sink 接收速率暂不可用'))} /></div>
          <div><Statistic title={metricTitle('已提交记录', 'Paimon Committer 成功提交后累计的 sink.numRecordsOut')} value={valueOrDash(sync?.committedRecords)} valueRender={(node) => metricValue(node, sync?.committedRecords, unavailableReason('committedRecords', 'Paimon 累计提交记录暂不可用'))} /></div>
          <div><Statistic title={metricTitle('最近提交耗时', 'Paimon commit.lastCommitDuration，多个 Committer 取最大值')} value={formatDuration(sync?.lastCommitDurationMs)} valueRender={(node) => metricValue(node, sync?.lastCommitDurationMs, unavailableReason('lastCommitDurationMs', '最近提交耗时暂不可用'))} /></div>
          <div><Statistic title={metricTitle('最近提交尝试', 'Paimon commit.lastCommitAttempts，多个 Committer 取最大值')} value={valueOrDash(sync?.lastCommitAttempts)} valueRender={(node) => metricValue(node, sync?.lastCommitAttempts, unavailableReason('lastCommitAttempts', '最近提交尝试次数暂不可用'))} /></div>
          <div><Statistic title={metricTitle('重启次数', 'Flink Job 指标 numRestarts')} value={valueOrDash(runtime?.restartCount)} valueRender={(node) => metricValue(node, runtime?.restartCount, runtime?.restartCountUnavailableReason || 'Flink Job 未提供 numRestarts')} /></div>
          <div><Statistic title={metricTitle('运行时长', 'Flink Job REST 返回的 duration')} value={formatDuration(runtime?.uptimeMs)} valueRender={(node) => metricValue(node, runtime?.uptimeMs, 'Flink Job 未提供 duration')} /></div>
          <div><Statistic title={metricTitle('最大 Busy', '所有算子 subtask 的 busyTimeMsPerSecond 最大值，1000ms/s 为 100%')} value={sync?.busyMaxMsPerSecond == null ? '-' : Math.round(msPerSecondToPercent(sync.busyMaxMsPerSecond))} suffix={sync?.busyMaxMsPerSecond == null ? undefined : '%'} valueRender={(node) => metricValue(node, sync?.busyMaxMsPerSecond, unavailableReason('busyMaxMsPerSecond', 'Busy 指标暂不可用'))} /></div>
          <div><Statistic title={metricTitle('最大 Backpressure', '所有算子 subtask 的 backPressuredTimeMsPerSecond 最大值，1000ms/s 为 100%')} value={sync?.backpressuredMaxMsPerSecond == null ? '-' : Math.round(msPerSecondToPercent(sync.backpressuredMaxMsPerSecond))} suffix={sync?.backpressuredMaxMsPerSecond == null ? undefined : '%'} valueRender={(node) => metricValue(node, sync?.backpressuredMaxMsPerSecond, unavailableReason('backpressuredMaxMsPerSecond', 'Backpressure 指标暂不可用'))} /></div>
        </div>
      </div>
      <div className="sync-runtime-two-columns">
        <div className="sync-runtime-section">
          <div className="sync-runtime-section-title"><Typography.Text strong>Checkpoint</Typography.Text><Typography.Text type="secondary">{live ? health.reason : '终态实例不再采集实时 Checkpoint 指标'}</Typography.Text></div>
          <Descriptions bordered size="small" column={2}>
            <Descriptions.Item label="成功次数">{valueOrDash(checkpoints?.counts?.completed)}</Descriptions.Item><Descriptions.Item label="失败次数">{valueOrDash(checkpoints?.counts?.failed)}</Descriptions.Item>
            <Descriptions.Item label="最近成功">{formatTimestamp(latestCompleted?.latest_ack_timestamp)}</Descriptions.Item><Descriptions.Item label="最近耗时">{formatDuration(latestCompleted?.end_to_end_duration)}</Descriptions.Item>
            <Descriptions.Item label="状态大小">{formatBytes(latestCompleted?.state_size)}</Descriptions.Item><Descriptions.Item label="最近失败">{formatTimestamp(latestFailed?.latest_ack_timestamp ?? latestFailed?.trigger_timestamp)}</Descriptions.Item>
            <Descriptions.Item label="失败原因" span={2}><Typography.Text ellipsis={{ tooltip: latestFailed?.failure_message }}>{latestFailed?.failure_message || '-'}</Typography.Text></Descriptions.Item>
          </Descriptions>
        </div>
        <div className="sync-runtime-section">
          <div className="sync-runtime-section-title"><Typography.Text strong>资源</Typography.Text><Typography.Text type="secondary">确认 TaskManager、Slot 和内存是否正常</Typography.Text></div>
          <Descriptions bordered size="small" column={2}>
            <Descriptions.Item label="YARN 状态">{resources?.yarn?.state || '-'}</Descriptions.Item><Descriptions.Item label="Container">{valueOrDash(resources?.yarn?.runningContainers)}</Descriptions.Item>
            <Descriptions.Item label="Slot">{valueOrDash(resources?.flink?.usedSlots)} / {valueOrDash(resources?.flink?.totalSlots)}</Descriptions.Item><Descriptions.Item label="TM 数量">{valueOrDash(resources?.flink?.taskManagerCount)}</Descriptions.Item>
            <Descriptions.Item label="JM Heap">{formatBytes(resources?.flink?.jobManagerHeapUsed)}</Descriptions.Item><Descriptions.Item label="JM Non-Heap">{formatBytes(resources?.flink?.jobManagerNonHeapUsed)}</Descriptions.Item>
          </Descriptions>
        </div>
      </div>
      <div className="sync-runtime-section">
        <div className="sync-runtime-section-title"><Typography.Text strong>算子健康</Typography.Text><Typography.Text type="secondary">定位吞吐下降和反压来自哪个 operator</Typography.Text></div>
        <Table<TaskRuntimeVertexMetric> rowKey="id" size="small" pagination={false} dataSource={vertexRows} scroll={{ x: 1420 }} locale={{ emptyText: live ? '暂无算子指标' : '终态实例不展示实时算子指标' }} columns={[
          { title: 'Operator', dataIndex: 'name', width: 360, ellipsis: true, render: (value, row) => <Tooltip title={row.unavailableReason || value || row.id}><span>{value || row.id}</span></Tooltip> },
          { title: '角色', dataIndex: 'role', width: 120, render: (value: TaskRuntimeVertexMetric['role']) => <Tag>{value ? roleText[value] : '-'}</Tag> },
          { title: '状态', dataIndex: 'status', width: 120, render: renderFlinkState }, { title: '并行度', dataIndex: 'parallelism', width: 90, render: valueOrDash },
          { title: metricTitle('输入', '该顶点 numRecordsInPerSecond 的 subtask 求和'), dataIndex: 'inputRate', width: 150, render: (value, row) => metricValue(formatRate(value), value, row.unavailableReason) },
          { title: metricTitle('输出', '该顶点 numRecordsOutPerSecond 的 subtask 求和'), dataIndex: 'outputRate', width: 150, render: (value, row) => metricValue(formatRate(value), value, row.unavailableReason) },
          { title: metricTitle('提交', '该顶点 Paimon sink.numRecordsOutPerSecond 的 subtask 求和'), dataIndex: 'commitRate', width: 150, render: (value, row) => metricValue(formatRate(value), value, row.unavailableReason || '该算子未提供 Paimon 提交指标') },
          { title: metricTitle('Busy 最大', '该顶点最忙 subtask 的 busyTimeMsPerSecond'), dataIndex: 'busyMaxMsPerSecond', width: 190, render: (value, row) => renderBackpressure(value, row.unavailableReason) },
          { title: metricTitle('Backpressure 最大', '该顶点反压最高 subtask 的 backPressuredTimeMsPerSecond'), dataIndex: 'backpressuredMaxMsPerSecond', width: 220, render: (value, row) => renderBackpressure(value, row.unavailableReason) },
        ]} />
      </div>
      {taskManagers.length > 0 && <div className="sync-runtime-section">
        <div className="sync-runtime-section-title"><Typography.Text strong>TaskManager</Typography.Text></div>
        <Table<TaskManagerRuntimeResource> rowKey="id" size="small" pagination={false} dataSource={taskManagers} columns={[
          { title: 'TaskManager', dataIndex: 'id', ellipsis: true }, { title: '地址', dataIndex: 'path', ellipsis: true },
          { title: 'Heap', dataIndex: 'heapUsed', width: 120, render: formatBytes }, { title: 'Managed', dataIndex: 'managedMemoryUsed', width: 120, render: formatBytes },
          { title: 'Network', dataIndex: 'networkMemoryUsed', width: 120, render: formatBytes }, { title: 'Slot', width: 100, render: (_, row) => `${(row.slots ?? 0) - (row.freeSlots ?? 0)} / ${row.slots ?? 0}` },
        ]} />
      </div>}
    </Spin>
  </div>;
}
