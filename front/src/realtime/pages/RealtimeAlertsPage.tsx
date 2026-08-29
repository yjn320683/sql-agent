import { useCallback, useEffect, useState } from 'react';
import { Button, Card, Table, Tag, Typography, message } from 'antd';
import { CheckOutlined, ReloadOutlined } from '@ant-design/icons';
import { acknowledgeAlert, listAlerts } from '../api';
import type { RealtimeAlert } from '../types';

export default function RealtimeAlertsPage() {
  const [rows, setRows] = useState<RealtimeAlert[]>([]);
  const [loading, setLoading] = useState(false);
  const load = useCallback(async () => {
    setLoading(true);
    try { setRows(await listAlerts()); } catch (error) { message.error((error as Error).message); }
    finally { setLoading(false); }
  }, []);
  useEffect(() => { void load(); }, [load]);
  return (
    <div className="realtime-page">
      <div className="realtime-page-header">
        <div><Typography.Title level={3}>实时同步告警</Typography.Title><Typography.Text type="secondary">双跑、状态检测和任务失败告警</Typography.Text></div>
        <Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button>
      </div>
      <Card variant="borderless" className="realtime-table-card">
        <Table rowKey="id" loading={loading} dataSource={rows} columns={[
          { title: '级别', dataIndex: 'severity', width: 100, render: (value: string) => <Tag color={value === 'critical' ? 'red' : value === 'warning' ? 'orange' : 'blue'}>{value}</Tag> },
          { title: '状态', dataIndex: 'status', width: 120, render: (value: string) => <Tag color={value === 'open' ? 'error' : 'default'}>{value === 'open' ? '待处理' : '已确认'}</Tag> },
          { title: '任务', dataIndex: 'taskName', width: 220 },
          { title: '标题', dataIndex: 'title', width: 240 },
          { title: '详情', dataIndex: 'detail' },
          { title: '产生时间', dataIndex: 'createTime', width: 175 },
          { title: '操作', width: 100, render: (_: unknown, row: RealtimeAlert) => row.status === 'open' ? <Button type="link" icon={<CheckOutlined />} onClick={async () => { await acknowledgeAlert(row.id); message.success('告警已确认'); await load(); }}>确认</Button> : null },
        ]} />
      </Card>
    </div>
  );
}
