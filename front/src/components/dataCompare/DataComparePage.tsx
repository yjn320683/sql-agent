import { useCallback, useEffect, useState } from 'react';
import type { RefCallback } from 'react';
import {
  Button, Checkbox, DatePicker, Empty, Form, Input, InputNumber, Modal, Pagination, Segmented, Select, Space, Table, Tooltip, Typography, message,
} from 'antd';
import { DownOutlined, ExperimentOutlined, PlusOutlined, ReloadOutlined, SearchOutlined, UpOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { useNavigate } from 'react-router-dom';
import { createDataCompare, listDataCompares } from '../../api/dataCompare';
import { getDataMapPrimaryKeys } from '../../api/workspace';
import type { DataCompareJobVO, DataComparePageVO } from '../../types';
import { useAutoTableActionWidth } from '../../utils/useAutoTableActionWidth';
import DataCompareStatusTag from './DataCompareStatusTag';

const EMPTY: DataComparePageVO = { items: [], page: 1, pageSize: 20, total: 0 };
const formatter = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
});

interface TableFormValue {
  baselineTable: string;
  candidateTable: string;
  primaryKeys?: string;
  compareColumns?: string;
  probeColumns?: string;
  onlyCompareSameColumn?: boolean;
}

const columns = (
  navigate: (path: string) => void,
  actionColumnWidth: number,
  actionRef: (key: string | number) => RefCallback<HTMLDivElement>,
): ColumnsType<DataCompareJobVO> => [
  { title: '验数 ID', dataIndex: 'id', width: 110, fixed: 'left', render: (id) => <span className="mono-id">{id}</span> },
  { title: '任务 / 对象', width: 300, fixed: 'left', render: (_, row) => row.compare_type === 'VERSION'
    ? `任务 ${row.task_id} · v${row.baseline_version_no} → v${row.candidate_version_no}`
    : `${row.baseline_table} → ${row.candidate_table}` },
  { title: '类型', dataIndex: 'compare_type', width: 110, render: (value) => value === 'VERSION' ? '版本验数' : '表对比' },
  { title: '结果', dataIndex: 'status', width: 110, render: (status) => <DataCompareStatusTag status={status} /> },
  { title: '操作人', dataIndex: 'operator_ob_id', width: 130 },
  { title: '创建时间', dataIndex: 'create_time', width: 180, render: (value) => formatter.format(new Date(value)) },
  { title: '错误摘要', dataIndex: 'error_message', ellipsis: true, render: (value) => value || <span className="muted-text">-</span> },
  {
    title: '操作', width: actionColumnWidth, fixed: 'right', className: 'table-operation-column',
    render: (_, row) => <div ref={actionRef(row.id)} className="table-row-actions"><Button type="link" size="small" onClick={(event) => { event.stopPropagation(); navigate(`/data-compares/${row.id}`); }}>详情</Button></div>,
  },
];

const splitColumns = (value?: string) => (value || '').split(',').map((item) => item.trim()).filter(Boolean);
const splitTable = (value?: string): [string, string] | undefined => {
  const parts = (value || '').split('.').map((item) => item.trim()).filter(Boolean);
  return parts.length === 2 ? [parts[0], parts[1]] : undefined;
};

