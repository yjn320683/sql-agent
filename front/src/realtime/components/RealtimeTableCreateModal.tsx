import { useEffect, useMemo, useState } from 'react';
import { Button, Form, Input, InputNumber, Modal, Select, Space, Table, Tabs, message, type FormInstance } from 'antd';
import { CopyOutlined, DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import type { RealtimeTableColumn } from '../types';
import type { AiProposal } from '../../types';
import {
  REALTIME_TABLE_OPTION_KEYS,
  createRealtimeTableDdl,
  parseRealtimeTableDdl,
  toRealtimeTableDraft,
  type RealtimeTableFormValue,
} from '../utils/realtimeTableDdl';

interface Props {
  open: boolean;
  form: FormInstance<RealtimeTableFormValue>;
  databases: string[];
  submitting?: boolean;
  onCancel: () => void;
  onSubmit: () => void;
}

const typeOptions = ['BOOLEAN', 'TINYINT', 'SMALLINT', 'INT', 'BIGINT', 'FLOAT', 'DOUBLE', 'STRING', 'BYTES', 'DATE', 'TIME', 'TIMESTAMP(3)', 'DECIMAL(18,2)', 'VARCHAR(255)']
  .map((value) => ({ value, label: value }));
const boolOptions = [{ value: true, label: '是' }, { value: false, label: '否' }];
const propertyOptions = REALTIME_TABLE_OPTION_KEYS.map((value) => ({ value, label: value }));

export default function RealtimeTableCreateModal({ open, form, databases, submitting, onCancel, onSubmit }: Props) {
  const [mode, setMode] = useState('visual');
  const [ddlInput, setDdlInput] = useState('');
  const watched = Form.useWatch([], form) as RealtimeTableFormValue | undefined;
  const preview = useMemo(() => createRealtimeTableDdl(toRealtimeTableDraft(watched)), [watched]);

  useEffect(() => {
    if (!open) return undefined;
    const applyAiProposal = (rawEvent: Event) => {
      if (rawEvent.defaultPrevented) return;
      const event = rawEvent as CustomEvent<AiProposal>; const proposal = event.detail; if (!proposal) return;
      const kind = proposal.kind.toUpperCase();
      try {
        if (kind === 'DDL' && proposal.target === 'realtime-table-ddl' && proposal.after) form.setFieldsValue(parseRealtimeTableDdl(proposal.after));
        else if ((kind === 'FORM' || kind === 'CONFIG') && proposal.target === 'realtime-table-form' && proposal.patch) form.setFieldsValue(proposal.patch as RealtimeTableFormValue);
        else return;
        setMode('visual'); event.preventDefault();
      } catch (error) { message.error(`AI DDL 无法应用：${(error as Error).message}`); }
    };
    const publishAiContext = () => window.dispatchEvent(new CustomEvent('sql-agent:ai-context-update', {
      detail: { contextType: 'REALTIME_TABLE', title: '新建实时表', revision: '0', draft: { ddl: preview, form: watched } },
    }));
    window.addEventListener('sql-agent:apply-ai-proposal', applyAiProposal);
    window.addEventListener('sql-agent:ai-context-request', publishAiContext);
    publishAiContext();
    return () => {
      window.removeEventListener('sql-agent:apply-ai-proposal', applyAiProposal);
      window.removeEventListener('sql-agent:ai-context-request', publishAiContext);
    };
  }, [form, open, preview, watched]);

  const parseDdl = () => {
    try {
      form.setFieldsValue(parseRealtimeTableDdl(ddlInput));
      setMode('visual');
      message.success('DDL 已解析并回填，可继续调整');
    } catch (error) {
      message.error((error as Error).message);
    }
  };
  const copyPreview = async () => {
    if (!preview) return;
    try { await navigator.clipboard.writeText(preview); message.success('DDL 已复制'); }
    catch { message.error('复制失败，请手动选择 DDL'); }
  };

  const visualContent = <div className="realtime-table-create-form">
    <section className="realtime-create-section">
      <div className="realtime-create-section-title"><strong>库表信息</strong><span>定义 Paimon 表的名称、类型和用途</span></div>
      <div className="realtime-create-basic-grid">
        <Form.Item name="databaseName" label="Paimon 数据库" rules={[{ required: true, message: '请选择数据库' }]}>
          <Select showSearch options={databases.map((value) => ({ value, label: value }))} placeholder="请选择数据库" />
        </Form.Item>
        <Form.Item name="tableName" label="表名" rules={[{ required: true, message: '请输入表名' }, { pattern: /^[A-Za-z_][A-Za-z0-9_]{0,127}$/, message: '仅支持字母、数字和下划线，且不能以数字开头' }]}>
          <Input placeholder="例如 orders" />
        </Form.Item>
        <Form.Item name="tableType" label="表类型" rules={[{ required: true }]}>
          <Select options={[{ value: 'primary_key', label: '主键表' }, { value: 'append_only', label: 'Append-only 表' }]} />
        </Form.Item>
        <Form.Item name="tableComment" label="表描述" className="realtime-create-full-row"><Input placeholder="请输入表用途或业务说明" /></Form.Item>
      </div>
    </section>

    <section className="realtime-create-section">
      <div className="realtime-create-section-title"><strong>字段定义</strong><span>主键字段自动设为不可空，字段顺序即物理表字段顺序</span></div>
      <Form.List name="columns">{(fields, { add, remove }) => <>
        <Table className="realtime-create-column-table" pagination={false} dataSource={fields} rowKey="key" scroll={{ x: 950 }} columns={[
          { title: '字段名', width: 190, render: (_, field) => <Form.Item name={[field.name, 'name']} rules={[{ required: true, message: '请输入字段名' }]} noStyle><Input placeholder="字段名" /></Form.Item> },
          { title: '数据类型', width: 180, render: (_, field) => <Form.Item name={[field.name, 'dataType']} rules={[{ required: true, message: '请选择类型' }]} noStyle><Select options={typeOptions} showSearch allowClear placeholder="类型" /></Form.Item> },
          { title: '可空', width: 90, render: (_, field) => <Form.Item name={[field.name, 'nullable']} noStyle><Select options={boolOptions} /></Form.Item> },
          { title: '主键', width: 90, render: (_, field) => <Form.Item name={[field.name, 'primaryKey']} noStyle><Select options={boolOptions} /></Form.Item> },
          { title: '分区键', width: 100, render: (_, field) => <Form.Item name={[field.name, 'partitionKey']} noStyle><Select options={boolOptions} /></Form.Item> },
          { title: '描述', render: (_, field) => <Form.Item name={[field.name, 'comment']} noStyle><Input placeholder="字段说明" /></Form.Item> },
          { title: '', width: 46, render: (_, field) => <Button type="text" danger aria-label="删除字段" icon={<DeleteOutlined />} disabled={fields.length === 1} onClick={() => remove(field.name)} /> },
        ]} />
        <Button className="realtime-create-add-row" block type="dashed" icon={<PlusOutlined />} onClick={() => add({ nullable: true, primaryKey: false, partitionKey: false })}>添加字段</Button>
      </>}</Form.List>
    </section>

    <section className="realtime-create-section">
      <div className="realtime-create-section-title"><strong>表属性</strong><span>常用属性直接配置，其他安全属性按需添加</span></div>
      <div className="realtime-create-property-grid">
        <Form.Item name="bucket" label="Bucket" rules={[{ required: true, message: '请输入 Bucket' }]}><InputNumber min={-1} precision={0} /></Form.Item>
        <Form.Item name="sinkParallelism" label="Sink 并行度" tooltip="写入该表时建议使用的并行度"><InputNumber min={1} precision={0} placeholder="例如 2" /></Form.Item>
        <Form.Item name="changelogProducer" label="Changelog Producer" rules={[{ required: true }]}><Select options={['none', 'input', 'lookup', 'full-compaction'].map((value) => ({ value, label: value }))} /></Form.Item>
        <Form.Item name="consumerExpiration" label="Consumer 过期时间" tooltip="例如 1 d、12 h"><Input placeholder="例如 1 d" /></Form.Item>
      </div>
      <Form.List name="extraOptions">{(fields, { add, remove }) => <div className="realtime-create-more-options">
        {fields.map((field) => <div className="realtime-create-option-row" key={field.key}>
          <Form.Item name={[field.name, 'key']} rules={[{ required: true, message: '请选择属性' }]}><Select options={propertyOptions} placeholder="表属性" /></Form.Item>
          <Form.Item name={[field.name, 'value']} rules={[{ required: true, message: '请输入属性值' }]}><Input placeholder="属性值" /></Form.Item>
          <Button type="text" danger aria-label="删除表属性" icon={<DeleteOutlined />} onClick={() => remove(field.name)} />
        </div>)}
        <Button type="dashed" icon={<PlusOutlined />} onClick={() => add()}>更多配置</Button>
      </div>}</Form.List>
    </section>
  </div>;

  const importContent = <div className="realtime-table-ddl-import">
    <div className="realtime-ddl-import-head">
      <div><strong>粘贴 Paimon CREATE TABLE</strong><span>支持字段、注释、主键、分区键和 WITH 表属性，解析后可继续可视化调整</span></div>
      <Button type="primary" disabled={!ddlInput.trim()} onClick={parseDdl}>解析并回填</Button>
    </div>
    <Input.TextArea value={ddlInput} onChange={(event) => setDdlInput(event.target.value)} rows={16} spellCheck={false} placeholder={'CREATE TABLE `paimon`.`ods_real`.`orders` (\n  `id` BIGINT NOT NULL,\n  PRIMARY KEY (`id`) NOT ENFORCED\n)\nWITH (\n  \'bucket\' = \'2\'\n);'} />
  </div>;

  return <Modal className="realtime-table-create-modal" title="新建实时表" open={open} width={1180} onCancel={onCancel} destroyOnHidden
    footer={<Space><Button onClick={onCancel}>取消</Button><Button type="primary" loading={submitting} onClick={onSubmit}>创建实时表</Button></Space>}>
    <Form form={form} layout="vertical" requiredMark="optional">
      <Form.Item name="catalogName" hidden><Input /></Form.Item>
      <Tabs className="ui-flat-tabs realtime-create-mode-tabs" activeKey={mode} onChange={setMode} items={[
        { key: 'visual', label: '可视化配置', children: visualContent },
        { key: 'ddl', label: 'DDL 导入', children: importContent },
      ]} />
    </Form>
    <section className="realtime-create-preview">
      <div className="realtime-create-preview-head"><div><strong>最终建表 DDL</strong><span>随上方配置实时更新，创建时以该配置为准</span></div><Button icon={<CopyOutlined />} disabled={!preview} onClick={() => void copyPreview()}>复制 DDL</Button></div>
      <pre>{preview || '请先填写数据库、表名和至少一个字段'}</pre>
    </section>
  </Modal>;
}

export function normalizeRealtimeTableColumns(columns?: RealtimeTableColumn[]): RealtimeTableColumn[] {
  return (columns ?? []).map((column, index) => ({ ...column, sortOrder: index }));
}
