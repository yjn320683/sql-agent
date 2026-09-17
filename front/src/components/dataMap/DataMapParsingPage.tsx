import { useEffect, useState } from 'react';
import { Alert, Button, Card, Col, Popconfirm, Row, Statistic, Table, Tag, Typography, message } from 'antd';
import { ReloadOutlined, SyncOutlined } from '@ant-design/icons';
import { getDataMapCoverage, getDataMapRuns, rebuildDataMap, reprojectDataMap, type DataMapCoverage, type DataMapRun } from '../../api/dataMap';

const color = (status: string) => status === 'SUCCEEDED' ? 'success' : status === 'FAILED' ? 'error' : status === 'RUNNING' ? 'processing' : 'warning';

export default function DataMapParsingPage() {
  const [coverage, setCoverage] = useState<DataMapCoverage>();
  const [rows, setRows] = useState<DataMapRun[]>([]);
  const [loading, setLoading] = useState(false);
  const load = async () => { setLoading(true); try { const [nextCoverage, runs] = await Promise.all([getDataMapCoverage(), getDataMapRuns()]); setCoverage(nextCoverage); setRows(runs.records); } catch (error) { message.error((error as Error).message); } finally { setLoading(false); } };
  useEffect(() => { void load(); }, []);
  const reproject = async () => { try { const value = await reprojectDataMap(); message.success(`已投影 ${value.projected} 个任务版本`); await load(); } catch (error) { message.error((error as Error).message); } };
  const rebuild = async () => { try { const value = await rebuildDataMap(); message.success(`已建立第 ${value.generation} 代全量投影`); await load(); } catch (error) { message.error((error as Error).message); } };
  return <div className="data-map-page">
    <header className="data-map-page-header"><div><Typography.Title level={2}>解析监控</Typography.Title><Typography.Text type="secondary">监控当前生产任务的血缘事实完整性，以及 MySQL 快照到 Neo4j 的可靠投影。</Typography.Text></div><div className="data-map-header-actions"><Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button><Button icon={<SyncOutlined />} onClick={() => void reproject()}>补投影</Button><Popconfirm title="构建新的全量图代次？" description="新代次完整投影成功后才会切换，不影响当前查询。" onConfirm={() => void rebuild()}><Button type="primary">全量重建</Button></Popconfirm></div></header>
    <Alert showIcon type="info" message="口径说明" description="离线仅使用当前生效版本，实时使用最新保存版本。解析不完整时保留表级事实与诊断，不会显示成“无血缘”。" />
    <Row gutter={[16,16]} className="data-map-statistics"><Col xs={12} lg={6}><Card><Statistic title="完整" value={coverage?.complete || 0} /></Card></Col><Col xs={12} lg={6}><Card><Statistic title="部分成功" value={coverage?.partial || 0} valueStyle={{ color: '#d46b08' }} /></Card></Col><Col xs={12} lg={6}><Card><Statistic title="解析失败" value={coverage?.failed || 0} valueStyle={{ color: '#cf1322' }} /></Card></Col><Col xs={12} lg={6}><Card><Statistic title="待投影/投影失败" value={coverage?.pendingProjection || 0} /></Card></Col></Row>
    <Card title="运行记录"><Table<DataMapRun> loading={loading} rowKey="id" dataSource={rows} columns={[
      { title: '批次', dataIndex: 'id', width: 90 }, { title: '类型', dataIndex: 'run_type', width: 130 },
      { title: '状态', dataIndex: 'status', width: 120, render: (value: string) => <Tag color={color(value)}>{value}</Tag> },
      { title: '扫描', dataIndex: 'scanned_count', width: 90 }, { title: '完整', dataIndex: 'success_count', width: 90 },
      { title: '部分', dataIndex: 'partial_count', width: 90 }, { title: '失败', dataIndex: 'failed_count', width: 90 },
      { title: '投影', dataIndex: 'projected_count', width: 90 }, { title: '开始时间', dataIndex: 'started_at', width: 190 },
      { title: '结果', dataIndex: 'error_message', ellipsis: true, render: (value?: string) => value || '-' },
    ]} /></Card>
  </div>;
}
