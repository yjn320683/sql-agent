import { useCallback, useEffect, useMemo, useState } from 'react';
import { Alert, Button, Drawer, Input, Segmented, Select, Spin, Table, Tag, Typography, message } from 'antd';
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { Background, Controls, MarkerType, ReactFlow, type Edge, type Node } from '@xyflow/react';
import dagre from '@dagrejs/dagre';
import { useSearchParams } from 'react-router-dom';
import { getAssetLineageGraph, searchLineageAssets, type AssetLineageGraph, type AssetLineageNode } from '../../api/assets';
import '@xyflow/react/dist/style.css';

function layout(data?: AssetLineageGraph): { nodes: Node[]; edges: Edge[] } {
  if (!data) return { nodes: [], edges: [] };
  const graph = new dagre.graphlib.Graph().setDefaultEdgeLabel(() => ({}));
  graph.setGraph({ rankdir: 'LR', ranksep: 90, nodesep: 34 });
  data.nodes.forEach((node) => graph.setNode(node.id, { width: node.nodeType === 'TASK' ? 220 : 260, height: 76 }));
  data.edges.forEach((edge) => graph.setEdge(edge.source, edge.target)); dagre.layout(graph);
  return {
    nodes: data.nodes.map((item) => { const point = graph.node(item.id) || { x: 0, y: 0 }; const asset = item.qualifiedName || [item.catalog, item.database, item.table, item.column].filter(Boolean).join('.'); return { id: item.id, position: { x: point.x - 120, y: point.y - 38 }, className: `asset-lineage-node ${item.nodeType.toLowerCase()}`, data: { label: <div><Tag color={item.nodeType === 'TASK' ? 'blue' : item.nodeType === 'COLUMN' ? 'purple' : 'cyan'}>{item.nodeType === 'TASK' ? item.taskType || '任务' : item.nodeType === 'COLUMN' ? '字段' : item.catalog || '表'}</Tag><strong>{item.nodeType === 'TASK' ? item.taskName || `任务 ${item.taskId}` : asset}</strong></div> } }; }),
    edges: data.edges.map((item) => ({ id: item.id, source: item.source, target: item.target, label: item.usageType || item.relationKind, markerEnd: { type: MarkerType.ArrowClosed } })),
  };
}

