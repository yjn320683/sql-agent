import { useCallback, useEffect, useMemo, useState } from 'react';
import { Button, Descriptions, Drawer, Form, Input, Modal, Popconfirm, Space, Table, Tag, Tooltip, Typography, message } from 'antd';
import { DeleteOutlined, EditOutlined, EyeOutlined, LinkOutlined, PlusOutlined, ProfileOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { createServer, deleteServer, listServers, listSyncTasks, testServer, testServerRequest, updateServer } from '../api';
import type { RealtimeServer, RealtimeServerSave, SyncTaskListItem } from '../types';
import type { AiProposal } from '../../types';
import { useAutoTableActionWidth } from '../../utils/useAutoTableActionWidth';

export default function RealtimeServersPage() {
  const { actionColumnWidth, actionRef } = useAutoTableActionWidth({ initialWidth: 420, minWidth: 360 });
  const [rows, setRows] = useState<RealtimeServer[]>([]);
  const [loading, setLoading] = useState(false);
  const [editing, setEditing] = useState<RealtimeServer>();
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [tested, setTested] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [viewing, setViewing] = useState<RealtimeServer>();
  const [taskServer, setTaskServer] = useState<RealtimeServer>();
  const [relatedTasks, setRelatedTasks] = useState<SyncTaskListItem[]>([]);
  const [relatedLoading, setRelatedLoading] = useState(false);
  const [form] = Form.useForm<RealtimeServerSave>();

  const load = useCallback(async () => {
    setLoading(true);
    try { setRows(await listServers()); } catch (error) { message.error((error as Error).message); }
    finally { setLoading(false); }
  }, []);
  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    if (!drawerOpen) return undefined;
    const applyAiProposal = (rawEvent: Event) => {
      if (rawEvent.defaultPrevented) return;
      const event = rawEvent as CustomEvent<AiProposal>; const proposal = event.detail;
      if (!proposal || proposal.target !== 'server-form'
        || !['FORM', 'CONFIG'].includes(proposal.kind.toUpperCase()) || !proposal.patch) return;
      const safePatch = proposal.patch as Partial<RealtimeServerSave>;
      form.setFieldsValue({
        name: safePatch.name,
        databaseName: safePatch.databaseName,
        databasePrefix: safePatch.databasePrefix,
        description: safePatch.description,
      });
      setTested(false);
      event.preventDefault();
    };
    const publishAiContext = () => window.dispatchEvent(new CustomEvent('sql-agent:ai-context-update', {
      detail: {
        contextType: 'REALTIME_SERVER',
        entityId: editing ? String(editing.id) : undefined,
        title: editing ? `编辑 Server · ${editing.name}` : '新建 Server',
        revision: editing?.updateTime ?? '0',
        draft: { name: form.getFieldValue('name'), databaseName: form.getFieldValue('databaseName'), databasePrefix: form.getFieldValue('databasePrefix'), description: form.getFieldValue('description') },
      },
    }));
    window.addEventListener('sql-agent:apply-ai-proposal', applyAiProposal);
    window.addEventListener('sql-agent:ai-context-request', publishAiContext);
    publishAiContext();
    return () => {
      window.removeEventListener('sql-agent:apply-ai-proposal', applyAiProposal);
      window.removeEventListener('sql-agent:ai-context-request', publishAiContext);
    };
  }, [drawerOpen, editing, form]);

  const open = (server?: RealtimeServer) => {
    setEditing(server); setDrawerOpen(true);
    setTested(Boolean(server));
    form.setFieldsValue(server ? { name: server.name, address: server.address, databaseName: server.databaseName,
      databasePrefix: server.databasePrefix, account: server.account, description: server.description, password: '' }
      : { name: '', address: '', databaseName: '', databasePrefix: '', account: '', password: '', description: '' });
  };
  const save = async () => {
    setSaving(true);
    try {
      const value = await form.validateFields();
      if (editing) await updateServer(editing.id, value); else await createServer(value);
      message.success(editing ? 'Server 已更新' : 'Server 已创建'); setDrawerOpen(false); await load();
    } catch (error) { message.error((error as Error).message); }
    finally { setSaving(false); }
  };
  const testCurrent = async () => {
    setTesting(true);
    try {
      const value = await form.validateFields();
      const result = editing && !value.password ? await testServer(editing.id) : await testServerRequest(value);
      setTested(true);
      Modal.success({ title: '连接成功', content: <Descriptions size="small" column={1} items={Object.entries(result).map(([key, item]) => ({ key, label: key, children: String(item) }))} /> });
    } catch (error) { message.error((error as Error).message); }
    finally { setTesting(false); }
  };

  const openTasks = async (server: RealtimeServer) => {
    setTaskServer(server); setRelatedLoading(true); setRelatedTasks([]);
    try {
      const query = new URLSearchParams({ page: '1', pageSize: '100', sort: 'lastOperationTime', order: 'desc', sourceServerId: String(server.id) });
      setRelatedTasks((await listSyncTasks(query)).items);
    } catch (error) { message.error((error as Error).message); }
    finally { setRelatedLoading(false); }
  };

  const filteredRows = useMemo(() => {
    const value = keyword.trim().toLowerCase();
    if (!value) return rows;
    return rows.filter((row) => [row.id, row.name, row.address, row.databaseName, row.databasePrefix, row.account, row.operator]
      .some((item) => String(item ?? '').toLowerCase().includes(value)));
  }, [keyword, rows]);

  return (
    <div className="page-content realtime-page realtime-sync-tasks-page realtime-servers-page">
      <section className="realtime-sync-main-panel">
        <div className="realtime-sync-filter-section">
          <div className="realtime-server-toolbar">
            <Input allowClear prefix={<SearchOutlined />} placeholder="服务 ID、名称、地址、数据库或操作人" value={keyword} onChange={(event) => setKeyword(event.target.value)} />
            <Space size={8}>
              <Tooltip title="刷新"><Button aria-label="刷新" icon={<ReloadOutlined />} onClick={() => void load()} /></Tooltip>
              <Tooltip title="新建 Server"><Button aria-label="新建 Server" type="primary" icon={<PlusOutlined />} onClick={() => open()} /></Tooltip>
            </Space>
          </div>
        </div>
        <div className="realtime-sync-table-section">
          <Table rowKey="id" loading={loading} dataSource={filteredRows} columns={[
          { title: '服务 ID', dataIndex: 'id', width: 100, fixed: 'left' as const },
          { title: '名称', dataIndex: 'name', width: 180, fixed: 'left' as const },
          { title: '类型', dataIndex: 'type', width: 90, render: () => <Tag color="blue">MySQL</Tag> },
          { title: '地址', dataIndex: 'address', width: 240, ellipsis: true },
          { title: '数据库', dataIndex: 'databaseName', width: 160 },
          { title: '库前缀', dataIndex: 'databasePrefix', width: 100 },
          { title: '账号', dataIndex: 'account', width: 140 },
          { title: '密码', dataIndex: 'passwordConfigured', width: 90, render: (value: boolean) => value ? <Tag color="green">已配置</Tag> : <Tag>未配置</Tag> },
          { title: '操作人', dataIndex: 'operator', width: 110 },
          { title: '更新时间', dataIndex: 'updateTime', width: 175 },
          { title: '操作', width: actionColumnWidth, fixed: 'right' as const, className: 'table-operation-column', render: (_: unknown, row: RealtimeServer) => <div ref={actionRef(row.id)} className="table-row-actions"><Space size={10}>
            <Button type="link" icon={<ProfileOutlined />} onClick={() => void openTasks(row)}>任务列表</Button>
            <Button type="link" icon={<EyeOutlined />} onClick={() => setViewing(row)}>查看</Button>
            <Button type="link" icon={<LinkOutlined />} onClick={async () => { try { const result = await testServer(row.id); message.success(`连接成功，耗时 ${result.latencyMs ?? '-'} ms`); } catch (error) { message.error((error as Error).message); } }}>测试</Button>
            <Button type="link" icon={<EditOutlined />} onClick={() => open(row)}>编辑</Button>
            <Popconfirm title="确认删除该 Server？" onConfirm={async () => { try { await deleteServer(row.id); await load(); } catch (error) { message.error((error as Error).message); } }}><Button type="link" danger icon={<DeleteOutlined />}>删除</Button></Popconfirm>
          </Space></div> },
          ]} scroll={{ x: 1385 + actionColumnWidth }} pagination={{ defaultPageSize: 20, showSizeChanger: true, pageSizeOptions: [20, 50, 100], showTotal: (total) => `共 ${total} 条` }} />
        </div>
      </section>
      <Drawer title={editing ? `编辑 Server · ${editing.name}` : '新建 MySQL Server'} open={drawerOpen} onClose={() => setDrawerOpen(false)} width={620} extra={<Space><Button icon={<LinkOutlined />} loading={testing} onClick={() => void testCurrent()}>测试连接</Button><Button type="primary" disabled={!editing && !tested} loading={saving} onClick={() => void save()}>保存</Button></Space>}>
        <Form form={form} layout="vertical" onValuesChange={() => { if (!editing) setTested(false); }}>
          {editing && <Typography.Paragraph type="secondary">已使用的连接信息保持只读，避免影响现有同步任务；可修改描述。</Typography.Paragraph>}
          <Form.Item name="name" label="Server 名称" rules={[{ required: true }]}><Input disabled={Boolean(editing)} /></Form.Item>
          <Form.Item name="address" label="MySQL 地址" rules={[{ required: true }]}><Input disabled={Boolean(editing)} placeholder="host:3306 或 jdbc:mysql://host:3306/database" /></Form.Item>
          <Form.Item name="databaseName" label="默认数据库" rules={[{ required: true, message: '请输入数据库' }]}><Input disabled={Boolean(editing)} /></Form.Item>
          <Form.Item name="databasePrefix" label="库前缀" tooltip="选填；填写时只能是小写字母，长度小于10" dependencies={['databaseName']} rules={[({ getFieldValue }) => ({ validator: (_, value) => {
            const prefix = String(value ?? '').trim();
            if (prefix && !/^[a-z]{1,9}$/.test(prefix)) return Promise.reject(new Error('只能是小写字母，长度小于10'));
            const database = String(getFieldValue('databaseName') ?? '').trim().toLowerCase();
            const sameDatabase = rows.filter((server) => server.id !== editing?.id && String(server.databaseName ?? '').trim().toLowerCase() === database);
            if (sameDatabase.length && !prefix) return Promise.reject(new Error('该数据库已存在，请填写库前缀'));
            if (sameDatabase.some((server) => String(server.databasePrefix ?? '').trim() === prefix)) return Promise.reject(new Error('库前缀与数据库组合已存在，请更换库前缀'));
            return Promise.resolve();
          } })]}><Input disabled={Boolean(editing)} placeholder="选填，例如 jd" /></Form.Item>
          <Form.Item name="account" label="账号"><Input disabled={Boolean(editing)} autoComplete="off" /></Form.Item>
          {!editing && <Form.Item name="password" label="密码"><Input.Password autoComplete="new-password" /></Form.Item>}
          <Form.Item name="description" label="描述"><Input.TextArea rows={3} /></Form.Item>
        </Form>
      </Drawer>
      <Modal title="查看 Server" open={Boolean(viewing)} footer={null} onCancel={() => setViewing(undefined)}>
        {viewing && <Descriptions bordered size="small" column={1} items={[
          { key: 'id', label: '服务 ID', children: viewing.id }, { key: 'name', label: '名称', children: viewing.name },
          { key: 'type', label: '类型', children: 'MySQL' }, { key: 'address', label: '地址', children: viewing.address },
          { key: 'database', label: '数据库', children: viewing.databaseName || '-' }, { key: 'prefix', label: '库前缀', children: viewing.databasePrefix || '-' },
          { key: 'account', label: '账号', children: viewing.account || '-' }, { key: 'password', label: '密码', children: viewing.passwordConfigured ? '已配置' : '未配置' },
          { key: 'description', label: '描述', children: viewing.description || '-' }, { key: 'operator', label: '操作人', children: viewing.operator || '-' },
          { key: 'created', label: '创建时间', children: viewing.createTime || '-' }, { key: 'updated', label: '更新时间', children: viewing.updateTime || '-' },
        ]} />}
      </Modal>
      <Modal title={`关联同步任务 · ${taskServer?.name ?? ''}`} open={Boolean(taskServer)} footer={null} width={820} onCancel={() => setTaskServer(undefined)}>
        <Table rowKey="id" size="small" loading={relatedLoading} dataSource={relatedTasks} pagination={false} locale={{ emptyText: '暂无关联同步任务' }} columns={[
          { title: '任务 ID', dataIndex: 'id', width: 100 }, { title: '任务名称', dataIndex: 'name' },
          { title: '状态', dataIndex: 'status', width: 120 }, { title: '负责人', dataIndex: 'owner', width: 120 },
          { title: '更新时间', dataIndex: 'updateTime', width: 180 },
        ]} />
      </Modal>
    </div>
  );
}