export default function DataComparePage() {
  const navigate = useNavigate();
  const { actionColumnWidth, actionRef } = useAutoTableActionWidth({ initialWidth: 96 });
  const [form] = Form.useForm<TableFormValue>();
  const [data, setData] = useState(EMPTY);
  const [status, setStatus] = useState('all');
  const [type, setType] = useState('all');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [keyword, setKeyword] = useState('');
  const [committedKeyword, setCommittedKeyword] = useState('');
  const [taskId, setTaskId] = useState<number>();
  const [versionNo, setVersionNo] = useState<number>();
  const [jobId, setJobId] = useState<number>();
  const [operator, setOperator] = useState('');
  const [mine, setMine] = useState(false);
  const [timeRange, setTimeRange] = useState<[string, string] | undefined>();
  const [loading, setLoading] = useState(false);
  const [tableModal, setTableModal] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [suggestedKeys, setSuggestedKeys] = useState<string[]>([]);
  const [filtersExpanded, setFiltersExpanded] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setData(await listDataCompares({
        status, type, page, pageSize, taskId, versionNo, jobId, operator,
        mine, fromTime: timeRange?.[0], toTime: timeRange?.[1], keyword: committedKeyword,
      }));
    } catch (error) {
      message.error(`加载验数历史失败：${(error as Error).message}`);
    } finally {
      setLoading(false);
    }
  }, [committedKeyword, jobId, mine, operator, page, pageSize, status, taskId, timeRange, type, versionNo]);

  const submitSearch = () => {
    setPage(1);
    setCommittedKeyword(keyword.trim());
  };

  useEffect(() => { void load(); }, [load]);

  const submitTableCompare = async () => {
    const value = await form.validateFields();
    setSubmitting(true);
    try {
      const detail = await createDataCompare({
        compareType: 'TABLE',
        baselineTable: value.baselineTable.trim(),
        candidateTable: value.candidateTable.trim(),
        baselineSteps: [],
        candidateSteps: [],
        onlyCompareSameColumn: Boolean(value.onlyCompareSameColumn),
        tables: [{
          baselineTable: value.baselineTable.trim(),
          candidateTable: value.candidateTable.trim(),
          rule: {
            onlyCompareSamePrimaryKey: false,
            ignoreNullPrimaryKey: false,
            primaryKeyList: splitColumns(value.primaryKeys),
            compareColumnList: splitColumns(value.compareColumns),
            probeColumnList: splitColumns(value.probeColumns),
          },
        }],
      });
      setTableModal(false);
      form.resetFields();
      setSuggestedKeys([]);
      navigate(`/data-compares/${detail.id}`);
    } catch (error) {
      if (error instanceof Error) message.error(`创建表对比失败：${error.message}`);
    } finally {
      setSubmitting(false);
    }
  };

  const loadPrimaryKeySuggestion = async () => {
    const baseline = splitTable(form.getFieldValue('baselineTable'));
    const candidate = splitTable(form.getFieldValue('candidateTable'));
    if (!baseline || !candidate) {
      message.warning('请先填写完整的 db.table');
      return;
    }
    try {
      const [left, right] = await Promise.all([
        getDataMapPrimaryKeys(baseline[0], baseline[1]),
        getDataMapPrimaryKeys(candidate[0], candidate[1]),
      ]);
      const rightKeys = new Set(right.result.primaryKeys.map((key) => key.toLowerCase()));
      const keys = left.result.primaryKeys.filter((key) => rightKeys.has(key.toLowerCase()));
      setSuggestedKeys(keys);
      if (!keys.length) message.info('Data Map 没有两侧共同的主键建议');
    } catch (error) {
      message.warning(`主键建议暂不可用：${(error as Error).message}`);
    }
  };

  return (
    <div className="data-page data-compare-page">
      <section className="data-panel">
        <div className="data-toolbar data-compare-toolbar">
          <div className="data-compare-toolbar-main">
            <Space size={8}>
              <Segmented className="ui-flat-segmented" value={type} options={[{ label: '全部', value: 'all' }, { label: '任务对比', value: 'VERSION' }, { label: '数据表对比', value: 'TABLE' }]} onChange={(value) => { setPage(1); setType(String(value)); }} />
              <Select className="data-compare-status-select" value={status} options={[{ label: '全部状态', value: 'all' }, { label: '运行中', value: 'RUNNING' }, { label: '真实通过', value: 'PASSED' }, { label: '强制通过', value: 'FORCE_PASSED' }, { label: '不一致', value: 'NOT_PASSED' }, { label: '需重验', value: 'NEEDS_RERUN' }, { label: '失败', value: 'FAILED' }]} onChange={(value) => { setPage(1); setStatus(value); }} />
              <Input className="data-compare-search" allowClear value={keyword} prefix={<SearchOutlined />} placeholder="搜索验数 ID、任务、表或操作人" onChange={(event) => setKeyword(event.target.value)} onPressEnter={submitSearch} />
              <Button type="primary" onClick={submitSearch}>查询</Button>
              <Tooltip title="刷新"><Button icon={<ReloadOutlined />} onClick={() => void load()} /></Tooltip>
              <Button type="text" icon={filtersExpanded ? <UpOutlined /> : <DownOutlined />} onClick={() => setFiltersExpanded((value) => !value)}>{filtersExpanded ? '收起' : '展开'}</Button>
            </Space>
            <Space size={8}>
              <span className="result-count">共 {data.total} 条</span>
              <Button icon={<PlusOutlined />} onClick={() => setTableModal(true)}>表对比</Button>
              <Button type="primary" icon={<ExperimentOutlined />} onClick={() => navigate('/data-compares/new')}>版本验数</Button>
            </Space>
          </div>
          {filtersExpanded ? <div className="data-compare-advanced-filters">
              <InputNumber min={1} controls={false} value={taskId} placeholder="任务 ID" onChange={(value) => setTaskId(value || undefined)} />
              <InputNumber min={1} controls={false} value={versionNo} placeholder="版本号" onChange={(value) => setVersionNo(value || undefined)} />
              <InputNumber min={1} controls={false} value={jobId} placeholder="Job ID" onChange={(value) => setJobId(value || undefined)} />
              <Input allowClear value={operator} placeholder="操作人" disabled={mine} onChange={(event) => setOperator(event.target.value)} />
              <DatePicker.RangePicker showTime onChange={(_, values) => setTimeRange(values[0] && values[1] ? [values[0], values[1]] : undefined)} />
              <Checkbox checked={mine} onChange={(event) => setMine(event.target.checked)}>只看自己</Checkbox>
          </div> : null}
        </div>
        <Table rowKey="id" size="middle" columns={columns(navigate, actionColumnWidth, actionRef)} dataSource={data.items} loading={loading} pagination={false} scroll={{ x: 1144 + actionColumnWidth }} locale={{ emptyText: <Empty description={committedKeyword ? '没有匹配的验数记录' : '暂无验数记录'} /> }} onRow={(row) => ({ className: 'clickable-task-row', onClick: () => navigate(`/data-compares/${row.id}`) })} />
        <div className="table-pagination"><Pagination current={page} pageSize={pageSize} total={data.total} showSizeChanger onChange={(next, size) => { setPage(next); setPageSize(size); }} /></div>
      </section>
      <Modal title="创建 Hive 表对比" open={tableModal} onCancel={() => { setTableModal(false); setSuggestedKeys([]); }} onOk={() => void submitTableCompare()} confirmLoading={submitting} okText="开始验数" width={720} destroyOnHidden>
        <Form form={form} layout="vertical" initialValues={{ onlyCompareSameColumn: true }}>
          <div className="compare-two-column-form">
            <Form.Item label="基线表" name="baselineTable" rules={[{ required: true, message: '请输入 db.table' }]}><Input placeholder="database.baseline_table" /></Form.Item>
            <Form.Item label="候选表" name="candidateTable" rules={[{ required: true, message: '请输入 db.table' }]}><Input placeholder="database.candidate_table" /></Form.Item>
          </div>
          <Form.Item label="业务键" name="primaryKeys" extra="多个字段用逗号分隔；不填写时只做表级、字段级和 CRC32 验证"><Input placeholder="order_id, item_id" /></Form.Item>
          <Space className="compare-key-suggestion">
            <Button size="small" onClick={() => void loadPrimaryKeySuggestion()}>读取 Data Map 建议</Button>
            {suggestedKeys.length ? <Typography.Text type="secondary">建议：{suggestedKeys.join(', ')} <Button type="link" size="small" onClick={() => form.setFieldValue('primaryKeys', suggestedKeys.join(', '))}>采用</Button></Typography.Text> : null}
          </Space>
          <Form.Item label="指定对比列" name="compareColumns" extra="留空时使用两侧同名字段"><Input placeholder="可选，多个字段用逗号分隔" /></Form.Item>
          <Form.Item label="指定探查列" name="probeColumns"><Input placeholder="可选，多个字段用逗号分隔" /></Form.Item>
          <Form.Item name="onlyCompareSameColumn" valuePropName="checked"><Checkbox>元数据不完全一致时继续比较同名字段</Checkbox></Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
