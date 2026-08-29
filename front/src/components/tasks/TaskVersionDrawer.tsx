import { useCallback, useEffect, useMemo, useState } from 'react';
import { Alert, Button, Drawer, Empty, Form, Input, Modal, Pagination, Skeleton, Space, Table, Tag, Tooltip, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  ArrowLeftOutlined, BranchesOutlined, CheckCircleOutlined, EditOutlined, ExperimentOutlined,
  FileTextOutlined, PlayCircleOutlined, PlusOutlined, ReloadOutlined, SearchOutlined, SwapOutlined,
} from '@ant-design/icons';
import { diffLines } from 'diff';
import { useNavigate } from 'react-router-dom';
import { activateTaskVersion, createTaskVersion, getTask, getTaskVersion, listTaskVersions } from '../../api/tasks';
import type { SqlTaskVO, SqlTaskVersionStatus, SqlTaskVersionVO } from '../../types';
import { useAutoTableActionWidth } from '../../utils/useAutoTableActionWidth';
import TaskExecutionModal from './TaskExecutionModal';

interface Props {
  open: boolean;
  task: SqlTaskVO;
  editingVersionNo?: number;
  hasUnsavedChanges?: boolean;
  createOnOpen?: boolean;
  onClose: () => void;
  onEditVersion: (versionNo: number) => void;
  onActivated?: (task: SqlTaskVO) => void;
}

type CompareTarget = { label: string; name: string; description?: string; sql: string; ddl: string };
const DEFAULT_PAGE_SIZE = 20;
const statusLabels: Record<SqlTaskVersionStatus, { text: string; color: string }> = {
  DRAFT: { text: '开发中', color: 'processing' },
  EFFECTIVE: { text: '当前生效', color: 'success' },
  HISTORICAL: { text: '历史版本', color: 'default' },
  STALE: { text: '基线过期', color: 'warning' },
};

function formatTime(value?: string): string {
  if (!value) return '-';
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
  }).format(new Date(value));
}

function toTarget(version: SqlTaskVersionVO): CompareTarget {
  return { label: `版本 v${version.versionNo}`, name: version.name, description: version.description, sql: version.sql || '', ddl: version.ddl || '' };
}

