import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Alert, Button, Checkbox, Descriptions, Drawer, Empty, Input, Modal, Popconfirm, Space, Spin,
  Table, Tabs, Tag, Typography, message,
} from 'antd';
import {
  ArrowLeftOutlined, CheckCircleOutlined, EditOutlined, FileTextOutlined, ReloadOutlined,
  RetweetOutlined, StopOutlined,
} from '@ant-design/icons';
import { diffLines } from 'diff';
import type { ColumnsType } from 'antd/es/table';
import { useNavigate, useParams } from 'react-router-dom';
import {
  cancelDataCompare, forcePassDataCompareTable, getDataCompareReport, getDataCompareTableLog,
  rerunDataCompareTable, updateDataCompareRule,
} from '../../api/dataCompare';
import type {
  DataCompareReportVO, DataCompareRule, DataCompareTableVO,
} from '../../types';
import { useAutoTableActionWidth } from '../../utils/useAutoTableActionWidth';
import DataCompareStatusTag from './DataCompareStatusTag';

const ACTIVE = new Set(['PENDING', 'RUNNING', 'CANCELLING']);
const DEFAULT_RULE: DataCompareRule = {
  onlyCompareSamePrimaryKey: false,
  ignoreNullPrimaryKey: false,
  primaryKeyList: [],
  compareColumnList: [],
  probeColumnList: [],
};
const split = (value: string) => value.split(',').map((item) => item.trim()).filter(Boolean);

function parseJson(value?: string): unknown {
  if (!value) return undefined;
  try { return JSON.parse(value); } catch { return value; }
}

function StructuredValue({ value, empty = '暂无数据' }: { value: unknown; empty?: string }) {
  if (value === undefined || value === null || value === '') {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={empty} />;
  }
  if (Array.isArray(value)) {
    if (!value.length) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={empty} />;
    if (value.every((item) => typeof item !== 'object' || item === null)) {
      return <Space wrap>{value.map((item, index) => <Tag key={`${String(item)}-${index}`}>{String(item)}</Tag>)}</Space>;
    }
    const keys = Array.from(new Set(value.flatMap((item) => Object.keys((item || {}) as object))));
    return <Table size="small" rowKey={(_, index) => String(index)} pagination={false} columns={keys.map((key) => ({ title: key, dataIndex: key, ellipsis: true, render: (item: unknown) => typeof item === 'object' ? JSON.stringify(item) : String(item ?? '-') }))} dataSource={value as Record<string, unknown>[]} scroll={{ x: Math.max(680, keys.length * 160) }} />;
  }
  if (typeof value === 'object') {
    return <Descriptions size="small" bordered column={2} items={Object.entries(value as Record<string, unknown>).map(([key, item]) => ({ key, label: key, children: typeof item === 'object' ? JSON.stringify(item) : String(item ?? '-') }))} />;
  }
  return <Typography.Paragraph className="structured-text-value">{String(value)}</Typography.Paragraph>;
}

function SqlDiff({ left, right }: { left?: string; right?: string }) {
  const parts = useMemo(() => diffLines(left || '', right || ''), [left, right]);
  return <pre className="compare-sql-diff">{parts.map((part, index) => (
    <span key={`${index}-${part.value.length}`} className={part.added ? 'diff-added' : part.removed ? 'diff-removed' : ''}>{part.value}</span>
  ))}</pre>;
}

