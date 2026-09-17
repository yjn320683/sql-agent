import { useCallback, useEffect, useState } from 'react';
import { Alert, Button, Empty, Skeleton, Table, Tabs, Tag, Tooltip, Typography } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { getTaskExecutionDiagnosticReport, getTaskExecutionDiagnostics } from '../../api/tasks';
import type { DiagnosticReport, TaskExecutionDiagnosticsVO } from '../../types';
import DiagnosticReportView from '../diagnostics/DiagnosticReportView';

interface Props {
  executionId: number;
}

type JobRow = TaskExecutionDiagnosticsVO['jobs'][number];

const METRIC_LABELS: Record<string, string> = {
  jobCount: 'Job 数',
  retainedJobCount: '已保留详情',
  expiredJobCount: '已过期详情',
  durationMs: '端到端 Job 耗时',
  sumJobDurationMs: 'Job 累计耗时',
  mapTaskCount: 'Map Task',
  reduceTaskCount: 'Reduce Task',
  failedTaskCount: '失败 Task',
  HDFS_BYTES_READ: 'HDFS 读取',
  HDFS_BYTES_WRITTEN: 'HDFS 写入',
  REDUCE_SHUFFLE_BYTES: 'Shuffle 数据量',
  SPILLED_RECORDS: 'Spill 记录数',
  CPU_MILLISECONDS: 'CPU 时间',
  GC_TIME_MILLIS: 'GC 时间',
};

function formatDuration(value: unknown): string {
  if (value == null || value === '') return '-';
  const milliseconds = Number(value);
  if (!Number.isFinite(milliseconds)) return '-';
  if (milliseconds < 1000) return `${milliseconds} ms`;
  if (milliseconds < 60_000) return `${(milliseconds / 1000).toFixed(1)} s`;
  return `${Math.floor(milliseconds / 60_000)} min ${Math.floor((milliseconds % 60_000) / 1000)} s`;
}

function formatBytes(value: unknown): string {
  const bytes = Number(value);
  if (!Number.isFinite(bytes)) return '-';
  const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];
  let current = bytes;
  let unit = 0;
  while (current >= 1024 && unit < units.length - 1) {
    current /= 1024;
    unit += 1;
  }
  return `${current >= 10 || unit === 0 ? current.toFixed(0) : current.toFixed(1)} ${units[unit]}`;
}

function formatMetric(name: string, value: number | null): string {
  if (value == null) return '-';
  if (name.endsWith('_BYTES')) return formatBytes(value);
  if (name.endsWith('_MILLISECONDS') || name.endsWith('_MILLIS') || name.endsWith('DurationMs') || name === 'durationMs') {
    return formatDuration(value);
  }
  return value.toLocaleString('zh-CN');
}

function taskDistribution(row: JobRow, type: 'map' | 'reduce'): string {
  const summary = row.tasks?.[type];
  if (!summary?.count) return '-';
  const duration = summary.durationMs || {};
  return `${summary.count} 个 · p95 ${formatDuration(duration.p95)} · max ${formatDuration(duration.max)}`;
}

function stateColor(state?: string): string | undefined {
  if (state === 'SUCCEEDED') return 'success';
  if (state === 'FAILED') return 'error';
  if (state === 'RUNNING') return 'processing';
  if (state === 'KILLED') return 'default';
  return undefined;
}

