import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Alert, Button, Empty, Input, Modal, Pagination, Popconfirm, Space, Table, Tag, Typography, message,
} from 'antd';
import {
  ArrowLeftOutlined, CheckCircleOutlined, EditOutlined, ExperimentOutlined, PlusOutlined,
  ReloadOutlined, RocketOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { useNavigate } from 'react-router-dom';
import {
  createTaskVersionUnion, getTaskVersionUnion, listTaskVersionUnionCandidates,
  listTaskVersionUnions, publishTaskVersionUnion, updateTaskVersionUnion,
} from '../../api/taskVersionUnions';
import type {
  TaskVersionUnionCandidateVO, TaskVersionUnionMemberVO, TaskVersionUnionStatus, TaskVersionUnionVO,
} from '../../types';
import DataCompareStatusTag from '../dataCompare/DataCompareStatusTag';

const statusMeta: Record<TaskVersionUnionStatus, { text: string; color: string }> = {
  DRAFT: { text: '草稿', color: 'processing' },
  PUBLISHING: { text: '发布中', color: 'processing' },
  PUBLISHED: { text: '已发布', color: 'success' },
  PUBLISH_FAILED: { text: '发布失败', color: 'error' },
  STALE: { text: '已失效', color: 'warning' },
};

interface EditorMember {
  key: string;
  taskId: number;
  taskName: string;
  versionNo: number;
  versionRevision: number;
  ddl: string;
}

const memberKey = (taskId: number, versionNo: number) => `${taskId}:${versionNo}`;
const formatTime = (value?: string) => value ? value.replace('T', ' ').slice(0, 19) : '-';
const toEditorMember = (member: TaskVersionUnionMemberVO): EditorMember => ({
  key: memberKey(member.task_id, member.version_no),
  taskId: member.task_id,
  taskName: member.task_name,
  versionNo: member.version_no,
  versionRevision: member.current_version_revision || member.version_revision,
  ddl: member.ddl || '',
});

export default function TaskVersionUnionPage() {
  const navigate = useNavigate();
  const [items, setItems] = useState<TaskVersionUnionVO[]>([]);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [total, setTotal] = useState(0);
  const [keyword, setKeyword] = useState('');
  const [searchedKeyword, setSearchedKeyword] = useState('');
  const [loading, setLoading] = useState(false);
  const [editor, setEditor] = useState<TaskVersionUnionVO | null | undefined>(undefined);
  const [unionDdl, setUnionDdl] = useState('');
  const [editorMembers, setEditorMembers] = useState<EditorMember[]>([]);
  const [candidates, setCandidates] = useState<TaskVersionUnionCandidateVO[]>([]);
  const [candidatePage, setCandidatePage] = useState(1);
  const [candidatePageSize, setCandidatePageSize] = useState(10);
  const [candidateTotal, setCandidateTotal] = useState(0);
  const [candidateKeyword, setCandidateKeyword] = useState('');
  const [candidateSearched, setCandidateSearched] = useState('');
  const [candidateLoading, setCandidateLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const result = await listTaskVersionUnions(page, pageSize, searchedKeyword);
      setItems(result.items);
      setTotal(result.total);
    } catch (error) {
      message.error(`读取联合版本失败：${(error as Error).message}`);
    } finally { setLoading(false); }
  }, [page, pageSize, searchedKeyword]);

  const loadCandidates = useCallback(async () => {
    if (editor === undefined) return;
    setCandidateLoading(true);
    try {
      const result = await listTaskVersionUnionCandidates(
        candidatePage, candidatePageSize, candidateSearched,
      );
      setCandidates(result.items);
      setCandidateTotal(result.total);
    } catch (error) {
      message.error(`读取候选版本失败：${(error as Error).message}`);
    } finally { setCandidateLoading(false); }
  }, [candidatePage, candidatePageSize, candidateSearched, editor]);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => { void loadCandidates(); }, [loadCandidates]);

  const openCreate = () => {
    setEditor(null);
    setUnionDdl('');
    setEditorMembers([]);
    setCandidatePage(1);
    setCandidateKeyword('');
    setCandidateSearched('');
  };

  const openEdit = async (id: number) => {
    setLoading(true);
    try {
      const detail = await getTaskVersionUnion(id);
      setEditor(detail);
      setUnionDdl(detail.union_ddl || '');
      setEditorMembers(detail.members.map(toEditorMember));
      setCandidatePage(1);
      setCandidateKeyword('');
      setCandidateSearched('');
    } catch (error) {
      message.error(`读取联合版本详情失败：${(error as Error).message}`);
    } finally { setLoading(false); }
  };

  const addCandidate = (candidate: TaskVersionUnionCandidateVO, selected: boolean) => {
    const key = memberKey(candidate.task_id, candidate.version_no);
    if (!selected) {
      setEditorMembers((current) => current.filter((item) => item.key !== key));
      return;
    }
    if (editorMembers.some((item) => item.taskId === candidate.task_id && item.key !== key)) {
      message.warning(`任务 ${candidate.task_name} 已选择一个版本，同一任务只能加入一个版本`);
      return;
    }
    setEditorMembers((current) => current.some((item) => item.key === key) ? current : [...current, {
      key,
      taskId: candidate.task_id,
      taskName: candidate.task_name,
      versionNo: candidate.version_no,
      versionRevision: candidate.version_revision,
      ddl: candidate.ddl || '',
    }]);
  };

  const save = async () => {
    if (editorMembers.length < 2) {
      message.warning('联合版本至少选择两个不同任务的开发中版本');
      return;
    }
    setSaving(true);
    try {
      const body = {
        revision: editor?.revision,
        unionDdl,
        members: editorMembers.map((member) => ({
          taskId: member.taskId,
          versionNo: member.versionNo,
          versionRevision: member.versionRevision,
          ddl: member.ddl,
        })),
      };
      const saved = editor
        ? await updateTaskVersionUnion(editor.id, body)
        : await createTaskVersionUnion(body);
      setEditor(undefined);
      message.success(`联合版本 ${saved.id} 已保存`);
      await load();
    } catch (error) {
      message.error(`保存联合版本失败：${(error as Error).message}`);
    } finally { setSaving(false); }
  };

  const publish = async (record: TaskVersionUnionVO) => {
    setLoading(true);
    try {
      await publishTaskVersionUnion(record.id, record.revision);
      message.success(`联合版本 ${record.id} 已统一发布，成员任务已一次性生效`);
      await load();
    } catch (error) {
      message.error(`联合发布失败：${(error as Error).message}`);
    } finally { setLoading(false); }
  };

  const columns = useMemo<ColumnsType<TaskVersionUnionVO>>(() => [
    { title: '联合 ID', dataIndex: 'id', width: 100, fixed: 'left', render: (value) => <strong className="mono-id">{value}</strong> },
    { title: '状态', dataIndex: 'status', width: 110, fixed: 'left', render: (value: TaskVersionUnionStatus) => <Tag color={statusMeta[value].color}>{statusMeta[value].text}</Tag> },
    { title: '成员', dataIndex: 'member_count', width: 90, render: (value) => `${value} 个任务` },
    { title: '验数门禁', dataIndex: 'can_publish', width: 120, render: (value, record) => record.status === 'PUBLISHED'
      ? <Tag color="success" icon={<CheckCircleOutlined />}>发布时已满足</Tag>
      : value ? <Tag color="success" icon={<CheckCircleOutlined />}>已满足</Tag> : <Tag color="warning">未满足</Tag> },
    { title: '修订', dataIndex: 'revision', width: 90 },
    { title: '创建人', dataIndex: 'created_by', width: 130, ellipsis: true },
    { title: '更新人', dataIndex: 'updated_by', width: 130, ellipsis: true },
    { title: '更新时间', dataIndex: 'update_time', width: 180, render: formatTime },
    { title: '错误', dataIndex: 'error_message', minWidth: 220, ellipsis: true, render: (value) => value || '-' },
    {
      title: '操作', width: 240, fixed: 'right',
      render: (_, record) => <div className="table-row-actions">
        {['DRAFT', 'PUBLISH_FAILED', 'STALE'].includes(record.status) ? <Button type="link" size="small" icon={<EditOutlined />} onClick={() => void openEdit(record.id)}>编辑</Button> : null}
        <Popconfirm title="确认执行生产 Hive DDL，并统一生效全部成员版本？" description="只允许 ADD/CHANGE；Hive DDL 不支持事务回滚。" okText="联合发布" onConfirm={() => void publish(record)}><Button type="link" size="small" icon={<RocketOutlined />} disabled={!record.can_publish || !['DRAFT', 'PUBLISH_FAILED'].includes(record.status)}>发布</Button></Popconfirm>
      </div>,
    },
  ], []);

  const memberColumns: ColumnsType<TaskVersionUnionMemberVO> = [
    { title: '任务', dataIndex: 'task_name', width: 220, render: (value, row) => `${row.task_id} ${value}` },
    { title: '版本', dataIndex: 'version_no', width: 90, render: (value) => `v${value}` },
    { title: '版本状态', dataIndex: 'version_status', width: 110, render: (value) => <Tag>{value}</Tag> },
    { title: '最新验数', dataIndex: 'compare_status', width: 120, render: (value) => value ? <DataCompareStatusTag status={value} /> : <Tag>未验数</Tag> },
    { title: '报告', dataIndex: 'latest_compare_job_id', width: 100, render: (value) => value ? <Button type="link" size="small" onClick={() => navigate(`/data-compares/${value}`)}>#{value}</Button> : '-' },
    {
      title: '操作', width: 160,
      render: (_, row) => <Button type="link" size="small" icon={<ExperimentOutlined />} disabled={row.version_status !== 'DRAFT'} onClick={() => navigate(`/data-compares/new?taskId=${row.task_id}&candidateVersionNo=${row.version_no}`)}>单独设计验数</Button>,
    },
  ];

  const editorMemberColumns: ColumnsType<EditorMember> = [
    { title: '任务', dataIndex: 'taskName', width: 220, render: (value, row) => `${row.taskId} ${value}` },
    { title: '版本', dataIndex: 'versionNo', width: 80, render: (value) => `v${value}` },
    { title: '成员 DDL', dataIndex: 'ddl', render: (value, row) => <Input.TextArea autoSize={{ minRows: 2, maxRows: 6 }} value={value} placeholder="可选；仅支持 ALTER TABLE ADD/CHANGE" onChange={(event) => setEditorMembers((current) => current.map((item) => item.key === row.key ? { ...item, ddl: event.target.value } : item))} /> },
    { title: '操作', width: 80, render: (_, row) => <Button danger type="link" size="small" onClick={() => setEditorMembers((current) => current.filter((item) => item.key !== row.key))}>移除</Button> },
  ];

  return <div className="page-content data-page task-version-union-page">
    <header className="data-page-header">
      <div><Typography.Title level={2}>联合版本</Typography.Title><Typography.Text type="secondary">成员分别验数，门禁全部满足后统一执行 DDL 并生效</Typography.Text></div>
      <Space><Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/tasks')}>返回任务管理</Button><Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建联合版本</Button></Space>
    </header>
    <section className="data-panel compact-list-panel">
      <div className="list-toolbar"><Space.Compact><Input.Search allowClear value={keyword} placeholder="搜索联合 ID 或操作人" onChange={(event) => setKeyword(event.target.value)} onSearch={(value) => { setPage(1); setSearchedKeyword(value.trim()); }} enterButton="查询" style={{ width: 360 }} /></Space.Compact><Button icon={<ReloadOutlined />} loading={loading} onClick={() => void load()} /></div>
      <Table rowKey="id" size="small" loading={loading} columns={columns} dataSource={items} pagination={false} scroll={{ x: 1370 }} expandable={{ expandedRowRender: (record) => <Table rowKey="id" size="small" columns={memberColumns} dataSource={record.members} pagination={false} /> }} locale={{ emptyText: <Empty description="暂无联合版本" /> }} />
      <div className="list-pagination"><span>共 {total} 个联合版本</span><Pagination current={page} pageSize={pageSize} total={total} showSizeChanger onChange={(next, size) => { setPage(size !== pageSize ? 1 : next); setPageSize(size); }} /></div>
    </section>
    <Modal className="union-version-editor" width="min(1220px, 94vw)" open={editor !== undefined} title={editor ? `编辑联合版本 ${editor.id}` : '新建联合版本'} okText="事务保存" confirmLoading={saving} onOk={() => void save()} onCancel={() => setEditor(undefined)} destroyOnHidden>
      <Alert type="info" showIcon message="保存会校验全部成员版本修订号和 DDL 表冲突" description="联合 DDL 与成员 DDL 不允许修改同一张表。任何一个版本发生冲突，本次保存全部回滚。" />
      <label className="union-ddl-editor"><Typography.Text strong>公共联合 DDL</Typography.Text><Input.TextArea rows={4} value={unionDdl} placeholder="可选；仅支持 Hive ALTER TABLE ADD COLUMNS / CHANGE COLUMN" onChange={(event) => setUnionDdl(event.target.value)} /></label>
      <Typography.Text strong>已选成员（至少 2 个不同任务）</Typography.Text>
      <Table rowKey="key" size="small" columns={editorMemberColumns} dataSource={editorMembers} pagination={false} scroll={{ x: 900 }} locale={{ emptyText: <Empty description="请从候选列表选择成员" /> }} />
      <div className="candidate-list-head"><Typography.Text strong>可加入的开发中版本</Typography.Text><Space.Compact><Input.Search allowClear value={candidateKeyword} placeholder="搜索任务、版本" onChange={(event) => setCandidateKeyword(event.target.value)} onSearch={(value) => { setCandidatePage(1); setCandidateSearched(value.trim()); }} enterButton="查询" /></Space.Compact></div>
      <Table
        rowKey={(record) => memberKey(record.task_id, record.version_no)}
        size="small"
        loading={candidateLoading}
        columns={[
          { title: '任务', dataIndex: 'task_name', render: (value, row) => `${row.task_id} ${value}` },
          { title: '版本', dataIndex: 'version_no', width: 90, render: (value) => `v${value}` },
          { title: '版本说明', dataIndex: 'version_note', ellipsis: true, render: (value) => value || '-' },
          { title: '修订', dataIndex: 'version_revision', width: 80 },
        ]}
        dataSource={candidates}
        pagination={false}
        rowSelection={{
          preserveSelectedRowKeys: true,
          selectedRowKeys: editorMembers.map((member) => member.key),
          getCheckboxProps: (candidate) => ({ disabled: editorMembers.some((member) => member.taskId === candidate.task_id && member.versionNo !== candidate.version_no) }),
          onSelect: (candidate, selected) => addCandidate(candidate, selected),
        }}
        locale={{ emptyText: <Empty description="没有可加入的候选版本" /> }}
      />
      <div className="list-pagination"><span>共 {candidateTotal} 个候选版本</span><Pagination size="small" current={candidatePage} pageSize={candidatePageSize} total={candidateTotal} showSizeChanger onChange={(next, size) => { setCandidatePage(size !== candidatePageSize ? 1 : next); setCandidatePageSize(size); }} /></div>
    </Modal>
  </div>;
}
