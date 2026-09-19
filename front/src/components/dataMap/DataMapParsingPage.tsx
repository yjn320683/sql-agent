import { useEffect, useState } from 'react';
import { Alert, Button, Card, Col, Popconfirm, Row, Statistic, Table, Tag, Typography, message } from 'antd';
import { ReloadOutlined, SyncOutlined } from '@ant-design/icons';
import { getDataMapCoverage, getDataMapReadiness, getDataMapRuns, rebuildDataMap, reprojectDataMap, type DataMapCoverage, type DataMapReadiness, type DataMapRun } from '../../api/dataMap';
import PageContent from '../layout/PageContent';
import ListToolbar from '../layout/ListToolbar';
import ListTableSection from '../layout/ListTableSection';

const color = (status: string) => status === 'SUCCEEDED' ? 'success' : status === 'FAILED' ? 'error' : status === 'RUNNING' ? 'processing' : 'warning';

export default function DataMapParsingPage() {
  const [coverage, setCoverage] = useState<DataMapCoverage>();
  const [readiness, setReadiness] = useState<DataMapReadiness>();
  const [rows, setRows] = useState<DataMapRun[]>([]);
  const [loading, setLoading] = useState(false);
  const load = async () => { setLoading(true); try { const [nextCoverage, runs, nextReadiness] = await Promise.all([getDataMapCoverage(), getDataMapRuns(), getDataMapReadiness()]); setCoverage(nextCoverage); setRows(runs.records); setReadiness(nextReadiness); } catch (error) { message.error((error as Error).message); } finally { setLoading(false); } };
  useEffect(() => { void load(); }, []);
  const reproject = async () => { try { const value = await reprojectDataMap(); message.success(`已投影 ${value.projected} 个任务版本`); await load(); } catch (error) { message.error((error as Error).message); } };
  const rebuild = async () => { try { const value = await rebuildDataMap(); message.success(`已建立第 ${value.generation} 代全量投影`); await load(); } catch (error) { message.error((error as Error).message); } };
  return <PageContent className="data-map-page">
    <ListToolbar className="data-map-page-toolbar"><Typography.Text type="secondary">生产任务血缘完整性与 MySQL → Neo4j 投影状态</Typography.Text><div className="data-map-header-actions"><Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button><Button icon={<SyncOutlined />} disabled={!readiness?.ready} title={!readiness?.ready ? 'Neo4j 连接或图约束尚未就绪' : undefined} onClick={() => void reproject()}>补投影</Button><Popconfirm disabled={!readiness?.ready || Boolean(readiness?.buildingGenerations.length)} title="构建新的全量图代次？" description="新代次完整投影成功后才会切换，不影响当前查询。" onConfirm={() => void rebuild()}><Button type="primary" disabled={!readiness?.ready || Boolean(readiness?.buildingGenerations.length)} title={!readiness?.ready ? 'Neo4j 连接或图约束尚未就绪' : readiness?.buildingGenerations.length ? '已有全量代次正在构建' : undefined}>全量重建</Button></Popconfirm></div></ListToolbar>
    <Alert className="data-map-status-alert" banner showIcon type={readiness?.ready ? 'info' : 'warning'} message={readiness?.ready ? `图存储可用，当前生效代次 ${readiness.activeGeneration}` : 'Neo4j 连接或图约束尚未就绪，补投影和全量重建已禁用'} description={readiness ? `待投影 ${readiness.projection.pending} · 处理中 ${readiness.projection.processing} · 超时 ${readiness.projection.stale} · 失败 ${readiness.projection.failed}` : undefined} />
    <Row gutter={[16,16]} className="data-map-statistics"><Col xs={12} lg={6}><Card><Statistic title="完整" value={coverage?.complete || 0} /></Card></Col><Col xs={12} lg={6}><Card><Statistic title="部分成功" value={coverage?.partial || 0} valueStyle={{ color: '#d46b08' }} /></Card></Col><Col xs={12} lg={6}><Card><Statistic title="解析失败" value={coverage?.failed || 0} valueStyle={{ color: '#cf1322' }} /></Card></Col><Col xs={12} lg={6}><Card><Statistic title="待投影/投影失败" value={coverage?.pendingProjection || 0} /></Card></Col></Row>
    <ListTableSection><Table<DataMapRun> loading={loading} rowKey="id" dataSource={rows} columns={[
      { title: '批次', dataIndex: 'id', width: 90 }, { title: '类型', dataIndex: 'run_type', width: 130 },
      { title: '状态', dataIndex: 'status', width: 120, render: (value: string) => <Tag color={color(value)}>{value}</Tag> },
      { title: '扫描', dataIndex: 'scanned_count', width: 90 }, { title: '完整', dataIndex: 'success_count', width: 90 },
      { title: '部分', dataIndex: 'partial_count', width: 90 }, { title: '失败', dataIndex: 'failed_count', width: 90 },
      { title: '投影', dataIndex: 'projected_count', width: 90 }, { title: '开始时间', dataIndex: 'started_at', width: 190 },
      { title: '结果', dataIndex: 'error_message', ellipsis: true, render: (value?: string) => value || '-' },
    ]} /></ListTableSection>
  </PageContent>;
}
