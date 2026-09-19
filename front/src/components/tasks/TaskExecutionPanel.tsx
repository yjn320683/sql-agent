import { useCallback, useEffect, useMemo, useState } from 'react';
import { Button, Empty, Input, Modal, Pagination, Segmented, Space, Table, Tooltip, message } from 'antd';
import {
  CodeOutlined,
  FileTextOutlined,
  ReloadOutlined,
  SearchOutlined,
  StopOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { useNavigate } from 'react-router-dom';
import { cancelTaskExecution, getTaskExecution, listTaskExecutions, rerunTaskExecution } from '../../api/tasks';
import type { SqlTaskVO, TaskExecutionStatus, TaskExecutionVO } from '../../types';
import { useAutoTableActionWidth } from '../../utils/useAutoTableActionWidth';
import TaskStatusTag, { isActiveExecution } from './TaskStatusTag';
import { ExecutionDetailContent } from './ExecutionDetailDrawer';

const dateTime = (value?: string) => value
  ? new Intl.DateTimeFormat('zh-CN', {
    dateStyle: 'short',
    timeStyle: 'medium',
    hour12: false,
  }).format(new Date(value))
  : '-';

const duration = (value?: number) => value == null
  ? '-'
  : value < 1000
    ? `${value}ms`
    : `${Math.round(value / 1000)}s`;

interface Props {
  taskId: number;
  task?: SqlTaskVO;
  initialExecutionId?: number;
  onExecutionChange?: (executionId?: number) => void;
}

export default function TaskExecutionPanel({ taskId, initialExecutionId, onExecutionChange }: Props) {
  const navigate = useNavigate();
  const { actionColumnWidth, actionRef } = useAutoTableActionWidth({ initialWidth: 140 });
  const [items, setItems] = useState<TaskExecutionVO[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [status, setStatus] = useState<TaskExecutionStatus | 'all'>('all');
  const [keyword, setKeyword] = useState('');
  const [committedKeyword, setCommittedKeyword] = useState('');
  const [loading, setLoading] = useState(false);
  const [selectedExecution, setSelectedExecution] = useState<TaskExecutionVO>();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const result = await listTaskExecutions(taskId, status, page, pageSize, committedKeyword);
      setItems(result.items);
      setTotal(result.total);
    } catch (error) {
      message.error(`加载实例失败：${(error as Error).message}`);
    } finally {
      setLoading(false);
    }
  }, [committedKeyword, page, pageSize, status, taskId]);

  const submitSearch = () => {
    setPage(1);
    setCommittedKeyword(keyword.trim());
  };

  useEffect(() => { void load(); }, [load]);

  const hasActive = useMemo(
    () => items.some((item) => isActiveExecution(item.status)),
    [items],
  );

  useEffect(() => {
    if (!hasActive) return undefined;
    const timer = window.setInterval(() => void load(), 2000);
    return () => window.clearInterval(timer);
  }, [hasActive, load]);

  const openExecution = async (executionId: number) => {
    try {
      setSelectedExecution(await getTaskExecution(executionId));
      onExecutionChange?.(executionId);
    }
    catch (error) { message.error(`加载实例详情失败：${(error as Error).message}`); }
  };

  useEffect(() => {
    if (!initialExecutionId || selectedExecution?.id === initialExecutionId) return;
    void openExecution(initialExecutionId);
  }, [initialExecutionId]); // eslint-disable-line react-hooks/exhaustive-deps

  const refreshSelected = async () => {
    await load();
    if (selectedExecution) setSelectedExecution(await getTaskExecution(selectedExecution.id));
  };

  const rerunSnapshot = (execution: TaskExecutionVO) => Modal.confirm({
    title: `按实例 ${execution.id} 的快照重跑？`,
    content: <div><p>将复用该实例的 SQL、版本、参数和业务日期，不会读取任务当前编辑内容。</p><p>来源：{execution.sourceType}{execution.taskVersionNo ? ` · 版本 v${execution.taskVersionNo}` : ''}</p></div>,
    okText: '创建重跑实例', cancelText: '取消',
    onOk: async () => {
      const created = await rerunTaskExecution(execution.id);
      message.success(`已创建快照重跑实例 ${created.id}`);
      setSelectedExecution(await getTaskExecution(created.id)); await load();
    },
  });

  const cancel = (row: TaskExecutionVO) => Modal.confirm({
    title: `取消实例 ${row.id}？`,
    content: '将向 HiveServer2 发送取消请求。',
    okText: '停止执行',
    okButtonProps: { danger: true },
    onOk: async () => {
      await cancelTaskExecution(row.id);
      await load();
    },
  });

  const columns: ColumnsType<TaskExecutionVO> = [
    { title: '实例 ID', dataIndex: 'id', width: 110, fixed: 'left', render: (id) => <span className="mono-id">{id}</span> },
    { title: '结果', dataIndex: 'status', width: 110, fixed: 'left', render: (value) => <TaskStatusTag status={value} /> },
    { title: '来源', key: 'source', width: 130, render: (_, row) => row.sourceType === 'REPLAY' ? `重跑 #${row.sourceExecutionId}` : row.sourceType === 'VERSION' ? `版本 v${row.taskVersionNo}` : '生效代码' },
    { title: 'Step 进度', key: 'stepProgress', width: 120, render: (_, row) => `${row.succeededSteps}/${row.totalSteps}` },
    { title: '执行人', dataIndex: 'requestedBy', width: 110 },
    { title: '提交时间', dataIndex: 'submittedAt', width: 170, render: dateTime },
    { title: '开始时间', dataIndex: 'startedAt', width: 170, render: dateTime },
    { title: '结束时间', dataIndex: 'finishedAt', width: 170, render: dateTime },
    { title: '耗时', dataIndex: 'durationMs', width: 90, render: duration },
    { title: 'Query ID', dataIndex: 'queryId', width: 220, ellipsis: true, render: (value) => value ? <code>{value}</code> : '-' },
    {
      title: 'Application / Job',
      key: 'runtimeIds',
      width: 250,
      ellipsis: true,
      render: (_, row) => (
        <span className="runtime-id-summary">
          {[...row.applicationIds, ...row.jobIds].join(', ') || '-'}
        </span>
      ),
    },
    {
      title: '操作',
      key: 'actions',
      width: actionColumnWidth,
      fixed: 'right',
      className: 'table-operation-column',
      onCell: () => ({ onClick: (event) => event.stopPropagation() }),
      render: (_, row) => (
        <div ref={actionRef(row.id)} className="table-row-actions icon-row-actions">
          <Tooltip title="查看日志">
            <Button
              type="text"
              icon={<FileTextOutlined />}
              onClick={() => void openExecution(row.id)}
            />
          </Tooltip>
          {isActiveExecution(row.status) ? (
            <Tooltip title="取消">
              <Button danger type="text" icon={<StopOutlined />} onClick={() => cancel(row)} />
            </Tooltip>
          ) : null}
          <Tooltip title="Agent 诊断">
            <Button
              type="text"
              icon={<CodeOutlined />}
              onClick={() => navigate(`/chat?taskId=${taskId}&executionId=${row.id}`)}
            />
          </Tooltip>
        </div>
      ),
    },
  ];

  if (selectedExecution) {
    return (
      <div className="execution-panel execution-panel-detail">
        <ExecutionDetailContent
          execution={selectedExecution}
          onRefresh={refreshSelected}
          onBack={() => {
            setSelectedExecution(undefined);
            onExecutionChange?.();
          }}
          onCancel={() => cancel(selectedExecution)}
          onRerun={['FAILED', 'CANCELLED'].includes(selectedExecution.status) ? () => rerunSnapshot(selectedExecution) : undefined}
        />
      </div>
    );
  }

  return (
    <div className="execution-panel">
      <div className="execution-panel-toolbar">
        <Space wrap>
          <Segmented
            className="ui-flat-segmented"
            value={status}
            onChange={(value) => {
              setPage(1);
              setStatus(value as TaskExecutionStatus | 'all');
            }}
            options={[
              { label: '全部', value: 'all' },
              { label: '运行中', value: 'RUNNING' },
              { label: '成功', value: 'SUCCEEDED' },
              { label: '失败', value: 'FAILED' },
              { label: '已取消', value: 'CANCELLED' },
            ]}
          />
          <Input allowClear value={keyword} prefix={<SearchOutlined />} placeholder="搜索实例、执行人、Query 或 Job ID" onChange={(event) => setKeyword(event.target.value)} onPressEnter={submitSearch} />
          <Button type="primary" onClick={submitSearch}>查询</Button>
        </Space>
        <Space>
          <span className="result-count">共 {total} 个实例</span>
          <Tooltip title="刷新">
            <Button icon={<ReloadOutlined />} onClick={() => void load()} />
          </Tooltip>
        </Space>
      </div>
      <Table
        rowKey="id"
        size="middle"
        columns={columns}
        dataSource={items}
        loading={loading}
        pagination={false}
        scroll={{ x: 1460 + actionColumnWidth, y: 'calc(100vh - 390px)' }}
        locale={{ emptyText: <Empty description={committedKeyword ? '没有匹配的执行实例' : '暂无执行实例'} /> }}
        rowClassName="clickable-task-row"
        onRow={(row) => ({ onClick: () => void openExecution(row.id) })}
      />
      <div className="table-pagination">
        <Pagination
          current={page}
          pageSize={pageSize}
          total={total}
          showSizeChanger
          onChange={(next, size) => {
            setPage(next);
            setPageSize(size);
          }}
        />
      </div>
    </div>
  );
}