export default function ExecutionDiagnosticsPanel({ executionId }: Props) {
  const [data, setData] = useState<TaskExecutionDiagnosticsVO>();
  const [report, setReport] = useState<DiagnosticReport>();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [facts, diagnosis] = await Promise.allSettled([
        getTaskExecutionDiagnostics(executionId), getTaskExecutionDiagnosticReport(executionId),
      ]);
      if (facts.status === 'fulfilled') setData(facts.value); else setData(undefined);
      if (diagnosis.status === 'fulfilled') setReport(diagnosis.value); else setReport(undefined);
      if (facts.status === 'rejected' && diagnosis.status === 'rejected') setError((diagnosis.reason as Error).message);
    } finally {
      setLoading(false);
    }
  }, [executionId]);

  useEffect(() => { void load(); }, [load]);

  const columns: ColumnsType<JobRow> = [
    {
      title: 'Job / Application',
      width: 260,
      fixed: 'left',
      render: (_, row) => (
        <div className="diagnostics-job-id">
          <Typography.Text copyable code>{String(row.job.id || '-')}</Typography.Text>
          <Typography.Text type="secondary">{String(row.job.applicationId || '-')}</Typography.Text>
        </div>
      ),
    },
    { title: '状态', width: 105, fixed: 'left', render: (_, row) => <Tag color={stateColor(row.job.state)}>{String(row.job.state || 'UNKNOWN')}</Tag> },
    { title: '队列', width: 150, render: (_, row) => String(row.job.queue || '-') },
    { title: '耗时', width: 120, render: (_, row) => formatDuration(row.job.durationMs) },
    { title: 'Map 分布', width: 245, render: (_, row) => taskDistribution(row, 'map') },
    { title: 'Reduce 分布', width: 245, render: (_, row) => taskDistribution(row, 'reduce') },
    { title: 'Shuffle', width: 120, render: (_, row) => formatBytes(row.counters.REDUCE_SHUFFLE_BYTES) },
    { title: 'Spill', width: 120, render: (_, row) => Number(row.counters.SPILLED_RECORDS || 0).toLocaleString('zh-CN') },
    {
      title: 'YARN',
      width: 110,
      render: (_, row) => row.yarnApplication
        ? <Tag color="success">已保留</Tag>
        : <Tag>已过期</Tag>,
    },
  ];

  const refreshReport = async () => {
    setLoading(true);
    try { setReport(await getTaskExecutionDiagnosticReport(executionId, true)); }
    catch (requestError) { setError((requestError as Error).message); }
    finally { setLoading(false); }
  };

  if (loading && !data && !report) return <Skeleton active paragraph={{ rows: 8 }} />;
  if (error) return <Alert type="error" showIcon message="加载运行诊断失败" description={error} action={<Button onClick={() => void load()}>重试</Button>} />;
  const metrics = data ? Object.entries({
    jobCount: data.aggregate.jobCount,
    retainedJobCount: data.aggregate.retainedJobCount ?? data.jobs.length,
    expiredJobCount: data.aggregate.expiredJobCount ?? 0,
    ...(data.aggregate.metrics || {}),
  }) : [];
  const facts = data ? <div className="execution-diagnostics">
    <div className="execution-diagnostics-toolbar">
      <div><Typography.Title level={5}>JobHistory / YARN 运行事实</Typography.Title><Typography.Text type="secondary">数据获取于 {data.fetchedAt.replace('T', ' ').slice(0, 19)}</Typography.Text></div>
      <Tooltip title="重新采集"><Button icon={<ReloadOutlined />} loading={loading} onClick={() => void load()} /></Tooltip>
    </div>
    {!data.complete || data.warnings.length ? <Alert type="warning" showIcon message="诊断数据不完整" description={(data.warnings.length ? data.warnings : data.missingReasons).join('；')} /> : null}
    {metrics.length ? <div className="execution-diagnostics-metrics">{metrics.map(([name, value]) => <div key={name} title={name}><span>{METRIC_LABELS[name] || name}</span><strong>{formatMetric(name, value)}</strong></div>)}</div> : null}
    <Table rowKey={(row) => String(row.job.id)} size="small" pagination={false} loading={loading} dataSource={data.jobs} columns={columns} scroll={{ x: 1490 }} locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="该实例没有可诊断的 MapReduce Job" /> }} />
  </div> : <Alert showIcon type="warning" message="JobHistory / YARN 原始事实暂不可用" />;
  return (
    <Tabs className="ui-flat-tabs" items={[
      { key: 'report', label: '诊断结论', children: <DiagnosticReportView report={report} loading={loading} onRefresh={() => void refreshReport()} /> },
      { key: 'facts', label: '原始运行事实', children: facts },
    ]} />
  );
}
