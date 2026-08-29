import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Button,
  Empty,
  Input,
  Modal,
  Pagination,
  Segmented,
  Space,
  Switch,
  Table,
  Tooltip,
  Typography,
  message,
} from 'antd';
import {
  FileTextOutlined,
  ReloadOutlined,
  RobotOutlined,
  SearchOutlined,
  StopOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { cancelTaskExecution, getExecutionSummary, listExecutions } from '../../api/tasks';
import type {
  ExecutionCenterStatus,
  ExecutionSummaryVO,
  TaskExecutionVO,
} from '../../types';
import { useAutoTableActionWidth } from '../../utils/useAutoTableActionWidth';
import ExecutionDetailDrawer from './ExecutionDetailDrawer';
import TaskStatusTag, { isActiveExecution } from './TaskStatusTag';

const EMPTY_SUMMARY: ExecutionSummaryVO = {
  total: 0,
  active: 0,
  succeeded24h: 0,
  failed24h: 0,
  cancelled24h: 0,
};

const statusOptions = [
  { label: '全部', value: 'all' },
  { label: '运行中', value: 'active' },
  { label: '成功', value: 'SUCCEEDED' },
  { label: '失败', value: 'FAILED' },
  { label: '已取消', value: 'cancelled' },
];

const formatTime = (value?: string) => value
  ? new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
  }).format(new Date(value))
  : '-';

const formatDuration = (value?: number) => {
  if (value == null) return '-';
  if (value < 1000) return `${value} ms`;
  const seconds = Math.floor(value / 1000);
  if (seconds < 60) return `${seconds} s`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
  return `${Math.floor(seconds / 3600)}h ${Math.floor((seconds % 3600) / 60)}m`;
};

function parsePositive(value: string | null, fallback: number): number {
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback;
}

