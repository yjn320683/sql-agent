import { CheckCircleFilled, LoadingOutlined, WarningFilled } from '@ant-design/icons';
import { Button, Popover, Progress, Spin, Steps, Tag, Tooltip, Typography } from 'antd';
import { type ReactNode, useEffect, useMemo, useState } from 'react';
import { getInstanceProgress } from '../api';
import type { TaskInstance, TaskInstanceProgress } from '../types';

const stages = [
  { code: 'accepted', label: '已创建实例' }, { code: 'preparing_config', label: '准备配置' },
  { code: 'writing_hdfs', label: '写入 HDFS' }, { code: 'building_command', label: '生成命令' },
  { code: 'submitting_flink', label: '提交 Flink' }, { code: 'waiting_running', label: '等待运行' },
];
const labels = Object.fromEntries(stages.map((item) => [item.code, item.label]));
const percent = (value: number) => Math.max(0, Math.min(100, Math.floor(value)));

export const taskProgressPollingDelay = (progress: TaskInstanceProgress) => {
  if (['submitting', 'stopping', 'restarting'].includes(progress.phase)) return 2000;
  if (progress.executionMode.toUpperCase() === 'DEBUG' && progress.phase === 'qualifying') return 5000;
  return undefined;
};

function SubmissionStepsPopover({ children, progress }: { children: ReactNode; progress: TaskInstanceProgress }) {
  const current = Math.max(1, progress.stageIndex ?? 1);
  const failed = progress.instanceStatus === 'failed';
  const completed = progress.phase === 'running';
  const items = stages.map(({ label }, index) => {
    const position = index + 1;
    const status: 'finish' | 'error' | 'process' | 'wait' = completed || position < current ? 'finish'
      : failed && position === current ? 'error' : position === current ? 'process' : 'wait';
    return { title: label, description: status === 'finish' ? '已完成' : status === 'error' ? '失败' : status === 'process' ? '进行中' : '未开始', status };
  });
  return <Popover placement="bottom" trigger="hover" content={<div className="task-instance-progress-popover">
    <Typography.Text strong>启动明细</Typography.Text>
    <Steps className="task-instance-progress-steps" direction="vertical" size="small" items={items} />
    <Typography.Text type={failed ? 'danger' : 'secondary'}>{failed ? '详细异常信息请在日志中查看' : completed ? '当前：启动完成，任务运行中' : `当前：${progress.message}`}</Typography.Text>
  </div>}>{children}</Popover>;
}

