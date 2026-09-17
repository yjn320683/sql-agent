import { LinkOutlined } from '@ant-design/icons';
import { Space, Table, Tag, Typography } from 'antd';
import { type ReactNode, useEffect, useMemo, useState } from 'react';
import type { TaskInstance } from '../types';
import InstanceListToolbar, { type InstanceSearchField, type InstanceSortOrder } from './InstanceListToolbar';
import InstanceLogPanel from './InstanceLogPanel';
import TaskInstanceProgressCell from './TaskInstanceProgressCell';

const ACTIVE = ['submitting', 'running', 'stopping', 'restarting'];
const RECOVERABLE = ['failed', 'canceled', 'finished', 'killed_success'];

export const realtimeInstanceStatusLabel: Record<string, string> = {
  not_running: '未运行', submitting: '提交中', running: '运行中', debug_success_running: '运行中（调试成功）',
  stopping: '停止中', restarting: '重启中', finished: '已完成', failed: '失败', canceled: '已取消', killed_success: '已停止（调试成功）',
};

const statusColor: Record<string, string> = {
  submitting: 'processing', running: 'success', debug_success_running: 'success', stopping: 'warning', restarting: 'processing',
  finished: 'success', failed: 'error', canceled: 'default', killed_success: 'success', not_running: 'default',
};

export function filterAndSortInstances(
  instances: TaskInstance[], keyword: string, searchField: InstanceSearchField,
  status: string, sortOrder: InstanceSortOrder,
): TaskInstance[] {
  return instances.filter((item) => {
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
  });
}

interface Props {
  taskId: number;
  instances: TaskInstance[];
  loading?: boolean;
  stoppingInstanceId?: number;
  header?: ReactNode;
  syncPlaceholders?: boolean;
  showDebugReport?: boolean;
  stoppableStatuses?: string[];
  onRefresh: () => void;
  onInspect: (instance: TaskInstance) => void;
  onRecover: (instance: TaskInstance) => void;
  onStop: (instance: TaskInstance) => void;
  renderExtraActions?: (instance: TaskInstance) => ReactNode;
}

