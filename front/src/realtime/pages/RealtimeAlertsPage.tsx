import { useCallback, useEffect, useMemo, useState } from 'react';
import { Button, Input, Table, Tag, Tooltip, message } from 'antd';
import { CheckOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { acknowledgeAlert, listAlerts } from '../api';
import type { RealtimeAlert } from '../types';

export default function RealtimeAlertsPage() {
  const [rows, setRows] = useState<RealtimeAlert[]>([]);
  const [loading, setLoading] = useState(false);
  const [keyword, setKeyword] = useState('');
  const load = useCallback(async () => {
    setLoading(true);
    try { setRows(await listAlerts()); } catch (error) { message.error((error as Error).message); }
    finally { setLoading(false); }
  }, []);
  useEffect(() => { void load(); }, [load]);
  const filteredRows = useMemo(() => {
    const value = keyword.trim().toLowerCase();
    if (!value) return rows;
    return rows.filter((row) => [row.id, row.taskName, row.title, row.detail, row.severity, row.status]
      .some((item) => String(item ?? '').toLowerCase().includes(value)));
  }, [keyword, rows]);
  return (
    <div className="realtime-page realtime-sync-tasks-page realtime-alerts-page">
      <section className="realtime-sync-main-panel">
        <div className="realtime-sync-filter-section">
          <div className="realtime-server-toolbar">
            <Input allowClear prefix={<SearchOutlined />} placeholder="告警 ID、任务、标题或详情" value={keyword} onChange={(event) => setKeyword(event.target.value)} />
            <Tooltip title="刷新"><Button aria-label="刷新" icon={<ReloadOutlined />} onClick={() => void load()} /></Tooltip>
          </div>
        </div>
        <div className="realtime-sync-table-section">
          <Table rowKey="id" loading={loading} dataSource={filteredRows} columns={[
            { title: '级别', dataIndex: 'severity', width: 100, render: (value: string) => <Tag color={value === 'critical' ? 'red' : value === 'warning' ? 'orange' : 'blue'}>{value}</Tag> },
            { title: '状态', dataIndex: 'status', width: 120, render: (value: string) => <Tag color={value === 'open' ? 'error' : 'default'}>{value === 'open' ? '待处理' : '已确认'}</Tag> },
            { title: '任务', dataIndex: 'taskName', width: 220 },
            { title: '标题', dataIndex: 'title', width: 240 },
            { title: '详情', dataIndex: 'detail' },
            { title: '产生时间', dataIndex: 'createTime', width: 175 },
            { title: '操作', width: 100, render: (_: unknown, row: RealtimeAlert) => row.status === 'open' ? <Button type="link" icon={<CheckOutlined />} onClick={async () => { await acknowledgeAlert(row.id); message.success('告警已确认'); await load(); }}>确认</Button> : null },
          ]} pagination={{ defaultPageSize: 20, showSizeChanger: true, pageSizeOptions: [20, 50, 100], showTotal: (total) => `共 ${total} 条` }} />
        </div>
      </section>
    </div>
  );
}