export default function TaskInstanceProgressCell({ taskId, instance }: { taskId: number; instance: TaskInstance }) {
  const [progress, setProgress] = useState<TaskInstanceProgress>();
  const [error, setError] = useState('');
  const [reload, setReload] = useState(0);

  useEffect(() => {
    let disposed = false; let timer: number | undefined;
    setProgress(undefined); setError('');
    const refresh = async () => {
      try {
        const value = await getInstanceProgress(taskId, instance.id);
        if (disposed) return;
        setProgress(value); setError('');
        const delay = taskProgressPollingDelay(value);
        if (delay !== undefined) timer = window.setTimeout(refresh, delay);
      } catch (reason) { if (!disposed) setError(reason instanceof Error ? reason.message : '实例进度加载失败'); }
    };
    void refresh();
    return () => { disposed = true; if (timer !== undefined) window.clearTimeout(timer); };
  }, [instance.id, instance.status, reload, taskId]);

  const submissionPercent = useMemo(() => !progress?.stageIndex || !progress.stageCount ? 0
    : percent(progress.stageIndex * 100 / progress.stageCount), [progress?.stageCount, progress?.stageIndex]);
  const qualification = progress?.qualification;
  const qualificationPercent = useMemo(() => !qualification ? 0
    : qualification.qualified || qualification.runtimeSatisfied ? 100
      : percent(qualification.runningSeconds * 100 / Math.max(1, qualification.requiredRunningSeconds)), [qualification]);

  if (error) return <Tooltip title={error}><Button className="task-instance-progress-retry" danger size="small" type="link" onClick={() => setReload((value) => value + 1)}>进度加载失败 · 重试</Button></Tooltip>;
  if (!progress) return <div className="task-instance-progress-cell task-instance-progress-loading"><Spin size="small" /><Typography.Text type="secondary">加载中</Typography.Text></div>;
  const stage = labels[progress.stage] ?? progress.message;
  const counter = progress.stageIndex && progress.stageCount ? `${progress.stageIndex}/${progress.stageCount}` : '';

  if (progress.phase === 'submitting') return <SubmissionStepsPopover progress={progress}><div className="task-instance-progress-cell"><div className="task-instance-progress-title"><Typography.Text>{stage}</Typography.Text><Typography.Text type="secondary">{counter}</Typography.Text></div><Progress percent={submissionPercent} showInfo={false} size="small" status="active" /></div></SubmissionStepsPopover>;
  if (progress.executionMode.toUpperCase() === 'DEBUG' && qualification) {
    if (!qualification.currentVersion) return <Tooltip title={qualification.configurationMessage || '当前配置需重新调试'}><div className="task-instance-progress-cell"><Typography.Text type="warning"><WarningFilled /> 历史调试结果已失效</Typography.Text><Typography.Text type="secondary">当前配置需重新调试</Typography.Text></div></Tooltip>;
    if (qualification.qualified) return <Tooltip title={progress.instanceStatus === 'killed_success' ? '调试成功，实例已停止' : '调试资格条件均已满足'}><div className="task-instance-progress-cell"><Typography.Text type="success"><CheckCircleFilled /> 调试成功</Typography.Text><Typography.Text type="secondary">已具备正式启动资格</Typography.Text></div></Tooltip>;
    if (progress.instanceStatus === 'running' && qualification.mode === 'CHECKPOINT_ONLY') return <Tooltip title={qualification.unavailableReason || '调试完成一次成功 Checkpoint 即可通过'}><div className="task-instance-progress-cell"><Typography.Text>{qualification.checkpointSatisfied ? 'Checkpoint 已完成' : '等待首次 Checkpoint'}</Typography.Text><Typography.Text type="secondary">{qualification.checkpointSatisfied ? '等待状态同步确认' : '成功后即可完成调试'}</Typography.Text></div></Tooltip>;
    if (progress.instanceStatus === 'running') return <Tooltip title={qualification.unavailableReason ? `资格数据暂不可用：${qualification.unavailableReason}` : `调试进度 ${qualificationPercent}%`}><div className="task-instance-progress-cell"><div className="task-instance-progress-title"><Typography.Text>调试进度</Typography.Text><Typography.Text type="secondary">{qualificationPercent}%</Typography.Text></div><Progress percent={qualificationPercent} showInfo={false} size="small" status="active" /><div className="task-instance-progress-checkpoint"><Typography.Text type="secondary">Checkpoint：</Typography.Text><Tag color={qualification.checkpointSatisfied ? 'success' : 'processing'}>{qualification.checkpointSatisfied ? '已满足' : '等待中'}</Tag></div></div></Tooltip>;
  }
  if (progress.phase === 'running') return <SubmissionStepsPopover progress={progress}><div className="task-instance-progress-cell"><Typography.Text type="success"><CheckCircleFilled /> 启动完成</Typography.Text><Typography.Text type="secondary">任务运行中</Typography.Text></div></SubmissionStepsPopover>;
  if (progress.phase === 'stopping' || progress.phase === 'restarting') return <div className="task-instance-progress-cell task-instance-progress-state"><LoadingOutlined spin /><Typography.Text>{progress.message}</Typography.Text></div>;
  if (progress.instanceStatus === 'failed') return <SubmissionStepsPopover progress={progress}><div className="task-instance-progress-cell"><Typography.Text type="danger"><WarningFilled /> {stage}失败</Typography.Text>{counter && <Typography.Text type="secondary">阶段 {counter}</Typography.Text>}</div></SubmissionStepsPopover>;
  return <Tooltip title={progress.message}><div className="task-instance-progress-cell"><Typography.Text>{progress.message}</Typography.Text>{counter && <Typography.Text type="secondary">最后阶段 {counter}</Typography.Text>}</div></Tooltip>;
}
