import { Tag } from 'antd';
import type { DataCompareStatus } from '../../types';

const metadata: Record<DataCompareStatus, { color: string; text: string }> = {
  PENDING: { color: 'default', text: '等待中' },
  RUNNING: { color: 'processing', text: '运行中' },
  PASSED: { color: 'success', text: '通过' },
  NOT_PASSED: { color: 'warning', text: '不一致' },
  FORCE_PASSED: { color: 'cyan', text: '强制通过' },
  NEEDS_RERUN: { color: 'orange', text: '需重新验数' },
  FAILED: { color: 'error', text: '系统失败' },
  CANCELLING: { color: 'warning', text: '取消中' },
  CANCELLED: { color: 'default', text: '已取消' },
};

export default function DataCompareStatusTag({ status }: { status: DataCompareStatus }) {
  const item = metadata[status] || { color: 'default', text: status };
  return <Tag color={item.color}>{item.text}</Tag>;
}
