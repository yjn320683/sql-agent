import { useCallback, useEffect, useState } from 'react';
import {
  Button,
  Drawer,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Select,
  Space,
  Table,
  Tag,
  Tooltip,
  message,
} from 'antd';
import { EditOutlined, PlusOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import {
  createBusinessDomain,
  listBusinessDomainAssets,
  listBusinessDomains,
  updateBusinessDomain,
  updateBusinessDomainStatus,
} from '../api';
import type { AssetDomainAssignment, BusinessDomain } from '../types';

interface DomainFormValue {
  code: string;
  name: string;
  description?: string;
  owner?: string;
  sortOrder?: number;
}

const pageSize = 20;

export default function BusinessDomainsPage() {
  const [form] = Form.useForm<DomainFormValue>();
  const [rows, setRows] = useState<BusinessDomain[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [keyword, setKeyword] = useState('');
  const [committedKeyword, setCommittedKeyword] = useState('');
  const [enabled, setEnabled] = useState<string>('all');
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [editing, setEditing] = useState<BusinessDomain>();
  const [formOpen, setFormOpen] = useState(false);
  const [assetDomain, setAssetDomain] = useState<BusinessDomain>();
  const [assets, setAssets] = useState<AssetDomainAssignment[]>([]);
  const [assetTotal, setAssetTotal] = useState(0);
  const [assetPage, setAssetPage] = useState(1);
  const [assetType, setAssetType] = useState('');
  const [assetLoading, setAssetLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const query = new URLSearchParams({ page: String(page), pageSize: String(pageSize) });
      if (committedKeyword) query.set('keyword', committedKeyword);
      if (enabled !== 'all') query.set('enabled', enabled);
      const result = await listBusinessDomains(query);
      setRows(result.records);
      setTotal(result.total);
    } catch (error) {
      message.error(`加载业务域失败：${(error as Error).message}`);
    } finally {
      setLoading(false);
    }
  }, [committedKeyword, enabled, page]);

  useEffect(() => { void load(); }, [load]);

  const loadAssets = useCallback(async () => {
    if (!assetDomain) return;
    setAssetLoading(true);
    try {
      const query = new URLSearchParams({ page: String(assetPage), pageSize: String(pageSize) });
      if (assetType) query.set('assetType', assetType);
      const result = await listBusinessDomainAssets(assetDomain.id, query);
      setAssets(result.records);
      setAssetTotal(result.total);
    } catch (error) {
      message.error(`加载域内资产失败：${(error as Error).message}`);
    } finally {
      setAssetLoading(false);
    }
  }, [assetDomain, assetPage, assetType]);

  useEffect(() => { void loadAssets(); }, [loadAssets]);

  const openCreate = () => {
    setEditing(undefined);
    form.resetFields();
    form.setFieldsValue({ sortOrder: 0 });
    setFormOpen(true);
  };

  const openEdit = (row: BusinessDomain) => {
    setEditing(row);
    form.setFieldsValue({
      code: row.code,
      name: row.name,
      description: row.description,
      owner: row.owner,
      sortOrder: row.sortOrder,
    });
    setFormOpen(true);
  };

  const save = async () => {
    try {
      const value = await form.validateFields();
      setSaving(true);
      if (editing) await updateBusinessDomain(editing.id, value);
      else await createBusinessDomain(value);
      message.success(editing ? '业务域已更新' : '业务域已创建');
      setFormOpen(false);
      await load();
    } catch (error) {
      if (error instanceof Error) message.error(error.message);
    } finally {
      setSaving(false);
    }
  };

  const changeStatus = async (row: BusinessDomain) => {
    try {
      await updateBusinessDomainStatus(row.id, !Boolean(row.enabled));
      message.success(Boolean(row.enabled) ? '业务域已停用，原有资产关联保留' : '业务域已启用');
      await load();
    } catch (error) {
      message.error((error as Error).message);
    }
  };

  return (
    <div className="data-page business-domain-page">
      <section className="data-panel">
        <div className="data-toolbar business-domain-filter">
          <Input
            allowClear
            value={keyword}
            prefix={<SearchOutlined />}
            placeholder="编码 / 名称 / 说明"
            onChange={(event) => setKeyword(event.target.value)}
            onPressEnter={() => { setPage(1); setCommittedKeyword(keyword.trim()); }}
          />
          <Select
            value={enabled}
            options={[{ value: 'all', label: '全部状态' }, { value: 'true', label: '启用' }, { value: 'false', label: '停用' }]}
            onChange={(value) => { setEnabled(value); setPage(1); }}
          />
          <Button type="primary" icon={<SearchOutlined />} onClick={() => { setPage(1); setCommittedKeyword(keyword.trim()); }}>查询</Button>
          <Tooltip title="刷新"><Button icon={<ReloadOutlined />} onClick={() => void load()} /></Tooltip>
          <Button className="business-domain-create" type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建业务域</Button>
        </div>
        <Table
          rowKey="id"
          loading={loading}
          dataSource={rows}
          pagination={{ current: page, pageSize, total, showSizeChanger: false, showTotal: (value) => `共 ${value} 个业务域` }}
          onChange={(pagination) => setPage(pagination.current || 1)}
          columns={[
            { title: '业务域', width: 260, render: (_, row) => <Button type="link" onClick={() => { setAssetDomain(row); setAssetPage(1); }}>{row.name}</Button> },
            { title: '编码', dataIndex: 'code', width: 180, render: (value) => <code>{value}</code> },
            { title: '负责人', dataIndex: 'owner', width: 150, render: (value) => value || '-' },
            { title: '资产数', dataIndex: 'assetCount', width: 100, render: (value) => value ?? 0 },
            { title: '状态', width: 100, render: (_, row) => Boolean(row.enabled) ? <Tag color="success">启用</Tag> : <Tag>停用</Tag> },
            { title: '说明', dataIndex: 'description', ellipsis: true, render: (value) => value || '-' },
            {
              title: '操作', width: 210, fixed: 'right', render: (_, row) => <Space>
                <Button type="link" icon={<EditOutlined />} onClick={() => openEdit(row)}>编辑</Button>
                <Button type="link" onClick={() => { setAssetDomain(row); setAssetPage(1); }}>资产</Button>
                <Popconfirm
                  title={Boolean(row.enabled) ? '停用后不能新增资产关联，已有关系仍保留。确认停用？' : '确认启用该业务域？'}
                  onConfirm={() => void changeStatus(row)}
                >
                  <Button type="link" danger={Boolean(row.enabled)}>{Boolean(row.enabled) ? '停用' : '启用'}</Button>
                </Popconfirm>
              </Space>,
            },
          ]}
        />
      </section>
      <Modal
        title={editing ? '编辑业务域' : '新建业务域'}
        open={formOpen}
        confirmLoading={saving}
        onCancel={() => setFormOpen(false)}
        onOk={() => void save()}
        okText={editing ? '保存修改' : '创建'}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="code" label="业务域编码" rules={[{ required: true }, { pattern: /^[a-z][a-z0-9_]{0,31}$/, message: '以小写字母开头，只能包含小写字母、数字和下划线，最长 32 位' }]}>
            <Input disabled={Boolean(editing)} placeholder="例如 retail_order" />
          </Form.Item>
          <Form.Item name="name" label="业务域名称" rules={[{ required: true, max: 64 }]}><Input placeholder="例如零售订单" /></Form.Item>
          <Form.Item name="owner" label="负责人" rules={[{ max: 64 }]}><Input placeholder="业务负责人或团队" /></Form.Item>
          <Form.Item name="description" label="说明" rules={[{ max: 512 }]}><Input.TextArea rows={4} placeholder="说明业务边界和包含的数据资产" /></Form.Item>
          <Form.Item name="sortOrder" label="排序"><InputNumber min={0} max={9999} precision={0} /></Form.Item>
        </Form>
      </Modal>
      <Drawer
        title={assetDomain ? `${assetDomain.name} · 数据资产` : '数据资产'}
        width={860}
        open={Boolean(assetDomain)}
        onClose={() => setAssetDomain(undefined)}
        extra={<Select allowClear value={assetType || undefined} placeholder="全部类型" options={[{ value: 'HIVE', label: 'Hive' }, { value: 'PAIMON', label: 'Paimon' }]} onChange={(value) => { setAssetType(value || ''); setAssetPage(1); }} />}
      >
        <Table
          rowKey={(row) => row.assetKey || String(row.id)}
          loading={assetLoading}
          dataSource={assets}
          pagination={{ current: assetPage, pageSize, total: assetTotal, showSizeChanger: false }}
          onChange={(pagination) => setAssetPage(pagination.current || 1)}
          columns={[
            { title: '类型', dataIndex: 'assetType', width: 100, render: (value) => <Tag color={value === 'PAIMON' ? 'blue' : 'purple'}>{value}</Tag> },
            { title: 'Catalog', dataIndex: 'catalogName', width: 130 },
            { title: '数据库', dataIndex: 'databaseName', width: 180 },
            { title: '表', dataIndex: 'tableName', ellipsis: true },
            { title: '更新人', dataIndex: 'updatedBy', width: 130, render: (value) => value || '-' },
          ]}
        />
      </Drawer>
    </div>
  );
}
