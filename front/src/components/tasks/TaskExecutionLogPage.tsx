import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Button, Empty, Input, Space, Switch, Typography, message } from 'antd';
import { ArrowLeftOutlined, CopyOutlined, DownloadOutlined, PauseOutlined, ReloadOutlined } from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import { getExecutionLogs, getTaskExecution } from '../../api/tasks';
import type { TaskExecutionVO } from '../../types';
import TaskStatusTag, { isActiveExecution } from './TaskStatusTag';

export default function TaskExecutionLogPage() {
  const navigate = useNavigate();
  const params = useParams<{ taskId: string; executionId: string }>();
  const taskId = Number(params.taskId); const executionId = Number(params.executionId);
  const [execution, setExecution] = useState<TaskExecutionVO>();
  const [content, setContent] = useState('');
  const [offset, setOffset] = useState(0);
  const [paused, setPaused] = useState(false);
  const [wrap, setWrap] = useState(false);
  const [autoScroll, setAutoScroll] = useState(true);
  const [keyword, setKeyword] = useState('');
  const terminalRef = useRef<HTMLPreElement>(null);

  const refresh = useCallback(async (reset = false) => {
    try {
      const start = reset ? 0 : offset;
      const [detail, chunk] = await Promise.all([getTaskExecution(executionId), getExecutionLogs(executionId, start)]);
      setExecution(detail);
      setContent((current) => reset ? chunk.content : current + chunk.content);
      setOffset(chunk.nextOffset);
    } catch (error) { message.error(`加载运行日志失败：${(error as Error).message}`); }
  }, [executionId, offset]);

  useEffect(() => { void refresh(true); }, [executionId]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    if (paused || !isActiveExecution(execution?.status)) return undefined;
    const timer = window.setInterval(() => void refresh(), 2000);
    return () => window.clearInterval(timer);
  }, [execution?.status, paused, refresh]);
  useEffect(() => {
    if (autoScroll && terminalRef.current) terminalRef.current.scrollTop = terminalRef.current.scrollHeight;
  }, [autoScroll, content]);

  const rendered = useMemo(() => {
    if (!keyword.trim()) return content;
    const escaped = keyword.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    return content.split(new RegExp(`(${escaped})`, 'gi')).map((part, index) =>
      part.toLowerCase() === keyword.toLowerCase() ? <mark key={index}>{part}</mark> : part,
    );
  }, [content, keyword]);

  return <div className="execution-log-page">
    <header className="log-header">
      <Space><Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate(`/tasks/${taskId}/executions`)} /><div><Typography.Title level={3}>实例 {executionId} 运行日志</Typography.Title><Space size={8}><span>任务 {taskId}</span><TaskStatusTag status={execution?.status} /></Space></div></Space>
      <Space><Button icon={<ReloadOutlined />} onClick={() => void refresh(true)}>刷新</Button><Button icon={<CopyOutlined />} onClick={() => void navigator.clipboard.writeText(content)}>复制</Button><Button icon={<DownloadOutlined />} href={`/api/task-executions/${executionId}/logs/download`}>下载</Button></Space>
    </header>
    {execution?.errorMessage ? <div className="execution-error-banner">{execution.errorMessage}</div> : null}
    <div className="log-toolbar"><Space><Input.Search value={keyword} allowClear placeholder="搜索日志" onChange={(event) => setKeyword(event.target.value)} /><span>自动滚动</span><Switch size="small" checked={autoScroll} onChange={setAutoScroll} /><span>自动换行</span><Switch size="small" checked={wrap} onChange={setWrap} /></Space><Button type={paused ? 'primary' : 'default'} icon={<PauseOutlined />} onClick={() => setPaused((value) => !value)}>{paused ? '继续刷新' : '暂停刷新'}</Button></div>
    {content ? <pre ref={terminalRef} className={wrap ? 'execution-terminal wrap' : 'execution-terminal'}><code>{rendered}</code></pre>
      : <div className="execution-log-empty"><Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前实例尚未产生运行日志" /></div>}
  </div>;
}
