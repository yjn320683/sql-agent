import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { Alert, Button, Card, Col, Empty, List, Row, Skeleton, Space, Statistic, Tag, Tooltip, Typography, message } from 'antd';
import {
  AlertOutlined,
  ApartmentOutlined,
  ArrowRightOutlined,
  ClockCircleOutlined,
  CodeOutlined,
  DatabaseOutlined,
  ExportOutlined,
  PlusOutlined,
  ReloadOutlined,
  RocketOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { getOverview, type OverviewData, type OverviewItem } from '../../api/overview';

const statusColor = (status?: string) => {
  const value = (status ?? '').toLowerCase();
  if (['failed', 'error', 'open', 'critical'].includes(value)) return 'error';
  if (['running', 'active', 'enabled'].includes(value)) return 'success';
  if (['declared', 'warning', 'acknowledged', 'muted'].includes(value)) return 'warning';
  return 'default';
};

const itemType: Record<string, { label: string; color: string }> = {
  OFFLINE_EXECUTION: { label: '离线实例', color: 'blue' },
  REALTIME_INSTANCE: { label: '实时实例', color: 'purple' },
  REALTIME_ALERT: { label: '实时告警', color: 'orange' },
  OFFLINE_SCHEDULE: { label: '离线调度', color: 'gold' },
  OFFLINE_TASK: { label: '离线任务', color: 'blue' },
  REALTIME_TASK: { label: '实时任务', color: 'purple' },
};

const formatTime = (value?: string) => {
  if (!value) return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(date);
};

function ItemList({ items, emptyText, compact = false }: { items: OverviewItem[]; emptyText: string; compact?: boolean }) {
  const navigate = useNavigate();
  return items.length ? (
    <List
      className={`overview-item-list${compact ? ' compact' : ''}`}
      dataSource={items}
      renderItem={(item) => {
        const type = item.objectType ? itemType[item.objectType] : undefined;
        return (
          <List.Item
            actions={[<Button key="open" type="text" className="overview-open-action" aria-label={`打开${item.title}`} icon={<ArrowRightOutlined />} onClick={() => navigate(item.route)} />]}
          >
            <List.Item.Meta
              title={(
                <div className="overview-item-title">
                  {type && <Tag color={type.color}>{type.label}</Tag>}
                  <Typography.Text strong ellipsis={{ tooltip: item.title }}>{item.title}</Typography.Text>
                  {item.status && <Tag color={statusColor(item.status)}>{item.status}</Tag>}
                </div>
              )}
              description={(
                <div className="overview-item-description">
                  <Typography.Text type="secondary" ellipsis={{ tooltip: item.subtitle }}>{item.subtitle || '-'}</Typography.Text>
                  {item.occurredAt && <time>{formatTime(item.occurredAt)}</time>}
                </div>
              )}
            />
          </List.Item>
        );
      }}
    />
  ) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={emptyText} />;
}

function MetricCard({
  title, icon, primaryLabel, primaryValue, secondaryLabel, secondaryValue, note, danger, route,
}: {
  title: string;
  icon: ReactNode;
  primaryLabel: string;
  primaryValue: number;
  secondaryLabel?: string;
  secondaryValue?: number;
  note?: string;
  danger?: boolean;
  route: string;
}) {
  const navigate = useNavigate();
  return (
    <Card className="overview-metric-card" hoverable onClick={() => navigate(route)}>
      <div className="overview-metric-card-head"><span className="overview-metric-icon">{icon}</span><Typography.Text strong>{title}</Typography.Text><ArrowRightOutlined /></div>
      <div className="overview-metric-values">
        <Statistic title={primaryLabel} value={primaryValue} />
        {secondaryLabel ? <Statistic title={secondaryLabel} value={secondaryValue ?? 0} valueStyle={danger && (secondaryValue ?? 0) > 0 ? { color: '#cf1322' } : undefined} /> : <Typography.Text className="overview-metric-note" type="secondary">{note}</Typography.Text>}
      </div>
    </Card>
  );
}

const quickActions = [
  { label: '新建离线任务', description: '开发 Hive SQL 任务', route: '/tasks/new', icon: <CodeOutlined /> },
  { label: '新建同步任务', description: 'MySQL CDC 入湖', route: '/realtime/sync-tasks/new', icon: <RocketOutlined /> },
  { label: '新建计算任务', description: '开发实时 Flink SQL', route: '/realtime/compute/new', icon: <ApartmentOutlined /> },
  { label: '新建出仓任务', description: 'Paimon 写入 MySQL', route: '/realtime/export/new', icon: <ExportOutlined /> },
];

export default function OverviewPage() {
  const navigate = useNavigate();
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
  const tableIssues = data?.realtimeTableIssues ?? [];
  const generatedAt = data?.generatedAt ? formatTime(data.generatedAt) : '';

  return (
    <div className="overview-page">
      <div className="overview-page-head">
        <Typography.Text type="secondary">离线与实时任务的运行概览、异常处置和开发入口</Typography.Text>
        <Space size={10}>
          {generatedAt && <Typography.Text type="secondary">更新于 {generatedAt}</Typography.Text>}
          <Tooltip title="刷新"><Button aria-label="刷新工作台" icon={<ReloadOutlined />} loading={loading} onClick={() => void load()} /></Tooltip>
        </Space>
      </div>
      {unavailable.length > 0 && <Alert type="warning" showIcon message="部分数据暂不可用" description={unavailable.map(([name, value]) => `${name}：${value.error}`).join('；')} />}
      {!data && loading ? <Skeleton active /> : <>
        <section className="overview-section" aria-labelledby="overview-running-title">
          <div className="overview-section-title"><div><Typography.Title id="overview-running-title" level={5}>运行概览</Typography.Title><Typography.Text type="secondary">当前运行状态与最近 24 小时异常</Typography.Text></div></div>
          <Row gutter={[16, 16]} className="overview-stat-grid">
            <Col xs={24} md={12} xl={6}><MetricCard title="离线任务" icon={<ThunderboltOutlined />} primaryLabel="运行中" primaryValue={summary.offlineActive ?? 0} secondaryLabel="失败（24h）" secondaryValue={summary.offlineFailed24h ?? 0} danger route="/executions" /></Col>
            <Col xs={24} md={12} xl={6}><MetricCard title="实时任务" icon={<ClockCircleOutlined />} primaryLabel="运行中" primaryValue={summary.realtimeActive ?? 0} secondaryLabel="失败（24h）" secondaryValue={summary.realtimeFailed24h ?? 0} danger route="/realtime/sync-tasks" /></Col>
            <Col xs={24} md={12} xl={6}><MetricCard title="告警与调度" icon={<AlertOutlined />} primaryLabel="待处理告警" primaryValue={summary.openAlerts ?? 0} secondaryLabel="失败调度（24h）" secondaryValue={summary.offlineFailedSchedules24h ?? 0} danger route="/realtime/alerts" /></Col>
            <Col xs={24} md={12} xl={6}><MetricCard title="实时表" icon={<DatabaseOutlined />} primaryLabel="异常表" primaryValue={summary.realtimeTableIssues ?? 0} note="包含待创建或物理状态异常" danger route="/realtime/paimon-tables" /></Col>
          </Row>
        </section>

        <Row gutter={[16, 16]} className="overview-main-grid">
          <Col xs={24} xl={16}>
            <Card
              className="overview-section-card overview-attention-card"
              title={<div className="overview-card-title"><span>待处理事项</span><Tag color={(data?.attention.length ?? 0) > 0 ? 'error' : 'success'}>{data?.attention.length ?? 0}</Tag></div>}
              extra={<Space><Button type="link" onClick={() => navigate('/executions')}>执行中心</Button><Button type="link" onClick={() => navigate('/realtime/alerts')}>告警中心</Button></Space>}
            >
              <ItemList items={data?.attention ?? []} emptyText="当前没有待处理事项" />
            </Card>
          </Col>
          <Col xs={24} xl={8}>
            <Card className="overview-section-card overview-quick-card" title="快捷开始">
              <div className="overview-quick-grid">
                {quickActions.map((item) => (
                  <button key={item.route} type="button" className="overview-quick-action" onClick={() => navigate(item.route)}>
                    <span className="overview-quick-icon">{item.icon}</span>
                    <span><strong>{item.label}</strong><small>{item.description}</small></span>
                    <PlusOutlined />
                  </button>
                ))}
              </div>
            </Card>
            <Card
              className="overview-section-card overview-health-card"
              title="实时表健康"
              extra={<Button type="link" onClick={() => navigate('/realtime/paimon-tables')}>查看全部</Button>}
            >
              {tableIssues.length > 0 ? <ItemList compact items={tableIssues.slice(0, 3)} emptyText="" /> : (
                <div className="overview-healthy-state"><DatabaseOutlined /><div><strong>受管实时表状态正常</strong><span>当前没有待创建或异常表</span></div></div>
              )}
            </Card>
          </Col>
        </Row>

        <section className="overview-section overview-task-section" aria-labelledby="overview-tasks-title">
          <div className="overview-section-title"><div><Typography.Title id="overview-tasks-title" level={5}>我的任务</Typography.Title><Typography.Text type="secondary">继续最近编辑，或快速定位高频运行任务</Typography.Text></div></div>
          <Row gutter={[16, 16]}>
            <Col xs={24} xl={12}><Card className="overview-section-card" title="最近任务"><ItemList items={data?.recentTasks ?? []} emptyText="暂无最近任务" /></Card></Col>
            <Col xs={24} xl={12}><Card className="overview-section-card" title="常用任务（近 30 天）"><ItemList items={data?.frequentTasks ?? []} emptyText="暂无任务执行记录" /></Card></Col>
          </Row>
        </section>
      </>}
    </div>
  );
}