export default function ExecutionCenterPage() {
  const { actionColumnWidth, actionRef } = useAutoTableActionWidth({ initialWidth: 136 });
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const initialStatus = (searchParams.get('status') || 'all') as ExecutionCenterStatus;
  const [status, setStatus] = useState<ExecutionCenterStatus>(initialStatus);
  const [keyword, setKeyword] = useState(searchParams.get('keyword') || '');
  const [committedKeyword, setCommittedKeyword] = useState(searchParams.get('keyword') || '');
  const [page, setPage] = useState(parsePositive(searchParams.get('page'), 1));
  const [pageSize, setPageSize] = useState(parsePositive(searchParams.get('pageSize'), 20));
  const [items, setItems] = useState<TaskExecutionVO[]>([]);
  const [total, setTotal] = useState(0);
  const [summary, setSummary] = useState<ExecutionSummaryVO>(EMPTY_SUMMARY);
  const [loading, setLoading] = useState(false);
  const [autoRefresh, setAutoRefresh] = useState(true);
  const [selectedId, setSelectedId] = useState<number>();

  const selectedExecution = useMemo(
    () => items.find((item) => item.id === selectedId),
    [items, selectedId],
  );

  const syncQuery = useCallback((nextStatus: ExecutionCenterStatus, nextKeyword: string, nextPage: number, nextPageSize: number) => {
    const params = new URLSearchParams();
    if (nextStatus !== 'all') params.set('status', nextStatus);
    if (nextKeyword) params.set('keyword', nextKeyword);
    if (nextPage !== 1) params.set('page', String(nextPage));
    if (nextPageSize !== 20) params.set('pageSize', String(nextPageSize));
    setSearchParams(params, { replace: true });
  }, [setSearchParams]);

  const load = useCallback(async (silent = false) => {
    if (!silent) setLoading(true);
    try {
      const [pageResult, summaryResult] = await Promise.all([
        listExecutions(status, committedKeyword, page, pageSize),
        getExecutionSummary(),
      ]);
      setItems(pageResult.items);
      setTotal(pageResult.total);
      setSummary(summaryResult);
    } catch (error) {
      if (!silent) message.error(`加载执行中心失败：${(error as Error).message}`);
    } finally {
      if (!silent) setLoading(false);
    }
  }, [committedKeyword, page, pageSize, status]);

  useEffect(() => {
    syncQuery(status, committedKeyword, page, pageSize);
    void load();
  }, [load, syncQuery, status, committedKeyword, page, pageSize]);

  useEffect(() => {
    if (!autoRefresh) return undefined;
    const timer = window.setInterval(() => void load(true), 5000);
    return () => window.clearInterval(timer);
  }, [autoRefresh, load]);

  const submitSearch = () => {
    setPage(1);
    setCommittedKeyword(keyword.trim());
  };

  const cancel = (row: TaskExecutionVO) => Modal.confirm({
    title: `取消实例 ${row.id}？`,
    content: `将向 HiveServer2 发送取消请求，任务为 ${row.taskId} ${row.taskName}。`,
    okText: '停止执行',
    cancelText: '返回',
    okButtonProps: { danger: true },
    onOk: async () => {
      try {
        await cancelTaskExecution(row.id);
        message.success(`实例 ${row.id} 已提交取消请求`);
        await load();
      } catch (error) {
        message.error(`取消失败：${(error as Error).message}`);
      }
    },
  });

  const runtimeIds = (row: TaskExecutionVO) => {
    const values = [...row.applicationIds, ...row.jobIds];
    if (!values.length) return <span className="muted-text">-</span>;
    return <Tooltip title={values.join('\n')}><code className="execution-runtime-preview">{values[0]}{values.length > 1 ? ` +${values.length - 1}` : ''}</code></Tooltip>;
  };

  const columns: ColumnsType<TaskExecutionVO> = [
    { title: '实例 ID', dataIndex: 'id', width: 104, fixed: 'left', render: (value) => <span className="mono-id">{value}</span> },
    {
      title: '任务', key: 'task', width: 230, fixed: 'left', ellipsis: true,
      render: (_, row) => (
        <button type="button" className="execution-task-link" onClick={(event) => { event.stopPropagation(); navigate(`/tasks/${row.taskId}/edit`); }}>
          <span>{row.taskId}</span>{row.taskName}
        </button>
      ),
    },
    { title: '状态', dataIndex: 'status', width: 104, fixed: 'left', render: (value) => <TaskStatusTag status={value} /> },
    { title: '来源', key: 'source', width: 100, render: (_, row) => row.sourceType === 'VERSION' ? `版本 v${row.taskVersionNo}` : '生效代码' },
    { title: '执行人', dataIndex: 'requestedBy', width: 106, ellipsis: true },
    { title: '提交时间', dataIndex: 'submittedAt', width: 178, render: formatTime },
    { title: '耗时', dataIndex: 'durationMs', width: 94, render: formatDuration },
    { title: 'Query ID', dataIndex: 'queryId', width: 230, ellipsis: true, render: (value) => value ? <code>{value}</code> : '-' },
    { title: 'Application / Job', key: 'runtimeIds', width: 240, ellipsis: true, render: (_, row) => runtimeIds(row) },
    {
      title: '操作', key: 'actions', width: actionColumnWidth, fixed: 'right', className: 'table-operation-column',
      onCell: () => ({ onClick: (event) => event.stopPropagation() }),
      render: (_, row) => (
        <div ref={actionRef(row.id)} className="table-row-actions icon-row-actions">
          <Tooltip title="日志与详情"><Button type="text" icon={<FileTextOutlined />} onClick={() => setSelectedId(row.id)} /></Tooltip>
          {isActiveExecution(row.status) ? <Tooltip title="取消"><Button danger type="text" icon={<StopOutlined />} onClick={() => cancel(row)} /></Tooltip> : null}
          <Tooltip title="Agent 诊断"><Button type="text" icon={<RobotOutlined />} onClick={() => navigate(`/chat?taskId=${row.taskId}&executionId=${row.id}`)} /></Tooltip>
        </div>
      ),
    },
  ];

  return (
    <div className="data-page execution-center-page">
      <header className="data-page-header execution-center-header">
        <Typography.Title level={2}>执行中心</Typography.Title>
        <Space><span className="muted-text">自动刷新</span><Switch checked={autoRefresh} onChange={setAutoRefresh} /></Space>
      </header>

      <section className="execution-summary-band" aria-label="执行概览">
        <button type="button" onClick={() => { setStatus('all'); setPage(1); }}><span>全部实例</span><strong>{summary.total}</strong></button>
        <button type="button" onClick={() => { setStatus('active'); setPage(1); }}><span>当前运行中</span><strong className="processing">{summary.active}</strong></button>
        <button type="button" onClick={() => { setStatus('SUCCEEDED'); setPage(1); }}><span>24h 成功</span><strong className="success">{summary.succeeded24h}</strong></button>
        <button type="button" onClick={() => { setStatus('FAILED'); setPage(1); }}><span>24h 失败</span><strong className="failed">{summary.failed24h}</strong></button>
        <button type="button" onClick={() => { setStatus('cancelled'); setPage(1); }}><span>24h 已取消</span><strong>{summary.cancelled24h}</strong></button>
      </section>

      <section className="data-panel execution-center-panel">
        <div className="execution-center-toolbar">
          <Segmented
            className="ui-flat-segmented"
            value={status}
            options={statusOptions}
            onChange={(value) => { setStatus(value as ExecutionCenterStatus); setPage(1); }}
          />
          <Space className="execution-center-search">
            <Input
              allowClear
              value={keyword}
              prefix={<SearchOutlined />}
              placeholder="实例、任务、执行人、Query、Application 或 Job ID"
              onChange={(event) => setKeyword(event.target.value)}
              onPressEnter={submitSearch}
            />
            <Button type="primary" onClick={submitSearch}>查询</Button>
            <Tooltip title="刷新"><Button icon={<ReloadOutlined />} onClick={() => void load()} /></Tooltip>
          </Space>
        </div>
        <Table
          rowKey="id"
          size="middle"
          loading={loading}
          columns={columns}
          dataSource={items}
          pagination={false}
          scroll={{ x: 1284 + actionColumnWidth, y: 'calc(100vh - 405px)' }}
          rowClassName="clickable-execution-row"
          locale={{ emptyText: <Empty description="没有符合条件的真实执行实例" /> }}
          onRow={(row) => ({
            role: 'button',
            tabIndex: 0,
            onClick: () => setSelectedId(row.id),
            onKeyDown: (event) => {
              if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault();
                setSelectedId(row.id);
              }
            },
          })}
        />
        <div className="table-pagination">
          <span className="result-count">共 {total} 个实例</span>
          <Pagination
            current={page}
            pageSize={pageSize}
            total={total}
            showSizeChanger
            onChange={(nextPage, nextSize) => { setPage(nextPage); setPageSize(nextSize); }}
          />
        </div>
      </section>

      <ExecutionDetailDrawer execution={selectedExecution} onClose={() => setSelectedId(undefined)} onRefresh={() => load(true)} />
    </div>
  );
}
