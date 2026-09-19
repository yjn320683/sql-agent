import { useEffect, useState } from 'react';
import { Alert, Button, Card, Col, Row, Spin, Statistic, Table, Tag, Tooltip, Typography, message } from 'antd';
import { ApartmentOutlined, BranchesOutlined, DatabaseOutlined, ReloadOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { getDataMapOverview, type DataMapOverview, type DataMapRun } from '../../api/dataMap';
import PageContent from '../layout/PageContent';
import ListToolbar from '../layout/ListToolbar';
import ListTableSection from '../layout/ListTableSection';

const statusColor = (status: string) => status === 'SUCCEEDED' ? 'success' : status === 'FAILED' ? 'error' : status === 'RUNNING' ? 'processing' : 'warning';

export default function DataMapOverviewPage() {
  const navigate = useNavigate();
  const [data, setData] = useState<DataMapOverview>();
  const [loading, setLoading] = useState(false);
  const load = async () => {
    setLoading(true);
    try { setData(await getDataMapOverview()); }
    catch (error) { message.error((error as Error).message); }
    finally { setLoading(false); }
  };
  useEffect(() => { void load(); }, []);
  return <PageContent className="data-map-page">
    <ListToolbar className="data-map-page-toolbar"><Typography.Text type="secondary">当前生产口径的数据资产、任务血缘、字段派生和解析完整性</Typography.Text><Tooltip title="刷新"><Button aria-label="刷新数据概览" icon={<ReloadOutlined />} onClick={() => void load()} /></Tooltip></ListToolbar>
    {data ? <Alert className="data-map-status-alert data-map-readiness-bar" banner showIcon
      type={!data.graphConfigured || !data.graphAvailable || !data.graphSchemaReady ? 'warning' : (data.projection.stale > 0 || data.projection.failed > 0 ? 'error' : 'success')}
      message={<span>图存储：{!data.graphConfigured ? '未启用' : !data.graphAvailable ? '不可达' : !data.graphSchemaReady ? '约束未就绪' : '可用'} · 生效代次 {data.activeGeneration} · 待投影 {data.projection.pending} · 处理中 {data.projection.processing} · 超时 {data.projection.stale} · 失败 {data.projection.failed}</span>}
      description={!data.graphConfigured || !data.graphAvailable ? 'MySQL 血缘快照不会丢失，图存储恢复后将继续投影。' : undefined} /> : null}
    <Spin spinning={loading && !data}>
      <Row gutter={[16,16]} className="data-map-statistics">
        <Col xs={24} md={12} xl={6}><Card><Statistic title="表资产" value={data?.assetCount || 0} prefix={<DatabaseOutlined />} /></Card></Col>
        <Col xs={24} md={12} xl={6}><Card><Statistic title="字段" value={data?.columnCount || 0} prefix={<BranchesOutlined />} /></Card></Col>
        <Col xs={24} md={12} xl={6}><Card><Statistic title="生产任务版本" value={data?.taskCount || 0} prefix={<ApartmentOutlined />} /></Card></Col>
        <Col xs={24} md={12} xl={6}><Card><Statistic title="待投影/失败" value={data?.pendingProjection || 0} valueStyle={(data?.pendingProjection || 0) > 0 ? { color: '#d46b08' } : undefined} /></Card></Col>
      </Row>
      <section className="data-map-action-grid">
        <Card title="资产入口" extra={<Button type="link" onClick={() => navigate('/data-map/catalog')}>进入目录</Button>}><p>按 Hive、Paimon、MySQL 搜索表，并从资产进入血缘与影响分析。</p></Card>
        <Card title="血缘分析" extra={<Button type="link" onClick={() => navigate('/data-map/lineage')}>打开图谱</Button>}><p>切换表、字段、任务视图，查看上下游链路及关系证据。</p></Card>
        <Card title="解析质量" extra={<Button type="link" onClick={() => navigate('/data-map/parsing')}>查看监控</Button>}><p>完整 {data?.completeTasks || 0}，部分成功 {data?.partialTasks || 0}；部分结果会保留缺失原因。</p></Card>
      </section>
      <ListTableSection className="data-map-runs-card"><Table<DataMapRun> rowKey="id" size="small" pagination={false} dataSource={data?.latestRuns || []} columns={[
        { title: '批次', dataIndex: 'id', width: 90 },
        { title: '类型', dataIndex: 'run_type', width: 130 },
        { title: '状态', dataIndex: 'status', width: 120, render: (value: string) => <Tag color={statusColor(value)}>{value}</Tag> },
        { title: '已投影', dataIndex: 'projected_count', width: 100 },
        { title: '开始时间', dataIndex: 'started_at' },
        { title: '说明', dataIndex: 'error_message', ellipsis: true, render: (value?: string) => value || '-' },
      ]} /></ListTableSection>
    </Spin>
  </PageContent>;
}
