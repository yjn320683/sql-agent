import { useCallback, useEffect, useMemo, useState } from 'react';
import { Alert, Button, Card, Col, Empty, List, Row, Skeleton, Space, Statistic, Tag, Tooltip, Typography, message } from 'antd';
import { AlertOutlined, ClockCircleOutlined, DatabaseOutlined, ExclamationCircleOutlined, ReloadOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { getOverview, type OverviewData, type OverviewItem } from '../../api/overview';

const statusColor = (status?: string) => {
  const value = (status ?? '').toLowerCase();
  if (['failed', 'error', 'open', 'critical'].includes(value)) return 'error';
  if (['running', 'active', 'enabled'].includes(value)) return 'success';
  if (['declared', 'warning', 'acknowledged', 'muted'].includes(value)) return 'warning';
  return 'default';
};

function ItemList({ items, emptyText }: { items: OverviewItem[]; emptyText: string }) {
  const navigate = useNavigate();
  return items.length ? (
    <List
      className="overview-item-list"
      dataSource={items}
      renderItem={(item) => (
        <List.Item actions={[<Button key="open" type="link" onClick={() => navigate(item.route)}>打开</Button>]}>
          <List.Item.Meta title={<Space><Typography.Text strong>{item.title}</Typography.Text>{item.status && <Tag color={statusColor(item.status)}>{item.status}</Tag>}</Space>} description={item.subtitle || item.occurredAt || '-'} />
        </List.Item>
      )}
    />
  ) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={emptyText} />;
}

export default function OverviewPage() {
  const [data, setData] = useState<OverviewData>();
  const [loading, setLoading] = useState(false);
  const load = useCallback(async () => {
    setLoading(true);
    try { setData(await getOverview()); } catch (error) { message.error((error as Error).message); }
    finally { setLoading(false); }
  }, []);
  useEffect(() => { void load(); }, [load]);
  const unavailable = useMemo(() => Object.entries(data?.sections ?? {}).filter(([, value]) => !value.available), [data]);
  const summary = data?.summary ?? {};
  return (
    <div className="overview-page">
      <div className="overview-page-head">
        <div><Typography.Title level={3}>统一工作台</Typography.Title><Typography.Text type="secondary">集中查看离线与实时任务的运行状态和待处理事项</Typography.Text></div>
        <Tooltip title="刷新"><Button aria-label="刷新工作台" icon={<ReloadOutlined />} loading={loading} onClick={() => void load()} /></Tooltip>
      </div>
      {unavailable.length > 0 && <Alert type="warning" showIcon message="部分数据暂不可用" description={unavailable.map(([name, value]) => `${name}：${value.error}`).join('；')} />}
      {!data && loading ? <Skeleton active /> : <>
        <Row gutter={[16, 16]} className="overview-stat-grid">
          <Col xs={24} sm={12} xl={6}><Card><Statistic title="离线运行中" value={summary.offlineActive ?? 0} prefix={<ThunderboltOutlined />} /></Card></Col>
          <Col xs={24} sm={12} xl={6}><Card><Statistic title="离线失败（24h）" value={summary.offlineFailed24h ?? 0} prefix={<ExclamationCircleOutlined />} valueStyle={(summary.offlineFailed24h ?? 0) > 0 ? { color: '#cf1322' } : undefined} /></Card></Col>
          <Col xs={24} sm={12} xl={6}><Card><Statistic title="实时运行中" value={summary.realtimeActive ?? 0} prefix={<ClockCircleOutlined />} /></Card></Col>
          <Col xs={24} sm={12} xl={6}><Card><Statistic title="实时失败（24h）" value={summary.realtimeFailed24h ?? 0} prefix={<ExclamationCircleOutlined />} valueStyle={(summary.realtimeFailed24h ?? 0) > 0 ? { color: '#cf1322' } : undefined} /></Card></Col>
          <Col xs={24} sm={12} xl={6}><Card><Statistic title="待处理告警" value={summary.openAlerts ?? 0} prefix={<AlertOutlined />} valueStyle={(summary.openAlerts ?? 0) > 0 ? { color: '#d46b08' } : undefined} /></Card></Col>
          <Col xs={24} sm={12} xl={6}><Card><Statistic title="失败调度（24h）" value={summary.offlineFailedSchedules24h ?? 0} prefix={<ExclamationCircleOutlined />} /></Card></Col>
          <Col xs={24} sm={12} xl={6}><Card><Statistic title="实时表异常" value={summary.realtimeTableIssues ?? 0} prefix={<DatabaseOutlined />} /></Card></Col>
        </Row>
        <Card className="overview-section-card" title="待处理事项"><ItemList items={data?.attention ?? []} emptyText="当前没有待处理事项" /></Card>
        <Row gutter={[16, 16]}>
          <Col xs={24} xl={12}><Card className="overview-section-card" title="最近任务"><ItemList items={data?.recentTasks ?? []} emptyText="暂无最近任务" /></Card></Col>
          <Col xs={24} xl={12}><Card className="overview-section-card" title="常用任务（近 30 天）"><ItemList items={data?.frequentTasks ?? []} emptyText="暂无任务执行记录" /></Card></Col>
        </Row>
        <Card className="overview-section-card" title="实时表异常"><ItemList items={data?.realtimeTableIssues ?? []} emptyText="所有受管实时表状态正常" /></Card>
      </>}
    </div>
  );
}
