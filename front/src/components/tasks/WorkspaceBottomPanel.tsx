import { useCallback, useEffect, useState } from 'react';
import { Alert, Button, Empty, Input, Spin, Table, Tabs, Tooltip, Typography, message } from 'antd';
import {
  ApartmentOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  CodeOutlined,
  FileTextOutlined,
  ReloadOutlined,
  RobotOutlined,
  SafetyCertificateOutlined,
  TableOutlined,
  EditOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { listTaskExecutions } from '../../api/tasks';
import type { HiveExplainVO, HiveValidationVO, SqlQueryPreviewVO, TaskExecutionVO, TaskQualityVO } from '../../types';
import TaskStatusTag, { isActiveExecution } from './TaskStatusTag';
import TaskQualityPanel from './TaskQualityPanel';
import WorkspaceLineagePanel from './WorkspaceLineagePanel';
import ExecutionDetailDrawer from './ExecutionDetailDrawer';

export type WorkbenchTab = 'parameters' | 'preview' | 'quality' | 'validation' | 'explain' | 'lineage' | 'instances';

interface Props {
  taskId?: number;
  activeTab: WorkbenchTab;
  onActiveTabChange: (tab: WorkbenchTab) => void;
  validation?: HiveValidationVO;
  validationLoading: boolean;
  validationError?: string;
  explain?: HiveExplainVO;
  explainLoading: boolean;
  explainError?: string;
  quality?: TaskQualityVO;
  qualityLoading: boolean;
  qualityError?: string;
  refreshKey: number;
  versionNo?: number;
  defaultDb?: string;
  taskName: string;
  lineageRefreshKey: number;
  onInsertSql: (text: string) => void;
  parameterJson?: string;
  parameterError?: string;
  onParameterJsonChange?: (value: string) => void;
  renderedSql?: string;
  preview?: SqlQueryPreviewVO;
  previewLoading?: boolean;
  previewError?: string;
}

function formatDuration(value?: number): string {
  if (value == null) return '-';
  if (value < 1000) return `${value} ms`;
  return `${(value / 1000).toFixed(1)} s`;
}

export default function WorkspaceBottomPanel({
  taskId,
  activeTab,
  onActiveTabChange,
  validation,
  validationLoading,
  validationError,
  explain,
  explainLoading,
  explainError,
  quality,
  qualityLoading,
  qualityError,
  refreshKey,
  versionNo,
  defaultDb,
  taskName,
  lineageRefreshKey,
  onInsertSql,
  parameterJson = '{}',
  parameterError,
  onParameterJsonChange,
  renderedSql,
  preview,
  previewLoading,
  previewError,
}: Props) {
  const navigate = useNavigate();
  const [instances, setInstances] = useState<TaskExecutionVO[]>([]);
  const [instanceLoading, setInstanceLoading] = useState(false);
  const [detailExecution, setDetailExecution] = useState<TaskExecutionVO>();

  const loadInstances = useCallback(async (silent = false) => {
    if (!taskId) return;
    if (!silent) setInstanceLoading(true);
    try {
      const response = await listTaskExecutions(taskId, 'all', 1, 20);
      setInstances(response.items);
      setDetailExecution((current) => current
        ? response.items.find((item) => item.id === current.id) || current
        : current);
    } catch (error) {
      if (!silent) message.error(`加载实例失败：${(error as Error).message}`);
    } finally {
      if (!silent) setInstanceLoading(false);
    }
  }, [taskId]);

  useEffect(() => { void loadInstances(); }, [loadInstances, refreshKey]);
  useEffect(() => {
    if (!instances.some((item) => isActiveExecution(item.status))) return undefined;
    const timer = window.setInterval(() => void loadInstances(true), 2000);
    return () => window.clearInterval(timer);
  }, [instances, loadInstances]);

  const instanceColumns = [
    { title: '实例', dataIndex: 'id', width: 70, fixed: 'left' as const },
    { title: '状态', dataIndex: 'status', width: 88, render: (value: TaskExecutionVO['status']) => <TaskStatusTag status={value} /> },
    { title: '提交时间', dataIndex: 'submittedAt', width: 150, render: (value: string) => value?.replace('T', ' ').slice(0, 19) || '-' },
    { title: '耗时', dataIndex: 'durationMs', width: 76, render: formatDuration },
    {
      title: '操作', key: 'action', width: 150, fixed: 'right' as const, render: (_: unknown, row: TaskExecutionVO) => (
        <span className="workbench-row-actions">
          <Button
            type="link"
            size="small"
            icon={<FileTextOutlined />}
            onClick={() => setDetailExecution(row)}
          >
            详情
          </Button>
          <Tooltip title="交给 Agent 诊断">
            <Button
              type="text"
              size="small"
              icon={<RobotOutlined />}
              aria-label="交给 Agent 诊断"
              onClick={() => navigate(`/chat?taskId=${row.taskId}&executionId=${row.id}`)}
            />
          </Tooltip>
        </span>
      ),
    },
  ];

  const validationContent = validationLoading ? <Spin /> : validationError ? (
    <Alert type="error" showIcon message="Hive 编译校验请求失败" description={validationError} />
  ) : validation ? (
    <div className={validation.valid ? 'workbench-validation success' : 'workbench-validation error'}>
      <div className="workbench-validation-title">
        {validation.valid ? <CheckCircleOutlined /> : <FileTextOutlined />}
        <strong>{validation.valid ? 'Hive 编译校验通过' : 'Hive 编译校验失败'}</strong>
        <span>{validation.defaultDb} · {validation.compilationMs} ms</span>
      </div>
      {validation.errors.map((item, index) => (
        <Alert key={`${item.type}-${index}`} type="error" showIcon message={item.message} />
      ))}
      {validation.warnings.map((item) => <Typography.Text key={item} type="warning">{item}</Typography.Text>)}
    </div>
  ) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="点击工具栏中的校验，使用目标 Hive 编译当前任务 SQL" />;

  const explainContent = explainLoading ? <Spin /> : explainError ? (
    <Alert type="error" showIcon message="Hive 执行计划获取失败" description={explainError} />
  ) : explain ? (
    <div className="workbench-explain">
      <div className="workbench-result-meta">
        <span>预测计划</span><span>{explain.defaultDb}</span><span>{explain.compilationMs} ms</span>
        {explain.truncated ? <span>已压缩</span> : null}
      </div>
      {(explain.risks || []).map((risk, index) => (
        <Alert
          key={`${risk.stepNo}-${risk.code}-${index}`}
          type="warning"
          showIcon
          message={`Step ${risk.stepNo}（${risk.stepName}）：${risk.message}`}
          description={`计划证据：${risk.evidence}`}
        />
      ))}
      <pre>{explain.planText}</pre>
    </div>
  ) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="点击工具栏中的 Explain，获取目标 Hive 的真实预测计划" />;

  const previewColumns = preview?.columns.map((column, index) => ({
    title: <span>{column.name}{column.type ? <small className="preview-column-type">{column.type}</small> : null}</span>,
    dataIndex: index,
    key: `${column.name}-${index}`,
    width: 180,
    ellipsis: true,
    render: (value: unknown) => value == null ? <Typography.Text type="secondary">NULL</Typography.Text> : String(value),
  })) || [];

  const items = [
    {
      key: 'parameters',
      label: <span><EditOutlined /> 参数展开</span>,
      children: (
        <div className="workbench-tab-content parameter-preview">
          <section>
            <strong>运行参数（JSON）</strong>
            <Input.TextArea value={parameterJson} rows={7} status={parameterError ? 'error' : undefined} onChange={(event) => onParameterJsonChange?.(event.target.value)} />
            {parameterError ? <Typography.Text type="danger">{parameterError}</Typography.Text> : <Typography.Text type="secondary">参数只用于当前预览，不会保存到任务。</Typography.Text>}
          </section>
          <section><strong>当前 Step 展开结果</strong><pre>{renderedSql || '点击“参数预览”生成展开结果'}</pre></section>
        </div>
      ),
    },
    {
      key: 'preview',
      label: <span><TableOutlined /> 数据预览</span>,
      children: (
        <div className="workbench-tab-content query-preview">
          {previewLoading ? <Spin /> : previewError ? <Alert type="error" showIcon message="只读预览失败" description={previewError} /> : preview ? <>
            <div className="workbench-result-meta"><span>Step {preview.stepNo} · {preview.stepName}</span><span>{preview.rowCount} 行</span><span>{preview.elapsedMs} ms</span>{preview.truncated ? <span>结果已截断</span> : null}</div>
            <Table rowKey={(_, index) => String(index)} size="small" columns={previewColumns} dataSource={preview.rows} pagination={false} scroll={{ x: 'max-content', y: 210 }} />
          </> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="选择 Step 后点击工具栏中的数据预览；仅执行服务端限量只读查询" />}
        </div>
      ),
    },
    { key: 'quality', label: <span><SafetyCertificateOutlined /> 质量检查</span>, children: <TaskQualityPanel quality={quality} loading={qualityLoading} error={qualityError} /> },
    { key: 'validation', label: <span><CheckCircleOutlined /> 编译校验</span>, children: <div className="workbench-tab-content centered">{validationContent}</div> },
    { key: 'explain', label: <span><CodeOutlined /> 执行计划</span>, children: <div className="workbench-tab-content">{explainContent}</div> },
    {
      key: 'lineage',
      label: <span><ApartmentOutlined /> 血缘</span>,
      children: (
        <div className="workbench-tab-content lineage">
          <WorkspaceLineagePanel
            taskId={taskId}
            versionNo={versionNo}
            defaultDb={defaultDb}
            taskName={taskName}
            refreshKey={lineageRefreshKey}
            onInsertSql={onInsertSql}
          />
        </div>
      ),
    },
    {
      key: 'instances',
      label: <span><ClockCircleOutlined /> 运行实例 {instances.length ? `(${instances.length})` : ''}</span>,
      children: (
        <div className="workbench-tab-content instances">
          <section className="workbench-instance-list" aria-label="运行实例列表">
            <div className="workbench-table-toolbar">
              <span>最近 20 个实例</span>
              <Tooltip title="刷新实例"><Button type="text" size="small" icon={<ReloadOutlined />} onClick={() => void loadInstances()} /></Tooltip>
            </div>
            <Table
              rowKey="id"
              size="small"
              loading={instanceLoading}
              columns={instanceColumns}
              dataSource={instances}
              pagination={false}
              scroll={{ x: 560, y: 164 }}
              locale={{ emptyText: '当前任务暂无执行实例' }}
              rowClassName={() => 'workbench-instance-row'}
            />
          </section>
        </div>
      ),
    },
  ];

  return (
    <>
      <Tabs
        className="workspace-bottom-tabs ui-flat-tabs"
        activeKey={activeTab}
        items={items}
        onChange={(key) => onActiveTabChange(key as WorkbenchTab)}
      />
      <ExecutionDetailDrawer
        execution={detailExecution}
        onClose={() => setDetailExecution(undefined)}
        onRefresh={() => loadInstances(true)}
      />
    </>
  );
}
