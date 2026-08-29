import { useCallback, useEffect, useState } from 'react';
import { Button, Drawer, Dropdown, Empty, Input, Modal, Pagination, Segmented, Space, Switch, Table, Tooltip, Typography, message } from 'antd';
import {
  BranchesOutlined, BugOutlined, CopyOutlined, EditOutlined, InboxOutlined,
  FullscreenExitOutlined, FullscreenOutlined, MoreOutlined, PlusOutlined,
  ReloadOutlined, RobotOutlined, SearchOutlined, UndoOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { useNavigate } from 'react-router-dom';
import { archiveTask, cloneTask, executeTask, getTask, listTasks, listTaskVersions, restoreTask, setTaskEnabled } from '../../api/tasks';
import type { SqlTaskPageVO, SqlTaskVO } from '../../types';
import { useAutoTableActionWidth } from '../../utils/useAutoTableActionWidth';
import TaskExecutionPanel from './TaskExecutionPanel';
import TaskStatusTag from './TaskStatusTag';
import TaskVersionDrawer from './TaskVersionDrawer';

const EMPTY: SqlTaskPageVO = { items: [], page: 1, pageSize: 20, total: 0 };
const formatter = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
});

interface Props {
  embedded?: boolean;
  refreshKey?: number;
  initialExecutionTaskId?: number;
  initialExecutionId?: number;
  onCreateTask?: () => void;
  onEditTask?: (taskId: number, title?: string, versionNo?: number) => void;
  onExecutionOpen?: (taskId: number, executionId?: number) => void;
  onExecutionClose?: () => void;
}

