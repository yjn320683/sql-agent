import { useCallback, useEffect, useMemo, useState } from 'react';
import { Alert, Button, DatePicker, Drawer, Empty, Form, Input, InputNumber, Pagination, Select, Space, Switch, Table, Tabs, Tag, Typography, message } from 'antd';
import { CalendarOutlined, ReloadOutlined, SaveOutlined } from '@ant-design/icons';
import type { Dayjs } from 'dayjs';
import { useNavigate } from 'react-router-dom';
import {
  createTaskBackfill, getTaskDependencies, getTaskSchedule, listTaskBackfills, listTaskScheduleRuns,
  listTasks, saveTaskDependencies, saveTaskSchedule,
} from '../../api/tasks';
import type { AiProposal, SqlTaskBackfillBatchPageVO, SqlTaskDependencyVO, SqlTaskScheduleRunPageVO, SqlTaskScheduleVO, SqlTaskVO } from '../../types';

interface Props { task: SqlTaskVO; open: boolean; onClose: () => void; }

interface ScheduleFields {
  scheduleType: 'MANUAL' | 'CRON'; cronExpression?: string; timezone: string; enabled: boolean;
  concurrencyPolicy: 'FORBID' | 'ALLOW'; maxRetries: number; retryIntervalSeconds: number; parametersText: string;
}

const EMPTY_RUNS: SqlTaskScheduleRunPageVO = { items: [], page: 1, pageSize: 20, total: 0 };
const EMPTY_BACKFILLS: SqlTaskBackfillBatchPageVO = { items: [], page: 1, pageSize: 20, total: 0 };
const RUN_STATUS_LABELS: Record<string, string> = {
  WAITING: '等待提交', SUBMITTED: '已提交', RETRYING: '重试认领中', RETRIED: '已触发重试',
  SKIPPED: '已跳过', FAILED: '失败', CANCELLED: '已取消', SUCCEEDED: '成功',
};
const BACKFILL_STATUS_LABELS: Record<string, string> = {
  PENDING: '等待中', RUNNING: '运行中', SUCCEEDED: '成功', PARTIAL_FAILED: '部分失败',
  FAILED: '失败', CANCELLED: '已取消',
};
const TRIGGER_LABELS: Record<string, string> = { CRON: '定时', MANUAL: '手动', BACKFILL: '补数', RETRY: '重试' };

