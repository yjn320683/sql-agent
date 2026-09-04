import { ArrowLeftOutlined, ReloadOutlined } from '@ant-design/icons';
import { Button, Select, Space, Spin, Tabs, Tag, Typography, message } from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { getInstanceInfo, getInstanceLogs } from '../api';
import type { TaskInstance } from '../types';

const ACTIVE_STATUSES = ['submitting', 'running', 'debug_success_running', 'stopping', 'restarting'];
const STATUS_LABEL: Record<string, string> = {
  submitting: '提交中', running: '运行中', debug_success_running: '运行中(调试成功)', stopping: '停止中', restarting: '重启中',
  canceled: '已取消', killed_success: '已停止(调试成功)', finished: '已完成', failed: '失败', not_running: '未运行',
};
const STATUS_COLOR: Record<string, string> = {
  submitting: 'processing', running: 'success', debug_success_running: 'success', stopping: 'warning', restarting: 'processing',
  canceled: 'default', killed_success: 'success', finished: 'success', failed: 'error', not_running: 'default',
};

type RuntimeComponent = 'jobmanager' | 'taskmanager' | 'yarn';

interface LogPayload {
  runtimeLog: string;
  selectedFile?: string;
  truncated?: boolean;
}

interface Props {
  taskId: number;
  instance: TaskInstance;
  backLabel: string;
  onBack: () => void;
}

const normalizeText = (value: unknown, ...fields: string[]) => {
  if (typeof value === 'string') return value.replace(/\\r\\n/g, '\n').replace(/\\n/g, '\n').replace(/\\t/g, '  ');
  const record = value && typeof value === 'object' ? value as Record<string, unknown> : {};
  const raw = fields.map((field) => record[field]).find((item) => typeof item === 'string');
  return typeof raw === 'string' ? raw.replace(/\\r\\n/g, '\n').replace(/\\n/g, '\n').replace(/\\t/g, '  ') : '';
};

const splitLifecycleLog = (value: string) => {
  const startup: string[] = [];
  const stop: string[] = [];
  let target = startup;
  value.split('\n').forEach((line) => {
    if (/\[STOP]|开始停止|停止请求已提交|停止完成|停止失败|停止类型：|savepoint 地址：/.test(line)) target = stop;
    target.push(line);
  });
  return { startup: startup.join('\n').trim(), stop: stop.join('\n').trim() };
};

const componentOptions = (value: unknown) => {
  const values = Array.isArray(value)
    ? value
    : value && typeof value === 'object' && Array.isArray((value as Record<string, unknown>).taskmanagers)
      ? (value as Record<string, unknown>).taskmanagers as unknown[]
      : [];
  return values.flatMap((item) => {
    const record = item && typeof item === 'object' ? item as Record<string, unknown> : {};
    const rawValue = String(record.value ?? record.id ?? '');
    if (!rawValue) return [];
    if (rawValue.startsWith('taskmanager:')) {
      return [{ value: rawValue.slice('taskmanager:'.length), label: String(record.label ?? rawValue) }];
    }
    if (record.id) return [{ value: rawValue, label: `TaskManager ${rawValue}` }];
    return [];
  });
};