export default function TaskVersionDrawer({ open, task, editingVersionNo, hasUnsavedChanges = false, createOnOpen = false, onClose, onEditVersion, onActivated }: Props) {
  const navigate = useNavigate();
  const { actionColumnWidth, actionRef } = useAutoTableActionWidth({ initialWidth: 372 });
  const [form] = Form.useForm<{ note: string }>();
  const [items, setItems] = useState<SqlTaskVersionVO[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [keyword, setKeyword] = useState('');
  const [committedKeyword, setCommittedKeyword] = useState('');
  const [loading, setLoading] = useState(false);
  const [creating, setCreating] = useState(false);
  const [activatingVersionNo, setActivatingVersionNo] = useState<number>();
  const [executionVersion, setExecutionVersion] = useState<SqlTaskVersionVO>();
  const [createOpen, setCreateOpen] = useState(false);
  const [activeView, setActiveView] = useState<'versions' | 'diff'>('versions');
  const [selectedVersionNos, setSelectedVersionNos] = useState<number[]>([]);
  const [leftTarget, setLeftTarget] = useState<CompareTarget>();
  const [rightTarget, setRightTarget] = useState<CompareTarget>();
  const [diffLoading, setDiffLoading] = useState(false);
  const [error, setError] = useState('');
  const [highlightedVersionNo, setHighlightedVersionNo] = useState<number>();

  const loadList = useCallback(async (
    targetPage = page,
    targetPageSize = pageSize,
    targetKeyword = committedKeyword,
  ) => {
    setLoading(true);
    try {
      const result = await listTaskVersions(task.id, targetPage, targetPageSize, targetKeyword);
      setItems(result.items);
      setTotal(result.total);
      setError('');
    } catch (loadError) {
      setItems([]);
      setTotal(0);
      setError((loadError as Error).message);
    } finally {
      setLoading(false);
    }
  }, [committedKeyword, page, pageSize, task.id]);

  useEffect(() => {
    if (!open) return;
    setPage(1);
    setKeyword('');
    setCommittedKeyword('');
    setSelectedVersionNos([]);
    setHighlightedVersionNo(undefined);
    setActiveView('versions');
    setLeftTarget(undefined);
    setRightTarget(undefined);
    setCreateOpen(createOnOpen);
    void loadList(1, pageSize, '');
  }, [createOnOpen, open, task.id]); // eslint-disable-line react-hooks/exhaustive-deps

  const submitSearch = () => {
    const nextKeyword = keyword.trim();
    setCommittedKeyword(nextKeyword);
    setPage(1);
    setSelectedVersionNos([]);
    void loadList(1, pageSize, nextKeyword);
  };

  const sqlChanges = useMemo(
    () => diffLines(leftTarget?.sql || '', rightTarget?.sql || ''),
    [leftTarget?.sql, rightTarget?.sql],
  );
  const ddlChanges = useMemo(
    () => diffLines(leftTarget?.ddl || '', rightTarget?.ddl || ''),
    [leftTarget?.ddl, rightTarget?.ddl],
  );
  const compareVersions = async () => {
    if (selectedVersionNos.length !== 2) {
      message.warning('请选择两个版本进行对比');
      return;
    }
    setDiffLoading(true);
    try {
      const sorted = [...selectedVersionNos].sort((a, b) => a - b);
      const [left, right] = await Promise.all(sorted.map((versionNo) => getTaskVersion(task.id, versionNo)));
      setLeftTarget(toTarget(left));
      setRightTarget(toTarget(right));
      setActiveView('diff');
    } catch (loadError) {
      message.error(`读取版本失败：${(loadError as Error).message}`);
    } finally {
      setDiffLoading(false);
    }
  };

  const compareWithEffective = async (version: SqlTaskVersionVO) => {
    setDiffLoading(true);
    try {
      const detail = version.sql === undefined ? await getTaskVersion(task.id, version.versionNo) : version;
      setLeftTarget(toTarget(detail));
      setRightTarget({
        label: task.effectiveVersionNo ? `当前生效代码 v${task.effectiveVersionNo}` : '当前生效代码',
        name: task.name,
        description: task.description,
        sql: task.sql,
        ddl: task.ddl || '',
      });
      setActiveView('diff');
    } catch (loadError) {
      message.error(`读取版本失败：${(loadError as Error).message}`);
    } finally {
      setDiffLoading(false);
    }
  };

  const createVersion = async () => {
    try {
      const { note } = await form.validateFields();
      setCreating(true);
      const version = await createTaskVersion(task.id, note.trim(), task.revision);
      message.success(`版本 v${version.versionNo} 已创建`);
      setCreateOpen(false);
      form.resetFields();
      onEditVersion(version.versionNo);
    } catch (createError) {
      if ((createError as { errorFields?: unknown }).errorFields) return;
      message.error(`创建版本失败：${(createError as Error).message}`);
    } finally {
      setCreating(false);
    }
  };

  const activateVersion = (version: SqlTaskVersionVO) => {
    Modal.confirm({
      title: `生效版本 v${version.versionNo}？`,
      content: '生效后，该版本代码将成为任务的当前生效代码。其他基于旧生效代码且仍在开发的版本可能会变为基线过期。',
      okText: '确认生效',
      cancelText: '取消',
      onOk: async () => {
        setActivatingVersionNo(version.versionNo);
        try {
          await activateTaskVersion(task.id, version.versionNo, task.revision, version.revision);
          const activatedTask = await getTask(task.id);
          message.success(`版本 v${version.versionNo} 已生效`);
          onActivated?.(activatedTask);
          await loadList(page, pageSize, committedKeyword);
        } catch (activateError) {
          message.error(`版本生效失败：${(activateError as Error).message}`);
        } finally {
          setActivatingVersionNo(undefined);
        }
      },
    });
  };

  const openExecution = async (version: SqlTaskVersionVO) => {
    try {
      setExecutionVersion(version.sql === undefined ? await getTaskVersion(task.id, version.versionNo) : version);
    } catch (loadError) {
      message.error(`读取版本失败：${(loadError as Error).message}`);
    }
  };

  const columns: ColumnsType<SqlTaskVersionVO> = [
    {
      title: '版本', dataIndex: 'versionNo', width: 90, fixed: 'left',
      render: (value: number, record) => <Space size={6}><strong className="version-number">v{value}</strong>{record.versionNo === task.effectiveVersionNo ? <CheckCircleOutlined className="effective-version-icon" /> : null}</Space>,
    },
    { title: '状态', dataIndex: 'status', width: 104, fixed: 'left', render: (value: SqlTaskVersionStatus) => <Tag color={statusLabels[value].color}>{statusLabels[value].text}</Tag> },
    { title: '版本说明', dataIndex: 'versionNote', width: 250, ellipsis: true, render: (value?: string) => value || <span className="muted-text">未填写</span> },
    {
      title: '基于生效版本', key: 'baseline', width: 130,
      render: (_, record) => record.baseEffectiveVersionNo ? `v${record.baseEffectiveVersionNo}` : '初始代码',
    },
    { title: '操作人', dataIndex: 'updatedBy', width: 120, ellipsis: true },
    { title: '创建时间', dataIndex: 'createdAt', width: 168, render: formatTime },
    { title: '更新时间', dataIndex: 'updatedAt', width: 168, render: formatTime },
    {
      title: '操作', key: 'actions', width: actionColumnWidth, fixed: 'right', className: 'table-operation-column',
      render: (_, record) => (
        <div ref={actionRef(record.versionNo)} className="table-row-actions version-row-actions">
          {record.status === 'DRAFT' && record.canEdit && !task.archived ? (
            <Tooltip title="编辑版本"><Button className="responsive-action" type="link" size="small" icon={<EditOutlined />} onClick={() => onEditVersion(record.versionNo)}>编辑</Button></Tooltip>
          ) : null}
          {record.status !== 'EFFECTIVE' ? <Tooltip title="与生效代码对比"><Button className="responsive-action" type="link" size="small" icon={<SwapOutlined />} onClick={() => void compareWithEffective(record)}>与生效代码对比</Button></Tooltip> : null}
          <Tooltip title="执行版本"><Button className="responsive-action" type="link" size="small" icon={<PlayCircleOutlined />} onClick={() => void openExecution(record)}>执行</Button></Tooltip>
          {record.status === 'DRAFT' && record.canEdit ? <Tooltip title="发起验数"><Button className="responsive-action" type="link" size="small" icon={<ExperimentOutlined />} onClick={() => { onClose(); navigate(`/data-compares/new?taskId=${task.id}&candidateVersionNo=${record.versionNo}`); }}>验数</Button></Tooltip> : null}
          {record.status === 'DRAFT' && record.canEdit && !task.archived ? (
            <Tooltip title={record.inUnion ? '已加入联合版本，只能从联合版本页统一发布生效' : record.versionNo === editingVersionNo && hasUnsavedChanges ? '请先保存当前版本，再执行生效' : undefined}>
              <Button className="responsive-action" type="link" size="small" icon={<CheckCircleOutlined />} disabled={record.canActivate === false || record.versionNo === editingVersionNo && hasUnsavedChanges} loading={activatingVersionNo === record.versionNo} onClick={() => activateVersion(record)}>{record.inUnion ? '联合生效' : '生效'}</Button>
            </Tooltip>
          ) : null}
        </div>
      ),
    },
  ];

  const metaChanged = leftTarget && rightTarget
    && (leftTarget.name !== rightTarget.name || (leftTarget.description || '') !== (rightTarget.description || ''));

  return <>
    <Drawer
      rootClassName="task-version-drawer-root"
      className="task-version-drawer"
      placement="bottom"
      height="calc(100vh - 24px)"
      open={open}
      onClose={onClose}
      title={<div className="version-drawer-title"><BranchesOutlined /><strong>任务版本列表</strong><span>{task.name}</span></div>}
      extra={<Space><Button icon={<BranchesOutlined />} onClick={() => { onClose(); navigate('/tasks/unions'); }}>联合版本</Button><Button icon={<EditOutlined />} onClick={() => {
        const editable = items.find((item) => item.status === 'DRAFT' && item.canEdit);
        if (editable) onEditVersion(editable.versionNo);
        else setCreateOpen(true);
      }}>进入版本开发</Button><Tooltip title={task.archived ? '归档任务不能创建版本' : undefined}><Button type="primary" icon={<PlusOutlined />} disabled={task.archived} onClick={() => setCreateOpen(true)}>新建版本</Button></Tooltip><Tooltip title="刷新版本列表"><Button icon={<ReloadOutlined />} loading={loading} onClick={() => void loadList()} /></Tooltip></Space>}
    >
      {error ? <Alert type="error" showIcon message="读取真实版本记录失败" description={error} /> : null}
      {activeView === 'versions' ? <div className="version-table-pane">
        <div className="version-list-nav">
          <Space>
            <Input
              allowClear
              value={keyword}
              prefix={<SearchOutlined />}
              placeholder="搜索版本号、版本说明或操作人"
              onChange={(event) => setKeyword(event.target.value)}
              onPressEnter={submitSearch}
            />
            <Button type="primary" onClick={submitSearch}>查询</Button>
          </Space>
          <Space>
            {task.effectiveVersionNo ? null : <span>当前运行：初始代码</span>}
            <Button type="link" icon={<SwapOutlined />} disabled={selectedVersionNos.length !== 2} loading={diffLoading} onClick={() => void compareVersions()}>对比版本 ({selectedVersionNos.length}/2)</Button>
          </Space>
        </div>
        <Table
          rowKey="versionNo"
          size="small"
          loading={loading}
          columns={columns}
          dataSource={items}
          pagination={false}
          scroll={{ x: 1080 + actionColumnWidth, y: 'calc(100vh - 220px)' }}
          rowClassName={(record) => record.versionNo === highlightedVersionNo ? 'highlighted-version-row' : ''}
          rowSelection={{ fixed: true, selectedRowKeys: selectedVersionNos, preserveSelectedRowKeys: true, onChange: (keys) => setSelectedVersionNos(keys.slice(-2).map(Number)) }}
          locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={committedKeyword ? '没有匹配的版本' : '暂无版本，可从当前生效代码新建'} /> }}
        />
        <div className="version-table-pagination">
          <span>共 {total} 个版本</span>
          <Pagination
            current={page}
            pageSize={pageSize}
            total={total}
            showSizeChanger
            showQuickJumper={total > pageSize}
            pageSizeOptions={[10, 20, 50, 100]}
            onChange={(next, nextPageSize) => {
              const targetPage = nextPageSize === pageSize ? next : 1;
              setPage(targetPage);
              setPageSize(nextPageSize);
              void loadList(targetPage, nextPageSize, committedKeyword);
            }}
          />
        </div>
      </div> : <div className="version-diff-workspace">
        <div className="version-diff-toolbar"><Button type="text" icon={<ArrowLeftOutlined />} onClick={() => setActiveView('versions')}>返回版本列表</Button><strong>版本差异</strong></div>
        {diffLoading ? <Skeleton active paragraph={{ rows: 10 }} /> : leftTarget && rightTarget ? <div className="version-diff-pane">
          <header className="version-diff-header"><div><FileTextOutlined /><strong>{leftTarget.label}</strong></div><SwapOutlined /><div><FileTextOutlined /><strong>{rightTarget.label}</strong></div></header>
          {metaChanged ? <div className="task-version-meta-diff"><div><span>任务名称</span><del>{leftTarget.name}</del><ins>{rightTarget.name}</ins></div><div><span>任务描述</span><del>{leftTarget.description || '-'}</del><ins>{rightTarget.description || '-'}</ins></div></div> : null}
          <div className="task-version-sql-head"><span>SQL 逐行差异</span><small>红色为左侧删除，绿色为右侧新增</small></div>
          <div className="task-version-diff" role="region" aria-label="SQL 版本差异">{sqlChanges.map((part, partIndex) => part.value.split('\n').map((line, lineIndex, lines) => {
            if (lineIndex === lines.length - 1 && line === '') return null;
            const kind = part.added ? 'added' : part.removed ? 'removed' : 'unchanged';
            return <pre key={`${partIndex}-${lineIndex}`} className={kind}><span>{part.added ? '+' : part.removed ? '-' : ' '}</span>{line || ' '}</pre>;
          }))}</div>
          <div className="task-version-sql-head"><span>DDL 逐行差异</span><small>DDL 仅随版本保存，不会自动执行</small></div>
          <div className="task-version-diff" role="region" aria-label="DDL 版本差异">{ddlChanges.map((part, partIndex) => part.value.split('\n').map((line, lineIndex, lines) => {
            if (lineIndex === lines.length - 1 && line === '') return null;
            const kind = part.added ? 'added' : part.removed ? 'removed' : 'unchanged';
            return <pre key={`ddl-${partIndex}-${lineIndex}`} className={kind}><span>{part.added ? '+' : part.removed ? '-' : ' '}</span>{line || ' '}</pre>;
          }))}</div>
        </div> : <Empty description="请返回版本列表重新选择对比对象" />}
      </div>}
    </Drawer>

    <Modal
      className="create-version-modal"
      open={createOpen}
      title={<Space><PlusOutlined /><span>新建版本</span></Space>}
      okText="创建并编辑"
      cancelText="取消"
      confirmLoading={creating}
      destroyOnHidden
      onCancel={() => { setCreateOpen(false); form.resetFields(); }}
      onOk={() => void createVersion()}
    >
      <Form form={form} layout="vertical" requiredMark={false}><Form.Item name="note" label="版本说明" rules={[{ required: true, whitespace: true, message: '请填写本次版本的开发目标' }, { max: 512 }]}><Input.TextArea autoFocus rows={4} maxLength={512} showCount placeholder="例如：优化订单明细表关联顺序，减少大表 Shuffle" /></Form.Item></Form>
    </Modal>

    {executionVersion ? <TaskExecutionModal
      open
      taskId={task.id}
      taskName={executionVersion.name}
      sql={executionVersion.sql || ''}
      parameters={executionVersion.parameters}
      versionNo={executionVersion.versionNo}
      onClose={() => setExecutionVersion(undefined)}
      onSubmitted={() => setExecutionVersion(undefined)}
    /> : null}
  </>;
}