export default function TaskScheduleDrawer({ task, open, onClose }: Props) {
  const navigate = useNavigate();
  const [form] = Form.useForm<ScheduleFields>();
  const [schedule, setSchedule] = useState<SqlTaskScheduleVO>();
  const [dependencies, setDependencies] = useState<SqlTaskDependencyVO[]>([]);
  const [dependencyIds, setDependencyIds] = useState<number[]>([]);
  const [dependencyTypes, setDependencyTypes] = useState<Record<number, 'SUCCESS' | 'COMPLETED'>>({});
  const [tasks, setTasks] = useState<SqlTaskVO[]>([]);
  const [runs, setRuns] = useState(EMPTY_RUNS);
  const [backfills, setBackfills] = useState(EMPTY_BACKFILLS);
  const [runPage, setRunPage] = useState(1);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [backfillRange, setBackfillRange] = useState<[Dayjs, Dayjs]>();
  const [backfillParameters, setBackfillParameters] = useState('{}');
  const [activeTab, setActiveTab] = useState('schedule');
  const scheduleType = Form.useWatch('scheduleType', form);
  const scheduleDraft = Form.useWatch([], form);

  const load = useCallback(async () => {
    if (!open) return;
    setLoading(true);
    try {
      const [loadedSchedule, loadedDependencies, loadedTasks, loadedRuns, loadedBackfills] = await Promise.all([
        getTaskSchedule(task.id), getTaskDependencies(task.id), listTasks('', 1, 100, 'active'),
        listTaskScheduleRuns(task.id, runPage, 20), listTaskBackfills(task.id, 1, 20),
      ]);
      setSchedule(loadedSchedule); setDependencies(loadedDependencies); setDependencyIds(loadedDependencies.map((item) => item.upstreamTaskId));
      setDependencyTypes(Object.fromEntries(loadedDependencies.map((item) => [item.upstreamTaskId, item.dependencyType])));
      setTasks(loadedTasks.items.filter((item) => item.id !== task.id)); setRuns(loadedRuns);
      setBackfills(loadedBackfills);
      form.setFieldsValue({
        scheduleType: loadedSchedule.scheduleType, cronExpression: loadedSchedule.cronExpression,
        timezone: loadedSchedule.timezone, enabled: loadedSchedule.enabled,
        concurrencyPolicy: loadedSchedule.concurrencyPolicy, maxRetries: loadedSchedule.maxRetries,
        retryIntervalSeconds: loadedSchedule.retryIntervalSeconds,
        parametersText: JSON.stringify(loadedSchedule.parameters || {}, null, 2),
      });
    } catch (error) { message.error(`加载调度配置失败：${(error as Error).message}`); }
    finally { setLoading(false); }
  }, [form, open, runPage, task.id]);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    if (!open) return;
    const publishAiContext = () => window.dispatchEvent(new CustomEvent('sql-agent:ai-context-update', {
      detail: {
        contextType: 'OFFLINE_SCHEDULE',
        entityId: String(task.id),
        title: `调度与依赖 · ${task.name}`,
        revision: schedule?.revision ?? 0,
        draft: {
          activeTab,
          schedule: scheduleDraft,
          dependencies: dependencyIds.map((upstreamTaskId) => ({
            upstreamTaskId,
            dependencyType: dependencyTypes[upstreamTaskId] || 'SUCCESS',
          })),
        },
      },
    }));
    publishAiContext();
    window.addEventListener('sql-agent:ai-context-request', publishAiContext);
    return () => window.removeEventListener('sql-agent:ai-context-request', publishAiContext);
  }, [activeTab, dependencyIds, dependencyTypes, open, schedule?.revision, scheduleDraft, task.id, task.name]);
  useEffect(() => {
    if (!open) return;
    const applyAiProposal = (rawEvent: Event) => {
      if (rawEvent.defaultPrevented) return;
      const event = rawEvent as CustomEvent<AiProposal>;
      const proposal = event.detail;
      const kind = proposal?.kind?.toUpperCase();
      if (!['offline-schedule', 'offline-dependencies'].includes(proposal?.target || '')
        || !proposal?.patch || (kind !== 'FORM' && kind !== 'CONFIG')) return;
      const patch = proposal.patch as Record<string, unknown>;
      const allowed = ['scheduleType', 'cronExpression', 'timezone', 'enabled', 'concurrencyPolicy', 'maxRetries', 'retryIntervalSeconds'] as const;
      const fields = Object.fromEntries(allowed.filter((key) => patch[key] !== undefined).map((key) => [key, patch[key]]));
      if (patch.parameters && typeof patch.parameters === 'object') fields.parametersText = JSON.stringify(patch.parameters, null, 2);
      form.setFieldsValue(fields as Partial<ScheduleFields>);
      const proposedDependencies = Array.isArray(patch.dependencies) ? patch.dependencies : Array.isArray(patch.dependencyIds) ? patch.dependencyIds : undefined;
      if (proposedDependencies) {
        const normalized = proposedDependencies.map((item) => typeof item === 'number'
          ? { upstreamTaskId: item, dependencyType: 'SUCCESS' as const }
          : item as { upstreamTaskId?: number; dependencyType?: 'SUCCESS' | 'COMPLETED' })
          .filter((item) => Number.isInteger(item.upstreamTaskId) && item.upstreamTaskId !== task.id);
        setDependencyIds(normalized.map((item) => item.upstreamTaskId as number));
        setDependencyTypes(Object.fromEntries(normalized.map((item) => [item.upstreamTaskId, item.dependencyType || 'SUCCESS'])));
      }
      event.preventDefault();
    };
    window.addEventListener('sql-agent:apply-ai-proposal', applyAiProposal);
    return () => window.removeEventListener('sql-agent:apply-ai-proposal', applyAiProposal);
  }, [form, open, task.id]);

  const parseParameters = (text: string) => {
    try {
      const value = JSON.parse(text || '{}');
      if (!value || Array.isArray(value) || typeof value !== 'object') throw new Error();
      return value as Record<string, unknown>;
    } catch { throw new Error('运行参数必须是 JSON 对象'); }
  };

  const saveSchedule = async () => {
    try {
      const values = await form.validateFields(); setSaving(true);
      const saved = await saveTaskSchedule(task.id, {
        scheduleType: values.scheduleType, cronExpression: values.scheduleType === 'CRON' ? values.cronExpression : undefined,
        timezone: values.timezone, enabled: values.scheduleType === 'CRON' && values.enabled,
        concurrencyPolicy: values.concurrencyPolicy, maxRetries: values.maxRetries,
        retryIntervalSeconds: values.retryIntervalSeconds, parameters: parseParameters(values.parametersText),
        revision: schedule?.revision || 0,
      });
      setSchedule(saved); message.success('调度配置已保存'); await load();
    } catch (error) { if (error instanceof Error) message.error(error.message); }
    finally { setSaving(false); }
  };

  const saveDependencies = async () => {
    setSaving(true);
    try {
      const saved = await saveTaskDependencies(task.id, dependencyIds.map((upstreamTaskId) => ({
        upstreamTaskId, dependencyType: dependencyTypes[upstreamTaskId] || 'SUCCESS',
      })));
      setDependencies(saved); message.success('任务依赖已保存');
    } catch (error) { message.error((error as Error).message); }
    finally { setSaving(false); }
  };

  const startBackfill = async () => {
    if (!backfillRange) { message.warning('请选择补数日期范围'); return; }
    try {
      setSaving(true);
      await createTaskBackfill(task.id, {
        startDate: backfillRange[0].format('YYYY-MM-DD'), endDate: backfillRange[1].format('YYYY-MM-DD'),
        parameters: parseParameters(backfillParameters),
      });
      message.success('补数批次已创建'); setRunPage(1); await load();
    } catch (error) { message.error((error as Error).message); }
    finally { setSaving(false); }
  };

  const dependencyOptions = useMemo(() => tasks.map((item) => ({ value: item.id, label: `${item.id} · ${item.name}` })), [tasks]);
  const items = [
    { key: 'schedule', label: '调度配置', children: <div className="task-schedule-pane">
      <Alert type="info" showIcon message="调度始终运行当前生效代码" description="版本只负责开发隔离；发布新版本后，下次调度自动使用最新生效代码。" />
      <Form form={form} layout="vertical" initialValues={{ scheduleType: 'MANUAL', timezone: 'Asia/Shanghai', enabled: false, concurrencyPolicy: 'FORBID', maxRetries: 0, retryIntervalSeconds: 60, parametersText: '{}' }}>
        <div className="task-schedule-grid">
          <Form.Item name="scheduleType" label="调度方式" rules={[{ required: true }]}><Select options={[{ value: 'MANUAL', label: '手动执行' }, { value: 'CRON', label: 'Cron 定时' }]} /></Form.Item>
          <Form.Item name="timezone" label="时区" rules={[{ required: true }]}><Select options={[{ value: 'Asia/Shanghai', label: 'Asia/Shanghai' }, { value: 'UTC', label: 'UTC' }]} /></Form.Item>
          {scheduleType === 'CRON' ? <Form.Item name="cronExpression" label="Cron（秒 分 时 日 月 周）" rules={[{ required: true }]}><Input placeholder="0 0 7 * * *" /></Form.Item> : null}
          <Form.Item name="concurrencyPolicy" label="并发策略"><Select options={[{ value: 'FORBID', label: '已有实例时跳过' }, { value: 'ALLOW', label: '允许并行' }]} /></Form.Item>
          <Form.Item name="maxRetries" label="失败重试次数"><InputNumber min={0} max={10} style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="retryIntervalSeconds" label="重试间隔（秒）"><InputNumber min={10} max={86400} style={{ width: '100%' }} /></Form.Item>
        </div>
        <Form.Item name="parametersText" label="固定运行参数（JSON）"><Input.TextArea className="task-schedule-json" rows={5} /></Form.Item>
        {scheduleType === 'CRON' ? <Form.Item name="enabled" label="自动调度" valuePropName="checked"><Switch checkedChildren="启用" unCheckedChildren="停用" /></Form.Item> : null}
        <div className="task-schedule-meta"><span>下次触发：{schedule?.nextTriggerTime || '-'}</span><span>最近状态：{schedule?.lastRunStatus || '-'}</span></div>
        <Button type="primary" icon={<SaveOutlined />} loading={saving} onClick={() => void saveSchedule()}>保存调度</Button>
      </Form>
    </div> },
    { key: 'dependencies', label: `上游依赖（${dependencies.length}）`, children: <div className="task-schedule-pane">
      <Alert type="info" showIcon message="依赖关系按 DAG 校验" description="保存时会拒绝自依赖、重复依赖和循环依赖；默认要求上游最近实例成功。" />
      <Typography.Text strong>上游任务</Typography.Text>
      <Select mode="multiple" value={dependencyIds} options={dependencyOptions} onChange={(values) => { setDependencyIds(values); setDependencyTypes((current) => Object.fromEntries(values.map((id) => [id, current[id] || 'SUCCESS']))); }} placeholder="选择一个或多个上游任务" style={{ width: '100%', margin: '12px 0' }} optionFilterProp="label" />
      <Table size="small" rowKey="id" pagination={false} dataSource={dependencyIds.map((id) => ({ id, name: tasks.find((item) => item.id === id)?.name || String(id) }))} columns={[
        { title: '上游任务', dataIndex: 'name' },
        { title: '满足条件', width: 220, render: (_, row) => <Select value={dependencyTypes[row.id] || 'SUCCESS'} onChange={(value) => setDependencyTypes((current) => ({ ...current, [row.id]: value }))} options={[{ value: 'SUCCESS', label: '上游成功' }, { value: 'COMPLETED', label: '上游完成（任意终态）' }]} style={{ width: '100%' }} /> },
      ]} />
      <Button type="primary" icon={<SaveOutlined />} loading={saving} onClick={() => void saveDependencies()}>保存依赖</Button>
    </div> },
    { key: 'backfill', label: '日期补数', children: <div className="task-schedule-pane">
      <Alert type="warning" showIcon message="补数会为日期范围内的每个业务日期创建独立实例" description="单次最多 366 天，可在调度记录中逐日查看结果。" />
      <Space direction="vertical" style={{ width: '100%' }} size={12}>
        <DatePicker.RangePicker value={backfillRange} onChange={(range) => setBackfillRange(range?.[0] && range[1] ? [range[0], range[1]] : undefined)} />
        <Input.TextArea rows={5} value={backfillParameters} onChange={(event) => setBackfillParameters(event.target.value)} placeholder={'{\n  "region": "cn"\n}'} />
        <Button type="primary" icon={<CalendarOutlined />} loading={saving} onClick={() => void startBackfill()}>创建补数批次</Button>
      </Space>
      <div className="task-schedule-subtitle"><Typography.Text strong>最近补数批次</Typography.Text><span>共 {backfills.total} 个</span></div>
      <Table size="small" rowKey="id" pagination={false} dataSource={backfills.items} columns={[
        { title: '日期范围', render: (_, row) => `${row.startDate} 至 ${row.endDate}` },
        { title: '状态', dataIndex: 'status', width: 120, render: (value) => <Tag color={value === 'SUCCEEDED' ? 'success' : value === 'FAILED' ? 'error' : value === 'PARTIAL_FAILED' ? 'warning' : value === 'RUNNING' ? 'processing' : 'default'}>{BACKFILL_STATUS_LABELS[value] || value}</Tag> },
        { title: '进度', width: 170, render: (_, row) => `${row.succeededCount} 成功 / ${row.failedCount} 失败 / ${row.totalCount} 总计` },
        { title: '发起人', dataIndex: 'requestedBy', width: 100 },
        { title: '创建时间', dataIndex: 'createTime', width: 170 },
      ]} scroll={{ x: 780 }} locale={{ emptyText: <Empty description="暂无补数批次" /> }} />
    </div> },
    { key: 'runs', label: `调度记录（${runs.total}）`, children: <div className="task-schedule-pane schedule-runs-pane">
      <div className="task-schedule-actions"><Button icon={<ReloadOutlined />} loading={loading} onClick={() => void load()}>刷新</Button></div>
      <Table size="small" rowKey="id" pagination={false} dataSource={runs.items} columns={[
        { title: '触发', dataIndex: 'triggerType', width: 90, render: (value) => TRIGGER_LABELS[value] || value }, { title: '业务日期', dataIndex: 'businessDate', width: 110 },
        { title: '状态', dataIndex: 'status', width: 110, render: (value) => <Tag color={value === 'SUCCEEDED' ? 'success' : value === 'FAILED' ? 'error' : value === 'SUBMITTED' || value === 'RETRYING' ? 'processing' : 'default'}>{RUN_STATUS_LABELS[value] || value}</Tag> },
        { title: '尝试', dataIndex: 'attemptNo', width: 70 }, { title: '实例 ID', dataIndex: 'executionId', width: 100, render: (value) => value ? <Button type="link" size="small" onClick={() => { onClose(); navigate(`/tasks/${task.id}/executions/${value}`); }}>{value}</Button> : '-' },
        { title: '计划时间', dataIndex: 'scheduledTime', width: 180 }, { title: '说明', dataIndex: 'message', ellipsis: true },
      ]} scroll={{ x: 860 }} locale={{ emptyText: <Empty description="暂无调度记录" /> }} />
      <Pagination size="small" current={runPage} pageSize={20} total={runs.total} onChange={setRunPage} />
    </div> },
  ];

  return <Drawer width="min(860px, 96vw)" open={open} onClose={onClose} title={<Space><CalendarOutlined /><span>任务调度 · {task.name}</span></Space>} destroyOnHidden><Tabs className="ui-flat-tabs" activeKey={activeTab} onChange={setActiveTab} items={items} /></Drawer>;
}