export default function DataCompareDetailPage() {
  const { actionColumnWidth, actionRef } = useAutoTableActionWidth({ initialWidth: 260 });
  const navigate = useNavigate();
  const id = Number(useParams().compareId);
  const [detail, setDetail] = useState<DataCompareReportVO>();
  const [loading, setLoading] = useState(false);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [keyword, setKeyword] = useState('');
  const [searchedKeyword, setSearchedKeyword] = useState('');
  const [logOpen, setLogOpen] = useState(false);
  const [logContent, setLogContent] = useState('');
  const [logLoading, setLogLoading] = useState(false);
  const [ruleTable, setRuleTable] = useState<DataCompareTableVO>();
  const [rule, setRule] = useState<DataCompareRule>(DEFAULT_RULE);
  const [primaryKeysText, setPrimaryKeysText] = useState('');
  const [compareColumnsText, setCompareColumnsText] = useState('');
  const [probeColumnsText, setProbeColumnsText] = useState('');
  const [forceTable, setForceTable] = useState<DataCompareTableVO>();
  const [forceReason, setForceReason] = useState('');
  const [actionLoading, setActionLoading] = useState(false);

  const load = useCallback(async (silent = false) => {
    if (!Number.isFinite(id) || id <= 0) return;
    if (!silent) setLoading(true);
    try {
      setDetail(await getDataCompareReport(id, page, pageSize, searchedKeyword));
    } catch (error) {
      if (!silent) message.error(`加载验数报告失败：${(error as Error).message}`);
    } finally {
      if (!silent) setLoading(false);
    }
  }, [id, page, pageSize, searchedKeyword]);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    if (!detail || !ACTIVE.has(detail.status)) return undefined;
    const timer = window.setInterval(() => void load(true), 2000);
    return () => window.clearInterval(timer);
  }, [detail, load]);

  const openLog = async (tableId: number) => {
    setLogOpen(true);
    setLogLoading(true);
    try {
      setLogContent((await getDataCompareTableLog(tableId)).content || '');
    } catch (error) {
      setLogContent(`日志读取失败：${(error as Error).message}`);
    } finally {
      setLogLoading(false);
    }
  };

  const openRule = (table: DataCompareTableVO) => {
    let parsed = DEFAULT_RULE;
    try { parsed = table.compare_rule ? { ...DEFAULT_RULE, ...JSON.parse(table.compare_rule) } : DEFAULT_RULE; } catch { parsed = DEFAULT_RULE; }
    setRuleTable(table);
    setRule(parsed);
    setPrimaryKeysText(parsed.primaryKeyList.join(', '));
    setCompareColumnsText(parsed.compareColumnList.join(', '));
    setProbeColumnsText(parsed.probeColumnList.join(', '));
  };

  const saveRule = async () => {
    if (!ruleTable) return;
    setActionLoading(true);
    try {
      await updateDataCompareRule(ruleTable.id, {
        ...rule,
        primaryKeyList: split(primaryKeysText),
        compareColumnList: split(compareColumnsText),
        probeColumnList: split(probeColumnsText),
      });
      setRuleTable(undefined);
      message.success('规则已保存，原结果已标记为需重新验数');
      await load();
    } catch (error) {
      message.error(`保存规则失败：${(error as Error).message}`);
    } finally { setActionLoading(false); }
  };

  const rerun = async (table: DataCompareTableVO) => {
    setActionLoading(true);
    try {
      await rerunDataCompareTable(table.id);
      message.success('已创建新的单表验数执行，差异表将使用新名称');
      await load();
    } catch (error) {
      message.error(`重新验数失败：${(error as Error).message}`);
    } finally { setActionLoading(false); }
  };

  const forcePass = async () => {
    if (!forceTable || !forceReason.trim()) {
      message.warning('请填写强制通过原因');
      return;
    }
    setActionLoading(true);
    try {
      await forcePassDataCompareTable(forceTable.id, forceReason.trim());
      setForceTable(undefined);
      setForceReason('');
      message.success('已记录强制通过审计，真实不通过结果保持不变');
      await load();
    } catch (error) {
      message.error(`强制通过失败：${(error as Error).message}`);
    } finally { setActionLoading(false); }
  };

  const columns = useMemo<ColumnsType<DataCompareTableVO>>(() => [
    { title: '原始输出表', dataIndex: 'original_tbl_name', width: 240, fixed: 'left', ellipsis: true, render: (value) => value || '-' },
    { title: '汇总结果', dataIndex: 'display_status', width: 120, fixed: 'left', render: (value, row) => <DataCompareStatusTag status={value || row.status} /> },
    { title: '真实结果', dataIndex: 'status', width: 110, render: (value) => <DataCompareStatusTag status={value} /> },
    { title: '规则修订', dataIndex: 'rule_revision', width: 100, render: (value, row) => `${value ?? 0} / 已验 ${row.verified_rule_revision ?? 0}` },
    { title: '元数据', dataIndex: 'meta_data_is_same', width: 100, render: (value) => value === undefined || value === null ? '-' : value ? <Tag color="success">一致</Tag> : <Tag color="warning">有差异</Tag> },
    { title: '行数', dataIndex: 'row_num_is_same', width: 90, render: (value) => value === undefined || value === null ? '-' : value ? <Tag color="success">一致</Tag> : <Tag color="warning">有差异</Tag> },
    { title: 'CRC32', dataIndex: 'crc32_value_is_same', width: 90, render: (value) => value === undefined || value === null ? '-' : value ? <Tag color="success">一致</Tag> : <Tag color="warning">有差异</Tag> },
    { title: '重跑次数', dataIndex: 'rerun_count', width: 90, render: (value) => value ?? 0 },
    { title: '差异表', dataIndex: 'diff_tbl_name', width: 260, ellipsis: true, render: (value) => value ? <code>{value}</code> : '-' },
    {
      title: '操作', width: actionColumnWidth, fixed: 'right', className: 'table-operation-column',
      render: (_, row) => <div ref={actionRef(row.id)} className="table-row-actions">
        <Button type="link" size="small" icon={<EditOutlined />} disabled={ACTIVE.has(row.status)} onClick={() => openRule(row)}>规则</Button>
        <Popconfirm title="按当前规则重新验数该表？" onConfirm={() => void rerun(row)}><Button type="link" size="small" icon={<RetweetOutlined />} disabled={ACTIVE.has(row.status) || actionLoading}>重验</Button></Popconfirm>
        {row.status === 'NOT_PASSED' && !row.result_stale ? <Button type="link" size="small" icon={<CheckCircleOutlined />} onClick={() => { setForceTable(row); setForceReason(''); }}>强制通过</Button> : null}
        <Button type="link" size="small" icon={<FileTextOutlined />} disabled={!row.log_file} onClick={() => void openLog(row.id)}>日志</Button>
      </div>,
    },
  ], [actionColumnWidth, actionLoading, actionRef]);

  if (loading && !detail) return <div className="route-loading"><Spin /></div>;
  if (!detail) return <div className="data-page"><Alert type="error" showIcon message="验数记录不存在或服务不可用" /></div>;

  const title = detail.compare_type === 'VERSION'
    ? `任务 ${detail.task_id} · ${detail.baseline_version_no ? `v${detail.baseline_version_no}` : '初始代码'} → v${detail.candidate_version_no}`
    : `${detail.baseline_table} → ${detail.candidate_table}`;

  return (
    <div className="data-page data-compare-detail-page">
      <header className="data-page-header">
        <div><Typography.Title level={2}>验数报告 {detail.id}</Typography.Title><Typography.Text type="secondary">{title}</Typography.Text></div>
        <Space>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/data-compares')}>返回验数中心</Button>
          <Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button>
          {ACTIVE.has(detail.status) ? <Popconfirm title="确认取消当前验数？" onConfirm={async () => { await cancelDataCompare(detail.id); await load(); }}><Button danger icon={<StopOutlined />}>取消</Button></Popconfirm> : null}
        </Space>
      </header>
      {detail.error_message ? <Alert type="error" showIcon message={detail.status === 'FAILED' ? '系统执行失败' : '验数未完成'} description={detail.error_message} /> : null}
      <section className="data-panel compare-summary-panel">
        <Descriptions size="small" column={4} colon={false}>
          <Descriptions.Item label="汇总状态"><DataCompareStatusTag status={detail.status} /></Descriptions.Item>
          <Descriptions.Item label="验数类型">{detail.compare_type === 'VERSION' ? '任务版本对比' : '数据表对比'}</Descriptions.Item>
          <Descriptions.Item label="Job ID"><span className="mono-id">{detail.id}</span></Descriptions.Item>
          <Descriptions.Item label="操作人">{detail.operator_ob_id}</Descriptions.Item>
          <Descriptions.Item label="开始时间">{detail.started_at || '-'}</Descriptions.Item>
          <Descriptions.Item label="结束时间">{detail.finished_at || '-'}</Descriptions.Item>
          <Descriptions.Item label="表数量">{detail.tablePage.total}</Descriptions.Item>
          <Descriptions.Item label="结论">{detail.status === 'FORCE_PASSED' ? '存在真实差异，已完成强制通过审计' : detail.status === 'NOT_PASSED' ? '存在真实数据差异' : detail.status === 'NEEDS_RERUN' ? '规则或代码变化后需重新验数' : detail.status === 'PASSED' ? '全部真实通过' : '-'}</Descriptions.Item>
        </Descriptions>
      </section>
      <section className="data-panel compare-report-panel">
        <div className="compare-section-title">
          <strong>数据表对比报告</strong>
          <Space.Compact><Input.Search allowClear value={keyword} placeholder="搜索表名或表级 ID" onChange={(event) => setKeyword(event.target.value)} onSearch={(value) => { setPage(1); setSearchedKeyword(value.trim()); }} style={{ width: 320 }} enterButton="查询" /></Space.Compact>
        </div>
        <Table
          rowKey="id"
          size="small"
          loading={loading}
          columns={columns}
          dataSource={detail.tablePage.items}
          pagination={{ current: page, pageSize, total: detail.tablePage.total, showSizeChanger: true, showTotal: (total) => `共 ${total} 张表`, onChange: (nextPage, nextPageSize) => { setPage(nextPageSize !== pageSize ? 1 : nextPage); setPageSize(nextPageSize); } }}
          scroll={{ x: 1410 + actionColumnWidth }}
          expandable={{
            expandedRowRender: (row) => (
              <Tabs className="ui-flat-tabs" size="small" items={[
                { key: 'tables', label: '表映射', children: <Descriptions size="small" bordered column={2} items={[
                  { key: 'source-left', label: '基线原始表', children: <code>{row.baseline_source_tbl_name || '-'}</code> },
                  { key: 'source-right', label: '候选原始表', children: <code>{row.candidate_source_tbl_name || '-'}</code> },
                  { key: 'debug-left', label: '基线调测表', children: <code>{row.baseline_tbl_name}</code> },
                  { key: 'debug-right', label: '候选调测表', children: <code>{row.candidate_tbl_name}</code> },
                ]} /> },
                { key: 'scope', label: '分区范围', children: <StructuredValue value={parseJson(row.partition_scope)} /> },
                { key: 'metadata', label: '元数据差异', children: <StructuredValue value={parseJson(row.meta_data_diff)} empty="元数据无差异" /> },
                { key: 'count', label: '行数 / CRC32', children: <div className="compare-metric-grid"><div><Typography.Text strong>行数</Typography.Text><StructuredValue value={parseJson(row.row_nums)} /></div><div><Typography.Text strong>CRC32</Typography.Text><StructuredValue value={parseJson(row.crc32_values)} /></div></div> },
                { key: 'probe', label: '抽样探测', children: <StructuredValue value={parseJson(row.col_probe_detail)} /> },
                { key: 'diff', label: '业务键差异样本', children: <StructuredValue value={parseJson(row.diff_detail)} empty="未发现业务键差异" /> },
                { key: 'audit', label: '强制通过审计', children: row.force_pass ? <Descriptions size="small" bordered column={3} items={[
                  { key: 'reason', label: '原因', children: row.force_reason || '-' },
                  { key: 'operator', label: '操作人', children: row.force_operator_ob_id || '-' },
                  { key: 'time', label: '时间', children: row.force_time || '-' },
                ]} /> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="未强制通过" /> },
              ]} />
            ),
          }}
          locale={{ emptyText: <Empty description="没有符合条件的表级报告" /> }}
        />
      </section>
      {detail.compare_type === 'VERSION' ? (
        <section className="data-panel compare-sql-panel">
          <div className="compare-section-title"><strong>SQL 快照与差异</strong><span>内容来自提交时的不可变计划</span></div>
          <Tabs className="ui-flat-tabs" size="small" items={[
            { key: 'original-baseline', label: '基线原始代码', children: <pre className="compare-sql-code">{detail.original_baseline_sql || '暂无'}</pre> },
            { key: 'original-candidate', label: '候选原始代码', children: <pre className="compare-sql-code">{detail.original_candidate_sql || '暂无'}</pre> },
            { key: 'baseline', label: '基线调测代码', children: <pre className="compare-sql-code">{detail.generated_baseline_sql || '暂无'}</pre> },
            { key: 'candidate', label: '候选调测代码', children: <pre className="compare-sql-code">{detail.generated_candidate_sql || '暂无'}</pre> },
            { key: 'diff', label: '调测 SQL 差异', children: <SqlDiff left={detail.generated_baseline_sql} right={detail.generated_candidate_sql} /> },
          ]} />
        </section>
      ) : null}
      <Drawer title="验数执行日志" placement="bottom" height="60vh" open={logOpen} onClose={() => setLogOpen(false)}>
        {logLoading ? <Spin /> : <pre className="execution-log-terminal">{logContent || '暂无日志内容'}</pre>}
      </Drawer>
      <Modal title={`修改比对规则 · ${ruleTable?.original_tbl_name || ''}`} open={Boolean(ruleTable)} confirmLoading={actionLoading} onOk={() => void saveRule()} onCancel={() => setRuleTable(undefined)} okText="保存并标记需重验">
        <div className="compare-rule-form">
          <label>业务主键<Input value={primaryKeysText} placeholder="id, business_date" onChange={(event) => setPrimaryKeysText(event.target.value)} /></label>
          <label>对比列<Input value={compareColumnsText} placeholder="留空比较全部同名列" onChange={(event) => setCompareColumnsText(event.target.value)} /></label>
          <label>抽样探测列<Input value={probeColumnsText} placeholder="留空自动选择" onChange={(event) => setProbeColumnsText(event.target.value)} /></label>
          <Space><Checkbox checked={rule.onlyCompareSamePrimaryKey} onChange={(event) => setRule((current) => ({ ...current, onlyCompareSamePrimaryKey: event.target.checked }))}>只比较相同业务键</Checkbox><Checkbox checked={rule.ignoreNullPrimaryKey} onChange={(event) => setRule((current) => ({ ...current, ignoreNullPrimaryKey: event.target.checked }))}>忽略空业务键</Checkbox></Space>
        </div>
      </Modal>
      <Modal title={`强制通过 · ${forceTable?.original_tbl_name || ''}`} open={Boolean(forceTable)} confirmLoading={actionLoading} onOk={() => void forcePass()} onCancel={() => setForceTable(undefined)} okText="确认强制通过">
        <Alert type="warning" showIcon message="真实 NOT_PASSED 结果会保留" description="本操作只记录独立的强制通过状态和审计信息，并允许通过联合发布门禁。重新验数会清除强制通过。" />
        <Input.TextArea autoFocus rows={4} maxLength={1000} showCount value={forceReason} placeholder="必填：说明接受差异的原因" onChange={(event) => setForceReason(event.target.value)} />
      </Modal>
    </div>
  );
}
