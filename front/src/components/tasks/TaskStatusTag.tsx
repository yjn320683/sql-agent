import { Tag } from 'antd';
import type { TaskExecutionStatus, TaskExecutionStepStatus } from '../../types';

const config: Record<TaskExecutionStepStatus, { color: string; label: string }> = {
  PENDING: { color: 'default', label: '待提交' },
  QUEUED: { color: 'blue', label: '排队中' },
  RUNNING: { color: 'processing', label: '运行中' },
  SUCCEEDED: { color: 'success', label: '成功' },
  FAILED: { color: 'error', label: '失败' },
  CANCELLING: { color: 'warning', label: '取消中' },
  CANCELLED: { color: 'default', label: '已取消' },
  SKIPPED: { color: 'default', label: '已跳过' },
};

export const isActiveExecution = (status?: TaskExecutionStatus): boolean =>
  Boolean(status && ['PENDING', 'QUEUED', 'RUNNING', 'CANCELLING'].includes(status));

export default function TaskStatusTag({ status }: { status?: TaskExecutionStepStatus }) {
  if (!status) return <span className="muted-text">未执行</span>;
  const item = config[status];
  return <Tag color={item.color}>{item.label}</Tag>;
}