export default function TaskListPage({
  embedded = false,
  refreshKey = 0,
  initialExecutionTaskId,
  initialExecutionId,
  onCreateTask,
  onEditTask,
  onExecutionOpen,
  onExecutionClose,
}: Props) {
  const navigate = useNavigate();
  const { actionColumnWidth, actionRef } = useAutoTableActionWidth({ initialWidth: 328 });
  const [data, setData] = useState(EMPTY);
  const [keyword, setKeyword] = useState('');
  const [committedKeyword, setCommittedKeyword] = useState('');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [status, setStatus] = useState<'active' | 'archived' | 'all'>('active');
  const [loading, setLoading] = useState(false);
  const [debuggingTaskId, setDebuggingTaskId] = useState<number>();
  const [executionTask, setExecutionTask] = useState<SqlTaskVO>();
  const [versionTask, setVersionTask] = useState<SqlTaskVO>();
  const [executionFullscreen, setExecutionFullscreen] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setData(await listTasks(committedKeyword, page, pageSize, status));
    } catch (error) {
      message.error(`加载任务失败：${(error as Error).message}`);
    } finally {
      setLoading(false);
    }
  }, [committedKeyword, page, pageSize, status]);

  useEffect(() => { void load(); }, [load, refreshKey]);

  useEffect(() => {
    if (!initialExecutionTaskId) return;
    const existing = data.items.find((item) => item.id === initialExecutionTaskId);
    if (existing) {
      setExecutionTask(existing);
      return;
    }
    void getTask(initialExecutionTaskId).then(setExecutionTask).catch((error) => {
      message.error(`加载任务实例失败：${(error as Error).message}`);
      onExecutionClose?.();
    });
  }, [data.items, initialExecutionTaskId, onExecutionClose]);

  const editTask = async (task: SqlTaskVO) => {
    let versionNo: number | undefined;
    try {
      const versions = await listTaskVersions(task.id, 1, 100);
      versionNo = versions.items.find((version) => version.status === 'DRAFT' && version.canEdit)?.versionNo;
    } catch (error) {
      message.warning(`读取版本失败，将打开当前生效代码：${(error as Error).message}`);
    }
    if (onEditTask) onEditTask(task.id, task.name, versionNo);
    else navigate(`/tasks/${task.id}/edit${versionNo ? `?versionNo=${versionNo}` : ''}`);
  };

  const openExecutions = (task: SqlTaskVO, executionId?: number) => {
    setExecutionTask(task);
    setExecutionFullscreen(false);
    onExecutionOpen?.(task.id, executionId);
  };

  const changeArchived = (task: SqlTaskVO, archived: boolean) => Modal.confirm({
    title: archived ? `归档任务 ${task.id}？` : `恢复任务 ${task.id}？`,
    content: archived ? '归档后保留版本与实例，但不能编辑、调试或发起新的 Agent 请求。' : '恢复后任务将重新出现在活跃任务列表。',
    okText: archived ? '归档' : '恢复',
    okButtonProps: archived ? { danger: true } : undefined,
    onOk: async () => {
      await (archived ? archiveTask(task.id, task.revision) : restoreTask(task.id, task.revision));
      message.success(archived ? '任务已归档' : '任务已恢复');
      await load();
    },
  });

  const copy = async (task: SqlTaskVO) => {
    const created = await cloneTask(task.id);
    message.success(`已复制为任务 ${created.id}`);
    if (onEditTask) onEditTask(created.id, created.name);
    else navigate(`/tasks/${created.id}/edit`);
  };

  const changeEnabled = (task: SqlTaskVO) => {
    const enabled = !task.enabled;
    Modal.confirm({
      title: `${enabled ? '启用' : '停用'}任务 ${task.id}？`,
      content: `确认将“${task.name}”的任务状态切换为${enabled ? '启用' : '停用'}吗？`,
      okText: enabled ? '启用' : '停用',
      okButtonProps: enabled ? undefined : { danger: true },
      onOk: async () => {
        await setTaskEnabled(task.id, enabled, task.revision);
        message.success(`任务已${enabled ? '启用' : '停用'}`);
        await load();
      },
    });
  };

  const debugTask = async (task: SqlTaskVO) => {
    setDebuggingTaskId(task.id);
    try {
      const parameters = Object.fromEntries(
        (task.parameters || [])
          .filter((parameter) => parameter.defaultValue !== undefined)
          .map((parameter) => [parameter.name, parameter.defaultValue]),
      );
      const execution = await executeTask(task.id, { revision: task.revision, parameters });
      message.success(`调试实例 ${execution.id} 已启动`);
      setExecutionTask(task);
      setExecutionFullscreen(false);
      onExecutionOpen?.(task.id, execution.id);
      await load();
    } catch (error) {
      message.error(`启动调试失败：${(error as Error).message}`);
    } finally {
      setDebuggingTaskId(undefined);
    }
  };

  const columns: ColumnsType<SqlTaskVO> = [
    { title: '任务 ID', dataIndex: 'id', width: 82, fixed: 'left', render: (id) => <span className="mono-id">{id}</span> },
    { title: '任务名称', dataIndex: 'name', width: 160, fixed: 'left', ellipsis: true, render: (value) => <span className="task-name-link">{value}</span> },
    {
      title: '任务状态', dataIndex: 'enabled', width: 96,
      render: (enabled, row) => (
        <Tooltip title={row.archived ? '归档任务不能变更启停状态' : undefined}>
          <span onClick={(event) => event.stopPropagation()}>
            <Switch
              size="small"
              checked={Boolean(enabled)}
              checkedChildren="启用"
              unCheckedChildren="停用"
              disabled={row.archived}
              onChange={() => changeEnabled(row)}
            />
          </span>
        </Tooltip>
      ),
    },
    { title: '最后结果', dataIndex: 'lastExecutionStatus', width: 96, render: (status) => <TaskStatusTag status={status} /> },
    { title: '生效版本', key: 'version', width: 112, render: (_, row) => <span>{row.effectiveVersionNo ? `v${row.effectiveVersionNo}` : '初始代码'}</span> },
    { title: '结构', key: 'structure', width: 110, render: (_, row) => <span className="task-structure-summary">{row.stepCount ?? '-'} Step · {(row.parameters || []).length} 参数</span> },
    { title: '任务描述', dataIndex: 'description', width: 170, ellipsis: true, render: (value) => value || <span className="muted-text">-</span> },
    { title: '任务类型', dataIndex: 'taskType', width: 110, render: (value) => value === 'RUN_HIVE' ? 'Run Hive' : value || '-' },
    { title: '执行频率', dataIndex: 'executionFrequency', width: 120, ellipsis: true },
    { title: '负责人', dataIndex: 'owner', width: 100, ellipsis: true },
    { title: '更新人', dataIndex: 'updatedBy', width: 90, ellipsis: true },
    { title: '更新时间', dataIndex: 'updatedAt', width: 140, render: (value) => formatter.format(new Date(value)) },
    {
      title: '操作', key: 'actions', width: actionColumnWidth, fixed: 'right', className: 'table-operation-column',
      onCell: () => ({ onClick: (event) => event.stopPropagation() }),
      render: (_, row) => (
        <div ref={actionRef(row.id)} className="task-row-actions">
          <Tooltip title={row.archived ? '归档任务不能开发' : '优先进入最新可编辑版本；没有开发中版本时查看当前生效代码'}><Button type="link" size="small" icon={<EditOutlined />} disabled={row.archived} onClick={() => void editTask(row)}>开发</Button></Tooltip>
          <Tooltip title={row.archived ? '归档任务不能调试' : !row.enabled ? '停用任务不能调试，请先启用' : undefined}>
            <Button
              type="link"
              size="small"
              icon={<BugOutlined />}
              loading={debuggingTaskId === row.id}
              disabled={row.archived || !row.enabled}
              className="task-row-compact-action"
              onClick={() => void debugTask(row)}
            >调试</Button>
          </Tooltip>
          <Button type="link" size="small" icon={<BranchesOutlined />} onClick={() => setVersionTask(row)}>版本</Button>
          <Tooltip title={row.archived ? '归档任务不能发起请求' : '交给 Agent'}><Button className="task-row-compact-action task-row-agent-action" type="link" size="small" icon={<RobotOutlined />} disabled={row.archived} aria-label="交给 Agent" onClick={() => navigate(`/chat?taskId=${row.id}`)}>Agent</Button></Tooltip>
          <Dropdown trigger={['click']} menu={{ items: [
            { key: 'copy', icon: <CopyOutlined />, label: '复制任务' },
            row.archived
              ? { key: 'restore', icon: <UndoOutlined />, label: '恢复任务' }
              : { key: 'archive', icon: <InboxOutlined />, label: '归档任务', danger: true },
          ], onClick: ({ key }) => { if (key === 'copy') void copy(row); else changeArchived(row, key === 'archive'); } }}>
            <Tooltip title="更多"><Button type="text" size="small" icon={<MoreOutlined />} /></Tooltip>
          </Dropdown>
        </div>
      ),
    },
  ];

  return (
    <div className={embedded ? 'data-page task-list-page embedded' : 'data-page task-list-page'}>
      {!embedded ? <header className="data-page-header">
        <Typography.Title level={2}>任务管理</Typography.Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => onCreateTask ? onCreateTask() : navigate('/tasks/new')}>新建任务</Button>
      </header> : null}
      <section className="data-panel">
        <div className="data-toolbar">
          <Space wrap>
            <Segmented className="ui-flat-segmented" value={status} options={[{ label: '活跃', value: 'active' }, { label: '已归档', value: 'archived' }, { label: '全部', value: 'all' }]} onChange={(value) => { setPage(1); setStatus(value as typeof status); }} />
            <Input value={keyword} allowClear prefix={<SearchOutlined />} placeholder="搜索任务 ID 或名称" onChange={(event) => setKeyword(event.target.value)} onPressEnter={() => { setPage(1); setCommittedKeyword(keyword.trim()); }} />
            <Button type="primary" onClick={() => { setPage(1); setCommittedKeyword(keyword.trim()); }}>查询</Button>
            <Tooltip title="刷新"><Button icon={<ReloadOutlined />} onClick={() => void load()} /></Tooltip>
          </Space>
          <span className="result-count">共 {data.total} 个任务</span>
        </div>
        <Table
          rowKey="id"
          size="small"
          columns={columns}
          dataSource={data.items}
          loading={loading}
          pagination={false}
          scroll={{ x: 1412 + actionColumnWidth }}
          locale={{ emptyText: <Empty description="暂无任务" /> }}
          rowClassName={(row) => row.id === executionTask?.id ? 'clickable-task-row selected-task-row' : 'clickable-task-row'}
          onRow={(row) => ({
            role: 'button',
            tabIndex: 0,
            onClick: () => openExecutions(row),
            onKeyDown: (event) => {
              if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault();
                openExecutions(row);
              }
            },
          })}
        />
        <div className="table-pagination"><Pagination current={page} pageSize={pageSize} total={data.total} showSizeChanger onChange={(next, size) => { setPage(next); setPageSize(size); }} /></div>
      </section>
      <Drawer
        rootClassName="task-execution-drawer-root"
        className="task-execution-drawer"
        placement="bottom"
        height={executionFullscreen ? 'calc(100vh - 12px)' : '70vh'}
        open={Boolean(executionTask)}
        onClose={() => {
          setExecutionTask(undefined);
          setExecutionFullscreen(false);
          onExecutionClose?.();
        }}
        destroyOnHidden
        title={executionTask ? (
          <div className="execution-drawer-title">
            <strong>{executionTask.name}</strong>
            <span>任务 {executionTask.id} · 执行实例</span>
          </div>
        ) : null}
        extra={executionTask ? (
          <Tooltip title={executionFullscreen ? '恢复抽屉高度' : '全屏'}>
            <Button
              type="text"
              icon={executionFullscreen ? <FullscreenExitOutlined /> : <FullscreenOutlined />}
              onClick={() => setExecutionFullscreen((value) => !value)}
            />
          </Tooltip>
        ) : null}
      >
        {executionTask ? (
          <TaskExecutionPanel
            taskId={executionTask.id}
            task={executionTask}
            initialExecutionId={initialExecutionId}
            onExecutionChange={(executionId) => onExecutionOpen?.(executionTask.id, executionId)}
          />
        ) : null}
      </Drawer>
      {versionTask ? (
        <TaskVersionDrawer
          open
          task={versionTask}
          onClose={() => setVersionTask(undefined)}
          onEditVersion={(versionNo) => {
            const selectedTaskId = versionTask.id;
            setVersionTask(undefined);
            navigate(`/tasks/${selectedTaskId}/edit?versionNo=${versionNo}`);
          }}
          onActivated={(activatedTask) => { setVersionTask(activatedTask); void load(); }}
        />
      ) : null}
    </div>
  );
}