export default function AssetLineagePage() {
  const [params, setParams] = useSearchParams();
  const [keyword, setKeyword] = useState(params.get('table') || ''); const [catalog, setCatalog] = useState(params.get('catalog') || 'all');
  const [direction, setDirection] = useState('BOTH'); const [depth, setDepth] = useState(2); const [rows, setRows] = useState<AssetLineageNode[]>([]);
  const [view, setView] = useState<'TABLE' | 'COLUMN' | 'TASK'>((params.get('view') as 'TABLE' | 'COLUMN' | 'TASK') || 'TABLE');
  const [column, setColumn] = useState(params.get('column') || '');
  const [selected, setSelected] = useState<AssetLineageNode>(); const [focusedNode, setFocusedNode] = useState<AssetLineageNode>(); const [graphData, setGraphData] = useState<AssetLineageGraph>(); const [loading, setLoading] = useState(false);
  const loadGraph = useCallback(async (asset: AssetLineageNode) => { if (view === 'COLUMN' && !column.trim()) { message.warning('字段血缘需要输入字段名'); return; } setLoading(true); try { const query = new URLSearchParams({ catalog: asset.catalog || 'hive', database: asset.database || '', table: asset.table || '', column: view === 'COLUMN' ? column.trim() : '', direction, depth: String(depth), view }); setGraphData(await getAssetLineageGraph(query)); setSelected(asset); } catch (error) { message.error((error as Error).message); } finally { setLoading(false); } }, [column, depth, direction, view]);
  const search = useCallback(async () => { setLoading(true); try { const query = new URLSearchParams({ keyword, catalog, page: '1', pageSize: '100' }); const value = await searchLineageAssets(query); setRows(value.records); const requestedTable = params.get('table'); const requestedDb = params.get('database'); const first = value.records.find((item) => (!requestedTable || item.table === requestedTable) && (!requestedDb || item.database === requestedDb)) || value.records[0]; if (first) await loadGraph(first); else setGraphData(undefined); } catch (error) { message.error((error as Error).message); } finally { setLoading(false); } }, [catalog, keyword, loadGraph, params]);
  useEffect(() => { void search(); }, []); // URL 初次定位；后续由查询按钮显式刷新。
  useEffect(() => { if (selected && (view !== 'COLUMN' || column.trim())) void loadGraph(selected); }, [depth, direction, view]); // 切换视图、方向或深度时重载。
  const graph = useMemo(() => layout(graphData), [graphData]);
  return <div className="asset-lineage-page">
    <section className="asset-lineage-viewbar"><Segmented value={view} onChange={(value) => setView(value as 'TABLE' | 'COLUMN' | 'TASK')} options={[{ label: '表血缘', value: 'TABLE' }, { label: '字段血缘', value: 'COLUMN' }, { label: '任务血缘', value: 'TASK' }]} /><Typography.Text type="secondary">{view === 'TABLE' ? '表 → 生产任务 → 下游表' : view === 'COLUMN' ? '源字段 → 表达式派生 → 目标字段' : '上游任务 → 中间表 → 下游任务'}</Typography.Text><Button className="asset-lineage-refresh" icon={<ReloadOutlined />} onClick={() => void search()}>刷新</Button></section>
    <section className={view === 'COLUMN' ? 'asset-lineage-toolbar with-column' : 'asset-lineage-toolbar'}><Input value={keyword} onChange={(event) => setKeyword(event.target.value)} onPressEnter={() => void search()} prefix={<SearchOutlined />} allowClear placeholder="搜索库或表" />{view === 'COLUMN' ? <Input value={column} onChange={(event) => setColumn(event.target.value)} onPressEnter={() => selected && void loadGraph(selected)} placeholder="输入字段名" /> : null}<Select value={catalog} onChange={setCatalog} options={[{ value: 'all', label: '全部 Catalog' }, { value: 'hive', label: 'Hive' }, { value: 'paimon', label: 'Paimon' }, { value: 'mysql', label: 'MySQL' }]} /><Select value={direction} onChange={setDirection} options={[{ value: 'BOTH', label: '上下游' }, { value: 'UPSTREAM', label: '仅上游' }, { value: 'DOWNSTREAM', label: '仅下游' }]} /><Select value={depth} onChange={setDepth} options={[1,2,3].map((value) => ({ value, label: `${value} 层` }))} /><Button type="primary" icon={<SearchOutlined />} onClick={() => { setParams({ ...(keyword ? { table: keyword } : {}), ...(column ? { column } : {}), view }); if (selected) void loadGraph(selected); else void search(); }}>查询</Button></section>
    {!graphData?.complete ? <Alert type="warning" showIcon message="血缘结果不完整" description={(graphData?.missingReasons || []).join('；')} /> : null}
    <section className="asset-lineage-content"><aside><Table size="small" rowKey="id" loading={loading} pagination={false} dataSource={rows} columns={[{ title: '资产', render: (_, row) => <Button type="link" onClick={() => void loadGraph(row)}>{row.qualifiedName}</Button> }, { title: '类型', width: 90, render: (_, row) => <Tag>{row.catalog}</Tag> }]} /></aside><main>{loading && !graphData ? <Spin /> : graph.nodes.length ? <ReactFlow nodes={graph.nodes} edges={graph.edges} fitView onNodeClick={(_, node) => setFocusedNode(graphData?.nodes.find((item) => item.id === node.id))}><Background /><Controls /></ReactFlow> : <div className="asset-lineage-empty">没有可展示的生产血缘</div>}</main></section>
    <Drawer width={460} title="节点详情" open={Boolean(focusedNode)} onClose={() => setFocusedNode(undefined)}>{focusedNode ? <dl className="asset-lineage-node-detail">{Object.entries(focusedNode).filter(([, value]) => value != null && value !== '').map(([key, value]) => <div key={key}><dt>{key}</dt><dd>{String(value)}</dd></div>)}</dl> : null}</Drawer>
  </div>;
}
