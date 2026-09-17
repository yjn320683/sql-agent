import { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert,
  Button,
  Descriptions,
  Drawer,
  Empty,
  Input,
  Select,
  Space,
  Switch,
  Table,
  Tabs,
  Tooltip,
  Typography,
  message,
} from 'antd';
import {
  ArrowLeftOutlined,
  CopyOutlined,
  DownloadOutlined,
  FileTextOutlined,
  PlayCircleOutlined,
  ReloadOutlined,
  RobotOutlined,
  StopOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { getExecutionLogs, getExecutionStepLogs } from '../../api/tasks';
import type { SqlTaskStepVO, TaskExecutionVO } from '../../types';
import TaskStatusTag, { isActiveExecution } from './TaskStatusTag';
import ExecutionDiagnosticsPanel from './ExecutionDiagnosticsPanel';

interface Props {
  execution?: TaskExecutionVO;
  onClose: () => void;
  onRefresh: () => Promise<void> | void;
}

interface ContentProps {
  execution: TaskExecutionVO;
  onRefresh: () => Promise<void> | void;
  onBack?: () => void;
  onCancel?: () => void;
  onRerun?: () => void;
}

const formatTime = (value?: string) => value ? value.replace('T', ' ').slice(0, 19) : '-';

const formatDuration = (value?: number) => {
  if (value == null) return '-';
  if (value < 1000) return `${value} ms`;
  const seconds = Math.floor(value / 1000);
  if (seconds < 60) return `${seconds} s`;
  return `${Math.floor(seconds / 60)} min ${seconds % 60} s`;
};

function RuntimeIds({ values }: { values: string[] }) {
  if (!values.length) return <span className="muted-text">-</span>;
  return (
    <Space direction="vertical" size={2}>
      {values.map((value) => <Typography.Text key={value} copyable={{ text: value }} code>{value}</Typography.Text>)}
    </Space>
  );
}

export function ExecutionDetailContent({ execution, onRefresh, onBack, onCancel, onRerun }: ContentProps) {
  const navigate = useNavigate();
  const [activeTab, setActiveTab] = useState('overview');
  const [logContent, setLogContent] = useState('');
  const [logLoading, setLogLoading] = useState(false);
  const [wrap, setWrap] = useState(true);
  const [autoScroll, setAutoScroll] = useState(true);
  const [logKeyword, setLogKeyword] = useState('');
  const [selectedStepNo, setSelectedStepNo] = useState<number | 'aggregate'>('aggregate');
  const offsetRef = useRef(0);
  const terminalRef = useRef<HTMLPreElement>(null);

  const loadLogs = useCallback(async (reset = false) => {
    if (reset) {
      offsetRef.current = 0;
      setLogContent('');
      setLogLoading(true);
    }
    try {
      const chunk = selectedStepNo === 'aggregate'
        ? await getExecutionLogs(execution.id, offsetRef.current)
        : await getExecutionStepLogs(execution.id, selectedStepNo, offsetRef.current);
      offsetRef.current = chunk.nextOffset;
      setLogContent((current) => reset ? chunk.content : current + chunk.content);
    } catch (error) {
      if (reset) message.error(`加载日志失败：${(error as Error).message}`);
    } finally {
      if (reset) setLogLoading(false);
    }
  }, [execution, selectedStepNo]);

  useEffect(() => {
    setActiveTab('overview');
    setSelectedStepNo('aggregate');
    setLogKeyword('');
    if (execution) void loadLogs(true);
  }, [execution?.id]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (execution) void loadLogs(true);
  }, [selectedStepNo]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!execution || !isActiveExecution(execution.status)) return undefined;
    const timer = window.setInterval(() => {
      void loadLogs(false);
      void onRefresh();
    }, 2000);
    return () => window.clearInterval(timer);
  }, [execution, loadLogs, onRefresh]);

  useEffect(() => {
    if (activeTab === 'logs' && autoScroll && terminalRef.current) {
      terminalRef.current.scrollTop = terminalRef.current.scrollHeight;
    }
  }, [activeTab, autoScroll, logContent]);

  const highlightedLog = logKeyword.trim()
    ? logContent.split(new RegExp(`(${logKeyword.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')})`, 'gi')).map((part, index) =>
      part.toLowerCase() === logKeyword.trim().toLowerCase() ? <mark key={`${part}-${index}`}>{part}</mark> : part)
    : logContent;

  const overview = (
    <div className="execution-detail-overview">
      {execution.errorMessage ? <Alert type="error" showIcon message={execution.errorMessage} /> : null}
      <Descriptions size="small" bordered column={{ xs: 1, sm: 2, lg: 3 }}>
        <Descriptions.Item label="实例 ID">{execution.id}</Descriptions.Item>
        <Descriptions.Item label="任务">
          <Button type="link" size="small" onClick={() => navigate(`/tasks/${execution.taskId}/edit`)}>
            {execution.taskId} {execution.taskName}
          </Button>
        </Descriptions.Item>
        <Descriptions.Item label="状态"><TaskStatusTag status={execution.status} /></Descriptions.Item>
        <Descriptions.Item label="执行人">{execution.requestedBy}</Descriptions.Item>
        {execution.sourceExecutionId ? <Descriptions.Item label="来源实例">{execution.sourceExecutionId} · {execution.replayStrategy}</Descriptions.Item> : null}
        <Descriptions.Item label="提交时间">{formatTime(execution.submittedAt)}</Descriptions.Item>
        <Descriptions.Item label="耗时">{formatDuration(execution.durationMs)}</Descriptions.Item>
        <Descriptions.Item label="开始时间">{formatTime(execution.startedAt)}</Descriptions.Item>
        <Descriptions.Item label="结束时间">{formatTime(execution.finishedAt)}</Descriptions.Item>
        <Descriptions.Item label="Query ID">
          {execution.queryId ? <Typography.Text copyable code>{execution.queryId}</Typography.Text> : '-'}
        </Descriptions.Item>
        <Descriptions.Item label="Application IDs" span={3}><RuntimeIds values={execution.applicationIds} /></Descriptions.Item>
        <Descriptions.Item label="Job IDs" span={3}><RuntimeIds values={execution.jobIds} /></Descriptions.Item>
      </Descriptions>
    </div>
  );

  const logs = (
    <div className="execution-detail-logs">
      <div className="execution-detail-log-toolbar">
        <Space>
          <SelectLogSource value={selectedStepNo} steps={execution.steps || []} onChange={setSelectedStepNo} />
          <Tooltip title="刷新日志"><Button icon={<ReloadOutlined />} onClick={() => void loadLogs(true)} /></Tooltip>
          <Input allowClear value={logKeyword} onChange={(event) => setLogKeyword(event.target.value)} placeholder="搜索当前日志" style={{ width: 200 }} />
          <Button icon={<CopyOutlined />} disabled={!logContent} onClick={() => void navigator.clipboard.writeText(logContent)}>复制</Button>
          <Button icon={<DownloadOutlined />} href={selectedStepNo === 'aggregate' ? `/api/task-executions/${execution.id}/logs/download` : `/api/task-executions/${execution.id}/steps/${selectedStepNo}/logs/download`}>下载</Button>
          <span>自动换行</span><Switch size="small" checked={wrap} onChange={setWrap} />
          <span>自动滚动</span><Switch size="small" checked={autoScroll} onChange={setAutoScroll} />
        </Space>
        <Button type="link" onClick={() => navigate(`/tasks/${execution.taskId}/executions/${execution.id}`)}>完整日志页</Button>
      </div>
      {logLoading ? <div className="execution-detail-log-empty">加载中...</div> : logContent ? (
        <pre ref={terminalRef} className={wrap ? 'execution-detail-terminal wrap' : 'execution-detail-terminal'}>{highlightedLog}</pre>
      ) : <Empty className="execution-detail-log-empty" image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前实例尚未产生运行日志" />}
    </div>
  );

  const steps = (
    <Table<SqlTaskStepVO>
      rowKey="stepNo"
      size="small"
      pagination={false}
      dataSource={execution.steps || []}
      rowClassName={(row) => row.stepNo === selectedStepNo ? 'selected-step-row' : 'clickable-task-row'}
      onRow={(row) => ({ onClick: () => { setSelectedStepNo(row.stepNo); setActiveTab('logs'); } })}
      locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="实例尚未生成 Step 记录" /> }}
      columns={[
        { title: 'Step', dataIndex: 'stepNo', width: 90, fixed: 'left', render: (value, row) => <strong>{row.stepName || `Step ${value}`}</strong> },
        { title: '状态', dataIndex: 'status', width: 110, fixed: 'left', render: (value) => <TaskStatusTag status={value} /> },
        { title: '开始时间', dataIndex: 'startedAt', width: 170, render: formatTime },
        { title: '耗时', dataIndex: 'durationMs', width: 110, render: formatDuration },
        { title: 'Query ID', dataIndex: 'queryId', width: 230, ellipsis: true, render: (value) => value ? <Typography.Text copyable code>{value}</Typography.Text> : '-' },
        { title: 'Application / Job', key: 'runtime', ellipsis: true, render: (_, row) => [...(row.applicationIds || []), ...(row.jobIds || [])].join(', ') || '-' },
        { title: '结果', dataIndex: 'errorMessage', width: 260, ellipsis: true, render: (value) => value || '-' },
      ]}
      scroll={{ x: 1250, y: '38vh' }}
    />
  );

  return (
    <div className="execution-detail-content">
      {onBack || onCancel || onRerun ? (
        <div className="execution-detail-toolbar">
          <div className="execution-detail-title">
            {onBack ? <Button type="text" icon={<ArrowLeftOutlined />} onClick={onBack}>返回实例列表</Button> : null}
            <strong>实例 {execution.id}</strong>
            <TaskStatusTag status={execution.status} />
            <span>{execution.sourceType === 'REPLAY' ? `来源实例 ${execution.sourceExecutionId}` : execution.sourceType === 'VERSION' ? `版本 v${execution.taskVersionNo}` : '当前生效代码'}</span>
          </div>
          <Space size={6}>
            {isActiveExecution(execution.status) && onCancel ? <Button danger icon={<StopOutlined />} onClick={onCancel}>取消</Button> : null}
            {onRerun ? <Button icon={<PlayCircleOutlined />} onClick={onRerun}>重新执行</Button> : null}
            <Button icon={<RobotOutlined />} onClick={() => navigate(`/chat?taskId=${execution.taskId}&executionId=${execution.id}`)}>Agent 诊断</Button>
          </Space>
        </div>
      ) : null}
      <Tabs
        className="execution-detail-tabs ui-flat-tabs"
        activeKey={activeTab}
        onChange={setActiveTab}
        items={[
          { key: 'overview', label: '运行概览', children: overview },
          { key: 'steps', label: `Step (${execution?.steps?.length || 0})`, children: steps },
          {
            key: 'diagnostics',
            label: '运行诊断',
            children: <ExecutionDiagnosticsPanel executionId={execution.id} />,
          },
          { key: 'logs', label: <span><FileTextOutlined /> 运行日志</span>, children: logs },
        ]}
      />
    </div>
  );
}

export default function ExecutionDetailDrawer({ execution, onClose, onRefresh }: Props) {
  return (
    <Drawer
      className="execution-detail-drawer"
      placement="bottom"
      height="68vh"
      open={Boolean(execution)}
      onClose={onClose}
      destroyOnHidden
      title={execution ? <div className="execution-detail-title"><strong>实例 {execution.id}</strong><span>任务 {execution.taskId} · {execution.taskName}</span><TaskStatusTag status={execution.status} /></div> : null}
    >
      {execution ? <ExecutionDetailContent execution={execution} onRefresh={onRefresh} /> : null}
    </Drawer>
  );
}

function SelectLogSource({ value, steps, onChange }: {
  value: number | 'aggregate';
  steps: SqlTaskStepVO[];
  onChange: (value: number | 'aggregate') => void;
}) {
  return (
    <Select
      className="execution-log-source"
      value={value}
      onChange={onChange}
      options={[
        { label: '聚合日志', value: 'aggregate' },
        ...steps.map((step) => ({ label: step.stepName || `Step ${step.stepNo}`, value: step.stepNo })),
      ]}
    />
  );
}