/** 三类实时任务共用实例筛选、排序、日志和操作规则；类型私有操作通过 renderExtraActions 注入。 */
export default function RealtimeInstanceList({ taskId, instances, loading, stoppingInstanceId, header,
  syncPlaceholders = false, showDebugReport = false, stoppableStatuses = ACTIVE,
  onRefresh, onInspect, onRecover, onStop, renderExtraActions }: Props) {
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState('all');
  const [searchField, setSearchField] = useState<InstanceSearchField>('all');
  const [sortOrder, setSortOrder] = useState<InstanceSortOrder>('startedAtDesc');
  const [logInstance, setLogInstance] = useState<TaskInstance>();

  useEffect(() => { setKeyword(''); setStatus('all'); setSearchField('all'); setSortOrder('startedAtDesc'); setLogInstance(undefined); }, [taskId]);
  useEffect(() => {
    if (!logInstance) return;
    const latest = instances.find((item) => item.id === logInstance.id);
    if (latest && latest !== logInstance) setLogInstance(latest);
  }, [instances, logInstance]);

  const rows = useMemo(() => filterAndSortInstances(instances, keyword, searchField, status, sortOrder),
    [instances, keyword, searchField, sortOrder, status]);
  if (logInstance) return <InstanceLogPanel taskId={taskId} instance={logInstance} backLabel="返回实例列表" onBack={() => setLogInstance(undefined)} />;

  const pending = (value: string | undefined, row: TaskInstance, activeText: string) => value || (syncPlaceholders && ACTIVE.includes(row.status) ? activeText : '-');
  return <>{header}
    <InstanceListToolbar keyword={keyword} searchField={searchField} status={status} sortOrder={sortOrder}
      statusOptions={[{ label: '全部状态', value: 'all' }, ...Object.entries(realtimeInstanceStatusLabel).map(([value, label]) => ({ value, label }))]}
      loading={Boolean(loading)} refreshLabel="刷新实例" onKeywordChange={setKeyword} onSearchFieldChange={setSearchField}
      onStatusChange={setStatus} onSortOrderChange={setSortOrder}
      onReset={() => { setKeyword(''); setSearchField('all'); setStatus('all'); setSortOrder('startedAtDesc'); }} onRefresh={onRefresh} />
    <Table rowKey="id" size="small" loading={loading} dataSource={rows}
      pagination={{ pageSize: 6, showSizeChanger: true, pageSizeOptions: [6, 10, 20], showTotal: (value) => `共 ${value} 条` }}
      locale={{ emptyText: '暂无运行实例' }} scroll={{ x: showDebugReport ? 1940 : 1870 }} columns={[
        { title: '实例 ID', dataIndex: 'id', width: 90, fixed: 'left' as const },
        { title: '状态', dataIndex: 'status', width: 130, fixed: 'left' as const, render: (value: string) => <Tag color={statusColor[value]}>{realtimeInstanceStatusLabel[value] ?? value}</Tag> },
        { title: '运行模式', dataIndex: 'executionMode', width: 110, render: (value: string) => <Tag color={value === 'DEBUG' ? 'blue' : undefined}>{value === 'DEBUG' ? '调试' : '正式'}</Tag> },
        { title: '进度', width: 190, render: (_: unknown, row: TaskInstance) => <TaskInstanceProgressCell taskId={taskId} instance={row} /> },
        { title: 'JobID', dataIndex: 'jobId', width: 260, ellipsis: true, render: (value: string, row: TaskInstance) => pending(value, row, '同步中') },
        { title: 'YARN Application ID', dataIndex: 'yarnApplicationId', width: 220, ellipsis: true, render: (value: string, row: TaskInstance) => pending(value, row, '提交中') },
        { title: 'Flink UI', dataIndex: 'trackingUrl', width: 110, render: (value: string, row: TaskInstance) => value ? <Typography.Link href={value} target="_blank"><LinkOutlined /> 打开</Typography.Link> : pending(value, row, '同步中') },
        { title: 'Savepoint', dataIndex: 'savepointPath', width: 240, ellipsis: true, render: (value: string, row: TaskInstance) => pending(value, row, '停止后生成') },
        ...(showDebugReport ? [{ title: '调试报告', dataIndex: 'debugReportStatus', width: 120, render: (value: string, row: TaskInstance) => row.executionMode === 'DEBUG' ? <Tag color={value === 'PASSED' ? 'green' : value === 'FAILED' ? 'red' : 'default'}>{value || '生成中'}</Tag> : '-' }] : []),
        { title: '失败原因', dataIndex: 'failureMessage', width: 230, ellipsis: true, render: (value: string, row: TaskInstance) => row.status === 'failed' ? (value || '请查看运行日志') : '-' },
        { title: '开始时间', dataIndex: 'startedAt', width: 180, render: (value: string) => value || '-' },
        { title: '结束时间', dataIndex: 'endedAt', width: 180, render: (value: string, row: TaskInstance) => value || (ACTIVE.includes(row.status) ? '运行中' : '-') },
        { title: '操作', fixed: 'right' as const, width: renderExtraActions ? 330 : 280, render: (_: unknown, row: TaskInstance) => <Space size={12}>
          <Typography.Link onClick={() => onInspect(row)}>实例配置</Typography.Link><Typography.Link onClick={() => setLogInstance(row)}>日志</Typography.Link>
          {renderExtraActions?.(row)}
          {row.executionMode !== 'DEBUG' && RECOVERABLE.includes(row.status) && <Typography.Link disabled={!row.managed} onClick={() => onRecover(row)}>恢复</Typography.Link>}
          {row.executionMode !== 'DEBUG' && stoppableStatuses.includes(row.status) && <Typography.Link type="danger" disabled={!row.managed || stoppingInstanceId === row.id} onClick={() => onStop(row)}>停止</Typography.Link>}
        </Space> },
      ]} />
  </>;
}
