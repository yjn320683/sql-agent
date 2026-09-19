import { Table, Tag } from 'antd';
import type { RealtimeAlert } from '../types';

interface Props {
  alerts: RealtimeAlert[];
  loading?: boolean;
}

export default function RealtimeAlertTable({ alerts, loading }: Props) {
  return (
    <Table
      rowKey="id"
      size="small"
      loading={loading}
      dataSource={alerts}
      locale={{ emptyText: '暂无告警记录' }}
      columns={[
        { title: '级别', dataIndex: 'severity', width: 100, render: (value: string) => <Tag color={value === 'critical' ? 'red' : 'orange'}>{value}</Tag> },
        { title: '状态', dataIndex: 'status', width: 100, render: (value: string) => value || '-' },
        { title: '标题', dataIndex: 'title', width: 240 },
        { title: '详情', dataIndex: 'detail' },
        { title: '时间', dataIndex: 'createTime', width: 180 },
      ]}
    />
  );
}