export default function InstanceLogPanel({ taskId, instance, backLabel, onBack }: Props) {
  // managed 只限制控制操作；运行中导入实例仍可通过 Flink REST 做只读日志观测。
  const active = ACTIVE_STATUSES.includes(instance.status);
  const [activeTab, setActiveTab] = useState('startup');
  const [lifecycleText, setLifecycleText] = useState('');
  const [lifecycleLoading, setLifecycleLoading] = useState(false);
  const [runtimeComponent, setRuntimeComponent] = useState<RuntimeComponent>(active ? 'jobmanager' : 'yarn');
  const [taskManagers, setTaskManagers] = useState<{ value: string; label: string }[]>([]);
  const [taskManagerId, setTaskManagerId] = useState<string>();
  const [runtime, setRuntime] = useState<LogPayload>({ runtimeLog: '' });
  const [runtimeLoading, setRuntimeLoading] = useState(false);

  const loadLifecycle = useCallback(async () => {
    setLifecycleLoading(true);
    try {
      const result = await getInstanceInfo(taskId, instance.id, 'startup-log');
      setLifecycleText(normalizeText(result, 'startupLog'));
    } catch (error) { message.error((error as Error).message); }
    finally { setLifecycleLoading(false); }
  }, [instance.id, taskId]);

  const loadComponents = useCallback(async () => {
    if (!active) { setTaskManagers([]); setTaskManagerId(undefined); return; }
    try {
      const options = componentOptions(await getInstanceInfo(taskId, instance.id, 'log-components'));
      setTaskManagers(options);
      setTaskManagerId((current) => current && options.some((item) => item.value === current) ? current : options[0]?.value);
    } catch { setTaskManagers([]); setTaskManagerId(undefined); }
  }, [active, instance.id, taskId]);

  const loadRuntime = useCallback(async () => {
    if (runtimeComponent === 'taskmanager' && !taskManagerId) {
      setRuntime({ runtimeLog: '' });
      return;
    }
    setRuntimeLoading(true);
    try {
      const component = runtimeComponent === 'taskmanager' ? `taskmanager:${taskManagerId}` : runtimeComponent;
      const value = await getInstanceLogs(taskId, instance.id, component);
      const record = value && typeof value === 'object' ? value as Record<string, unknown> : {};
      setRuntime({
        runtimeLog: normalizeText(value, 'runtimeLog', 'content'),
        selectedFile: typeof record.selectedFile === 'string' ? record.selectedFile : undefined,
        truncated: record.truncated === true,
      });
    } catch (error) { message.error((error as Error).message); }
    finally { setRuntimeLoading(false); }
  }, [instance.id, runtimeComponent, taskId, taskManagerId]);

  useEffect(() => {
    setActiveTab('startup'); setLifecycleText(''); setRuntime({ runtimeLog: '' });
    setRuntimeComponent(active ? 'jobmanager' : 'yarn');
    void loadLifecycle(); void loadComponents();
  }, [active, instance.id, loadComponents, loadLifecycle]);

  useEffect(() => { if (activeTab === 'runtime') void loadRuntime(); }, [activeTab, loadRuntime]);

  const lifecycle = useMemo(() => splitLifecycleLog(lifecycleText), [lifecycleText]);
  const refresh = () => activeTab === 'runtime' ? void loadRuntime() : void loadLifecycle();
  const consoleView = (text: string, empty: string, loading: boolean) => (
    <div className="task-runtime-log-pane">
      {loading && !text ? <div className="task-log-loading"><Spin /></div> : <pre className="task-log-console">{text || empty}</pre>}
    </div>
  );

  return <div className="realtime-instance-logs">
    <div className="realtime-instance-log-header">
      <Space size={12}>
        <Button type="link" className="realtime-log-back" icon={<ArrowLeftOutlined />} onClick={onBack}>{backLabel}</Button>
        <Typography.Text strong>实例 #{instance.id}</Typography.Text>
        <Tag color={STATUS_COLOR[instance.status]}>{STATUS_LABEL[instance.status] ?? instance.status}</Tag>
      </Space>
      <Button icon={<ReloadOutlined />} loading={activeTab === 'runtime' ? runtimeLoading : lifecycleLoading} onClick={refresh}>刷新日志</Button>
    </div>
    <Tabs className="ui-flat-tabs task-log-tabs-fill" activeKey={activeTab} onChange={setActiveTab} items={[
      { key: 'startup', label: '启动日志', children: consoleView(lifecycle.startup, '暂无启动日志', lifecycleLoading) },
      { key: 'stop', label: '停止日志', children: consoleView(lifecycle.stop, '暂无停止日志', lifecycleLoading) },
      { key: 'runtime', label: '运行日志', children: <div className="task-runtime-log-pane">
        <div className="task-log-toolbar">
          <Space size={8} className="task-log-source-controls">
            <Typography.Text strong className="task-log-toolbar-title">{active ? '运行日志' : 'YARN 应用日志'}</Typography.Text>
            {active && <Select size="small" className="task-log-component-select" value={runtimeComponent} options={[{ label: 'JobManager', value: 'jobmanager' }, { label: 'TaskManager', value: 'taskmanager' }]} onChange={(value) => setRuntimeComponent(value)} />}
            {active && runtimeComponent === 'taskmanager' && <Select size="small" className="task-log-taskmanager-select" placeholder="选择 TaskManager" value={taskManagerId} options={taskManagers} onChange={setTaskManagerId} />}
            {runtime.selectedFile && <Typography.Text type="secondary" className="task-log-selected-file">{runtime.selectedFile}</Typography.Text>}
            {runtime.truncated && <Tag color="warning">已截断</Tag>}
          </Space>
        </div>
        {runtimeLoading && !runtime.runtimeLog ? <div className="task-log-loading"><Spin /></div> : <pre className="task-log-console">{runtime.runtimeLog || (active ? '点击刷新日志拉取最新 Logs' : '点击刷新日志拉取 YARN 应用日志')}</pre>}
      </div> },
    ]} />
  </div>;
}
