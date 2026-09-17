import { useCallback, useEffect, useMemo, useState } from 'react';
import { Alert, Button, Select, Spin, Tag } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { Background, Controls, MarkerType, ReactFlow, type Edge, type Node } from '@xyflow/react';
import dagre from '@dagrejs/dagre';
import { useNavigate } from 'react-router-dom';
import { getScheduleDag } from '../../api/tasks';
import type { ScheduleDagVO } from '../../types';
import '@xyflow/react/dist/style.css';

export function layout(data: ScheduleDagVO, status: string, navigate: (path: string) => void): { nodes: Node[]; edges: Edge[] } {
  const visible = new Set(data.nodes.filter((node) => status === 'ALL' || (node.last_execution_status || 'NONE') === status).map((node) => node.id));
  const failedChain = new Set(data.nodes.filter((node) => node.last_execution_status === 'FAILED').map((node) => node.id));
  let changed = true;
  while (changed) {
    changed = false;
    for (const edge of data.edges) {
      if (failedChain.has(edge.upstreamTaskId) && !failedChain.has(edge.taskId)) { failedChain.add(edge.taskId); changed = true; }
    }
  }
  const graph = new dagre.graphlib.Graph().setDefaultEdgeLabel(() => ({}));
  graph.setGraph({ rankdir: 'LR', ranksep: 90, nodesep: 36 });
  data.nodes.filter((node) => visible.has(node.id)).forEach((node) => graph.setNode(String(node.id), { width: 230, height: 112 }));
  data.edges.filter((edge) => visible.has(edge.taskId) && visible.has(edge.upstreamTaskId)).forEach((edge) => graph.setEdge(String(edge.upstreamTaskId), String(edge.taskId)));
  dagre.layout(graph);
  return {
    nodes: data.nodes.filter((item) => visible.has(item.id)).map((item) => {
      const point = graph.node(String(item.id));
      return { id: String(item.id), position: { x: point.x - 115, y: point.y - 56 }, data: { label: <div className="dag-node-content"><strong>{item.id} · {item.name}</strong><span><Tag color={item.last_execution_status === 'FAILED' ? 'error' : item.last_execution_status === 'SUCCEEDED' ? 'success' : 'default'}>{item.last_execution_status || '无实例'}</Tag>{item.criticalPath ? <Tag color="gold">关键路径</Tag> : null}</span><small>{item.durationSampleCount ? `中位耗时 ${Math.round((item.medianDurationMs || 0) / 1000)} 秒 · ${item.durationSampleCount} 个样本` : '无耗时样本'} · 上游 {item.upstreamCount} / 下游 {item.downstreamCount}</small><span className="dag-node-actions nodrag"><Button type="link" size="small" onClick={() => navigate(`/tasks/${item.id}/edit`)}>任务</Button>{item.last_execution_id ? <Button type="link" size="small" onClick={() => navigate(`/tasks/${item.id}/executions/${item.last_execution_id}`)}>实例</Button> : null}{item.last_backfill_id ? <Button type="link" size="small" onClick={() => navigate(`/tasks?scheduleTaskId=${item.id}&backfillId=${item.last_backfill_id}`)}>补数</Button> : null}</span></div> }, className: `${item.criticalPath ? 'schedule-dag-node critical' : 'schedule-dag-node'}${failedChain.has(item.id) ? ' failed-chain' : ''}` };
    }),
    edges: data.edges.filter((edge) => visible.has(edge.taskId) && visible.has(edge.upstreamTaskId)).map((edge) => { const failed = failedChain.has(edge.upstreamTaskId) && failedChain.has(edge.taskId); return { id: String(edge.id), source: String(edge.upstreamTaskId), target: String(edge.taskId), label: edge.dependencyType, markerEnd: { type: MarkerType.ArrowClosed, color: failed ? '#ff4d4f' : undefined }, animated: data.criticalPathTaskIds.includes(edge.taskId) && data.criticalPathTaskIds.includes(edge.upstreamTaskId), style: { ...(edge.dependencyType === 'SUCCESS' ? {} : { strokeDasharray: '5 4' }), ...(failed ? { stroke: '#ff4d4f', strokeWidth: 2 } : {}) } }; }),
  };
}

export default function ScheduleDagPage() {
  const navigate = useNavigate();
  const [data, setData] = useState<ScheduleDagVO>(); const [loading, setLoading] = useState(false); const [error, setError] = useState(''); const [status, setStatus] = useState('ALL');
  const load = useCallback(async () => { setLoading(true); setError(''); try { setData(await getScheduleDag()); } catch (e) { setError((e as Error).message); } finally { setLoading(false); } }, []);
  useEffect(() => { void load(); }, [load]);
  const graph = useMemo(() => data ? layout(data, status, navigate) : { nodes: [], edges: [] }, [data, navigate, status]);
  return <div className="schedule-dag-page">
    <header><div><h2>调度拓扑</h2><p>展示所有启用任务、依赖、最近实例和基于最近 20 次成功实例中位耗时估算的关键路径。</p></div><div><Select value={status} onChange={setStatus} options={['ALL','SUCCEEDED','FAILED','RUNNING','NONE'].map((value) => ({ value, label: value === 'ALL' ? '全部状态' : value }))} /><Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button></div></header>
    {error ? <Alert type="error" showIcon message="调度拓扑加载失败" description={error} /> : null}
    <section>{loading ? <Spin /> : <ReactFlow nodes={graph.nodes} edges={graph.edges} fitView onNodeDoubleClick={(_, node) => navigate(`/tasks/${node.id}/edit`)}><Background /><Controls /></ReactFlow>}</section>
  </div>;
}
