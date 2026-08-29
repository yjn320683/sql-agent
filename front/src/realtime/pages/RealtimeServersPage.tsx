import { useCallback, useEffect, useMemo, useState } from 'react';
import { Button, Descriptions, Drawer, Form, Input, Modal, Popconfirm, Space, Table, Tag, Typography, message } from 'antd';
import { DeleteOutlined, EditOutlined, EyeOutlined, LinkOutlined, PlusOutlined, ProfileOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { createServer, deleteServer, listServers, listSyncTasks, testServer, testServerRequest, updateServer } from '../api';
import type { RealtimeServer, RealtimeServerSave, SyncTaskListItem } from '../types';

export default function RealtimeServersPage() {
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

  const open = (server?: RealtimeServer) => {
    setEditing(server); setDrawerOpen(true);
    setTested(Boolean(server));
    form.setFieldsValue(server ? { name: server.name, address: server.address, databaseName: server.databaseName,
      databaseAbbr: server.databaseAbbr, account: server.account, description: server.description, password: '' }
      : { name: '', address: '', databaseName: '', databaseAbbr: '', account: '', password: '', description: '' });
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
    return rows.filter((row) => [row.id, row.name, row.address, row.databaseName, row.databaseAbbr, row.account, row.operator]
      .some((item) => String(item ?? '').toLowerCase().includes(value)));
  }, [keyword, rows]);

  return (
    <div className="realtime-page realtime-sync-tasks-page realtime-servers-page">
      <section className="realtime-sync-main-panel">
        <div className="realtime-page-header">
          <Typography.Title level={4}>MySQL Server 管理</Typography.Title>
          <Space><Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button><Button type="primary" icon={<PlusOutlined />} onClick={() => open()}>新建 Server</Button></Space>
        </div>
        <div className="realtime-sync-filter-section">
          <div className="realtime-server-toolbar"><Input allowClear prefix={<SearchOutlined />} placeholder="服务 ID、名称、地址、数据库或操作人" value={keyword} onChange={(event) => setKeyword(event.target.value)} /></div>
        </div>
        <div className="realtime-sync-table-section">
          <Table rowKey="id" loading={loading} dataSource={filteredRows} columns={[
          { title: '服务 ID', dataIndex: 'id', width: 100, fixed: 'left' as const },
          { title: '名称', dataIndex: 'name', width: 180, fixed: 'left' as const },
          { title: '类型', dataIndex: 'type', width: 90, render: () => <Tag color="blue">MySQL</Tag> },
          { title: '地址', dataIndex: 'address', ellipsis: true },
          { title: '数据库', dataIndex: 'databaseName', width: 160 },
          { title: '库缩写', dataIndex: 'databaseAbbr', width: 100 },
          { title: '账号', dataIndex: 'account', width: 140 },
          { title: '密码', dataIndex: 'passwordConfigured', width: 90, render: (value: boolean) => value ? <Tag color="green">已配置</Tag> : <Tag>未配置</Tag> },
          { title: '操作人', dataIndex: 'operator', width: 110 },
          { title: '更新时间', dataIndex: 'updateTime', width: 175 },
          { title: '操作', width: 340, fixed: 'right' as const, render: (_: unknown, row: RealtimeServer) => <Space size={10}>
            <Button type="link" icon={<ProfileOutlined />} onClick={() => void openTasks(row)}>任务列表</Button>
            <Button type="link" icon={<EyeOutlined />} onClick={() => setViewing(row)}>查看</Button>
            <Button type="link" icon={<LinkOutlined />} onClick={async () => { try { const result = await testServer(row.id); message.success(`连接成功，耗时 ${result.latencyMs ?? '-'} ms`); } catch (error) { message.error((error as Error).message); } }}>测试</Button>
            <Button type="link" icon={<EditOutlined />} onClick={() => open(row)}>编辑</Button>
            <Popconfirm title="确认删除该 Server？" onConfirm={async () => { try { await deleteServer(row.id); await load(); } catch (error) { message.error((error as Error).message); } }}><Button type="link" danger icon={<DeleteOutlined />}>删除</Button></Popconfirm>
          </Space> },
          ]} scroll={{ x: 1540 }} pagination={{ defaultPageSize: 20, showSizeChanger: true, pageSizeOptions: [20, 50, 100], showTotal: (total) => `共 ${total} 条` }} />
        </div>
      </section>
      <Drawer title={editing ? `编辑 Server · ${editing.name}` : '新建 MySQL Server'} open={drawerOpen} onClose={() => setDrawerOpen(false)} width={620} extra={<Space><Button icon={<LinkOutlined />} loading={testing} onClick={() => void testCurrent()}>测试连接</Button><Button type="primary" disabled={!editing && !tested} loading={saving} onClick={() => void save()}>保存</Button></Space>}>
        <Form form={form} layout="vertical" onValuesChange={() => { if (!editing) setTested(false); }}>
          {editing && <Typography.Paragraph type="secondary">已使用的连接信息保持只读，避免影响现有同步任务；可修改描述。</Typography.Paragraph>}
          <Form.Item name="name" label="Server 名称" rules={[{ required: true }]}><Input disabled={Boolean(editing)} /></Form.Item>
          <Form.Item name="address" label="MySQL 地址" rules={[{ required: true }]}><Input disabled={Boolean(editing)} placeholder="host:3306 或 jdbc:mysql://host:3306/database" /></Form.Item>
          <Form.Item name="databaseName" label="默认数据库"><Input disabled={Boolean(editing)} /></Form.Item>
          <Form.Item name="databaseAbbr" label="数据库缩写" rules={[{ required: true }, { pattern: /^[a-z]{1,9}$/, message: '仅支持 1-9 位小写字母' }]}><Input disabled={Boolean(editing)} /></Form.Item>
          <Form.Item name="account" label="账号"><Input disabled={Boolean(editing)} autoComplete="off" /></Form.Item>
          {!editing && <Form.Item name="password" label="密码"><Input.Password autoComplete="new-password" /></Form.Item>}
          <Form.Item name="description" label="描述"><Input.TextArea rows={3} /></Form.Item>
        </Form>
      </Drawer>
      <Modal title="查看 Server" open={Boolean(viewing)} footer={null} onCancel={() => setViewing(undefined)}>
        {viewing && <Descriptions bordered size="small" column={1} items={[
          { key: 'id', label: '服务 ID', children: viewing.id }, { key: 'name', label: '名称', children: viewing.name },
          { key: 'type', label: '类型', children: 'MySQL' }, { key: 'address', label: '地址', children: viewing.address },
          { key: 'database', label: '数据库', children: viewing.databaseName || '-' }, { key: 'abbr', label: '库缩写', children: viewing.databaseAbbr || '-' },
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
