import { useCallback, useEffect, useMemo, useState } from 'react';
import { Alert, Button, Empty, Segmented, Skeleton, Tag, Tooltip } from 'antd';
import {
  ApartmentOutlined,
  BranchesOutlined,
  DatabaseOutlined,
  PlusOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { getTaskDependencies, getTaskLineage } from '../../api/workspace';
import type {
  LineageValidationStatus,
  SqlLineageTableVO,
  TaskDependenciesVO,
  TaskDependencyNodeVO,
  TaskLineageVO,
} from '../../types';

interface Props {
  taskId?: number;
  versionNo?: number;
  defaultDb?: string;
  taskName: string;
  refreshKey: number;
  onInsertSql: (text: string) => void;
}

interface GraphNode {
  key: string;
  title: string;
  subtitle?: string;
  kind: 'table' | 'task' | 'empty';
  status?: LineageValidationStatus;
  onClick?: () => void;
}

const statusMeta: Record<LineageValidationStatus, { color: string; label: string }> = {
  EXISTS: { color: 'success', label: '已验证' },
  MISSING: { color: 'error', label: '不存在' },
  UNRESOLVED: { color: 'default', label: '未解析' },
  UNKNOWN: { color: 'warning', label: '未知' },
};

function graphHeight(leftCount: number, rightCount: number) {
  return Math.max(190, Math.max(leftCount, rightCount, 1) * 68 + 28);
}

function nodeTop(index: number, count: number, height: number) {
  return ((index + 0.5) * height / Math.max(count, 1)) - 25;
}

function GraphCard({ node, side, style }: {
  node: GraphNode;
  side: 'left' | 'center' | 'right';
  style?: React.CSSProperties;
}) {
  const status = node.status ? statusMeta[node.status] : undefined;
  const content = (
    <>
      <span className="lineage-graph-node-icon">
        {node.kind === 'table' ? <DatabaseOutlined /> : node.kind === 'task' ? <BranchesOutlined /> : null}
      </span>
      <span className="lineage-graph-node-copy">
        <strong>{node.title}</strong>
        {node.subtitle ? <small>{node.subtitle}</small> : null}
      </span>
      {status ? <Tag color={status.color}>{status.label}</Tag> : null}
      {node.onClick && side !== 'center' ? <PlusOutlined className="lineage-graph-node-action" /> : null}
    </>
  );

  return node.onClick ? (
    <button type="button" className={`lineage-graph-node ${side}`} style={style} onClick={node.onClick}>{content}</button>
  ) : (
    <div className={`lineage-graph-node ${side} ${node.kind === 'empty' ? 'empty' : ''}`} style={style}>{content}</div>
  );
}

function LineageGraph({ left, center, right, leftTitle, rightTitle }: {
  left: GraphNode[];
  center: GraphNode;
  right: GraphNode[];
  leftTitle: string;
  rightTitle: string;
}) {
  const leftNodes = left.length ? left : [{ key: 'empty-left', title: `暂无${leftTitle}`, kind: 'empty' as const }];
  const rightNodes = right.length ? right : [{ key: 'empty-right', title: `暂无${rightTitle}`, kind: 'empty' as const }];
  const height = graphHeight(leftNodes.length, rightNodes.length);
  const centerY = height / 2;

  return (
    <div className="lineage-graph-shell">
      <div className="lineage-graph-column-title left">{leftTitle}<strong>{left.length}</strong></div>
      <div className="lineage-graph-column-title center">当前任务</div>
      <div className="lineage-graph-column-title right">{rightTitle}<strong>{right.length}</strong></div>
      <div className="lineage-graph-canvas" style={{ height }}>
        <svg aria-hidden="true" viewBox={`0 0 1000 ${height}`} preserveAspectRatio="none">
          {leftNodes.map((node, index) => {
            const y = nodeTop(index, leftNodes.length, height) + 25;
            return <path key={node.key} d={`M 300 ${y} C 330 ${y}, 330 ${centerY}, 360 ${centerY}`} />;
          })}
          {rightNodes.map((node, index) => {
            const y = nodeTop(index, rightNodes.length, height) + 25;
            return <path key={node.key} d={`M 640 ${centerY} C 670 ${centerY}, 670 ${y}, 700 ${y}`} />;
          })}
        </svg>
        {leftNodes.map((node, index) => (
          <GraphCard key={node.key} node={node} side="left" style={{ top: nodeTop(index, leftNodes.length, height) }} />
        ))}
        <GraphCard node={center} side="center" style={{ top: centerY - 25 }} />
        {rightNodes.map((node, index) => (
          <GraphCard key={node.key} node={node} side="right" style={{ top: nodeTop(index, rightNodes.length, height) }} />
        ))}
      </div>
    </div>
  );
}

export default function WorkspaceLineagePanel({
  taskId,
  versionNo,
  defaultDb,
  taskName,
  refreshKey,
  onInsertSql,
}: Props) {
  const navigate = useNavigate();
  const [mode, setMode] = useState<'tables' | 'tasks'>('tables');
  const [lineage, setLineage] = useState<TaskLineageVO>();
  const [dependencies, setDependencies] = useState<TaskDependenciesVO>();
  const [loading, setLoading] = useState(false);
  const [dependencyLoading, setDependencyLoading] = useState(false);
  const [error, setError] = useState('');
  const [dependencyError, setDependencyError] = useState('');

  const load = useCallback(async () => {
    if (!taskId) return;
    setLoading(true);
    try {
      setLineage(await getTaskLineage(taskId, defaultDb, versionNo));
      setError('');
    } catch (loadError) {
      setLineage(undefined);
      setError((loadError as Error).message);
    } finally {
      setLoading(false);
    }
  }, [defaultDb, taskId, versionNo]);

  const loadDependencies = useCallback(async () => {
    if (!taskId) return;
    setDependencyLoading(true);
    try {
      setDependencies(await getTaskDependencies(taskId, defaultDb, versionNo));
      setDependencyError('');
    } catch (loadError) {
      setDependencies(undefined);
      setDependencyError((loadError as Error).message);
    } finally {
      setDependencyLoading(false);
    }
  }, [defaultDb, taskId, versionNo]);

  useEffect(() => { void load(); }, [load, refreshKey]);
  useEffect(() => {
    if (mode === 'tasks') void loadDependencies();
  }, [loadDependencies, mode, refreshKey]);

  const tableGraph = useMemo(() => {
    if (!lineage) return undefined;
    const mapTable = (item: SqlLineageTableVO, direction: 'input' | 'output'): GraphNode => ({
      key: `${direction}-${item.qualifiedName}-${item.validationStatus}`,
      title: item.qualifiedName,
      subtitle: item.tableType || item.comment || 'Hive table',
      kind: 'table',
      status: item.validationStatus,
      onClick: () => onInsertSql(item.qualifiedName),
    });
    return {
      left: lineage.inputs.map((item) => mapTable(item, 'input')),
      center: {
        key: `task-${taskId}`,
        title: taskName || lineage.taskName,
        subtitle: `任务 ${taskId} · ${lineage.statementCount} 条语句`,
        kind: 'task' as const,
      },
      right: lineage.outputs.map((item) => mapTable(item, 'output')),
    };
  }, [lineage, onInsertSql, taskId, taskName]);

  const dependencyGraph = useMemo(() => {
    if (!dependencies) return undefined;
    const mapTask = (item: TaskDependencyNodeVO, direction: 'upstream' | 'downstream'): GraphNode => ({
      key: `${direction}-${item.taskId}`,
      title: item.taskName,
      subtitle: `任务 ${item.taskId}${item.tables?.length ? ` · ${item.tables.join('、')}` : ''}`,
      kind: 'task',
      onClick: () => navigate(`/tasks/${item.taskId}/edit`),
    });
    return {
      left: dependencies.directUpstream.map((item) => mapTask(item, 'upstream')),
      center: {
        key: `task-${dependencies.target.taskId}`,
        title: dependencies.target.taskName,
        subtitle: `任务 ${dependencies.target.taskId}`,
        kind: 'task' as const,
      },
      right: dependencies.directDownstream.map((item) => mapTask(item, 'downstream')),
    };
  }, [dependencies, navigate]);

  if (!taskId) {
    return <div className="bottom-lineage-panel"><Empty description="保存任务后可解析真实 SQL 血缘" /></div>;
  }

  const activeLoading = mode === 'tables' ? loading : dependencyLoading;
  const activeError = mode === 'tables' ? error : dependencyError;
  const activeGraph = mode === 'tables' ? tableGraph : dependencyGraph;

  return (
    <div className="bottom-lineage-panel">
      <div className="lineage-bottom-toolbar">
        <Segmented
          size="small"
          className="lineage-mode-switch ui-flat-segmented"
          value={mode}
          options={[
            { label: <span><ApartmentOutlined /> 表血缘</span>, value: 'tables' },
            { label: <span><BranchesOutlined /> 任务依赖</span>, value: 'tasks' },
          ]}
          onChange={(value) => setMode(value as 'tables' | 'tasks')}
        />
        <span className="lineage-bottom-source">基于{versionNo ? `版本 v${versionNo}` : '任务当前生效代码'} · {defaultDb || '未指定默认库'}</span>
        <Tooltip title="重新解析已保存 SQL">
          <Button
            type="text"
            size="small"
            icon={<ReloadOutlined />}
            loading={activeLoading}
            onClick={() => void (mode === 'tables' ? load() : loadDependencies())}
          />
        </Tooltip>
      </div>
      <div className="lineage-bottom-content">
        {activeError ? <Alert type="error" showIcon message={mode === 'tables' ? '血缘解析失败' : '任务依赖解析失败'} description={activeError} /> : null}
        {mode === 'tables' && lineage && (!lineage.complete || lineage.warnings.length) ? (
          <Alert type="warning" showIcon message="静态血缘不完整" description={[...lineage.warnings, ...lineage.missingReasons].join('；')} />
        ) : null}
        {mode === 'tasks' && dependencies && !dependencies.complete ? (
          <Alert type="warning" showIcon message="任务影响范围不完整" description={[...dependencies.warnings, ...dependencies.missingReasons].join('；')} />
        ) : null}
        {activeLoading && !activeGraph ? <Skeleton active paragraph={{ rows: 4 }} /> : activeGraph ? (
          <LineageGraph
            left={activeGraph.left}
            center={activeGraph.center}
            right={activeGraph.right}
            leftTitle={mode === 'tables' ? '输入表' : '直接上游'}
            rightTitle={mode === 'tables' ? '输出表' : '直接下游'}
          />
        ) : null}
        {mode === 'tables' && lineage ? (
          <div className="lineage-bottom-facts">
            {lineage.ctes.map((cte) => <Tag key={cte.name}>CTE {cte.name}</Tag>)}
            <span>来源 <code>{lineage.source}</code></span>
          </div>
        ) : null}
        {mode === 'tasks' && dependencies ? (
          <div className="lineage-bottom-facts">
            {dependencies.externalInputs.map((table) => <Tag key={table}>外部输入 {table}</Tag>)}
            {dependencies.selfDependencies.length ? <Tag color="warning">同表读写 {dependencies.selfDependencies.join('、')}</Tag> : null}
            {dependencies.producerConflicts.length ? <Tag color="error">多任务写入冲突 {dependencies.producerConflicts.length}</Tag> : null}
            <span>扫描 {dependencies.scannedTaskCount}/{dependencies.totalTaskCount} 个真实任务</span>
          </div>
        ) : null}
      </div>
    </div>
  );
}
