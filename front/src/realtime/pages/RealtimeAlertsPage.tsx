import { useCallback, useEffect, useMemo, useState } from 'react';
import { Button, Descriptions, Drawer, Form, Input, InputNumber, Modal, Select, Space, Switch, Table, Tabs, Tag, Tooltip, Typography, message } from 'antd';
import { CheckOutlined, EditOutlined, PauseOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { acknowledgeAlert, getAlert, listAlertRules, listAlertsPage, muteAlert, unmuteAlert, updateAlertRule } from '../api';
import type { RealtimeAlert, RealtimeAlertRule } from '../types';

type View = 'ACTIVE' | 'RECOVERED' | 'RULES';
const statusLabels: Record<string, string> = { OPEN: '待处理', ACKNOWLEDGED: '已确认', MUTED: '已静默', RECOVERED: '已恢复' };

export default function RealtimeAlertsPage() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const requestedAlertId = Number(searchParams.get('alertId') || 0);
  const [view, setView] = useState<View>('ACTIVE');
  const [rows, setRows] = useState<RealtimeAlert[]>([]);
  const [rules, setRules] = useState<RealtimeAlertRule[]>([]);
  const [loading, setLoading] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [severity, setSeverity] = useState<string>();
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [total, setTotal] = useState(0);
  const [detail, setDetail] = useState<RealtimeAlert>();
  const [editingRule, setEditingRule] = useState<RealtimeAlertRule>();
  const [ruleForm] = Form.useForm();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      if (view === 'RULES') setRules(await listAlertRules());
      else {
        const query = new URLSearchParams({ view, page: String(page), pageSize: String(pageSize) });
        if (keyword.trim()) query.set('keyword', keyword.trim());
        if (severity) query.set('severity', severity);
        const result = await listAlertsPage(query);
        setRows(result.records); setTotal(result.total);
      }
    } catch (error) { message.error((error as Error).message); }
    finally { setLoading(false); }
  }, [keyword, page, pageSize, severity, view]);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    if (!requestedAlertId) return;
    void getAlert(requestedAlertId).then(setDetail).catch((error) => message.error(error.message));
  }, [requestedAlertId]);

  const openTask = (alert: RealtimeAlert) => {
    const path = alert.taskType === 'compute' ? '/realtime/compute' : alert.taskType === 'export' ? '/realtime/export' : '/realtime/sync-tasks';
    navigate(`${path}?taskId=${alert.taskId}&tab=${alert.taskInstanceId ? 'instances' : 'alerts'}${alert.taskInstanceId ? `&instanceId=${alert.taskInstanceId}` : ''}`);
  };

  const handleAction = async (action: 'ack' | 'mute' | 'unmute', alert: RealtimeAlert) => {
    try {
      if (action === 'ack') await acknowledgeAlert(alert.id);
      if (action === 'mute') await muteAlert(alert.id, new Date(Date.now() + 60 * 60 * 1000).toISOString());
      if (action === 'unmute') await unmuteAlert(alert.id);
      message.success(action === 'ack' ? '告警已确认' : action === 'mute' ? '已静默 1 小时' : '已解除静默');
      setDetail(undefined); await load();
    } catch (error) { message.error((error as Error).message); }
  };

  const alertColumns = useMemo(() => [
    { title: '级别', dataIndex: 'severity', width: 90, render: (value: string) => <Tag color={value === 'critical' ? 'red' : value === 'warning' ? 'orange' : 'blue'}>{value}</Tag> },
    { title: '状态', dataIndex: 'status', width: 105, render: (value: string) => <Tag color={value === 'OPEN' ? 'error' : value === 'RECOVERED' ? 'success' : value === 'MUTED' ? 'gold' : 'blue'}>{statusLabels[value] || value}</Tag> },
    { title: '任务', dataIndex: 'taskName', width: 210, ellipsis: true, render: (value: string, row: RealtimeAlert) => <Button type="link" className="table-link-button" onClick={() => openTask(row)}>{value}</Button> },
    { title: '规则/标题', width: 260, render: (_: unknown, row: RealtimeAlert) => <div><Typography.Text strong>{row.title}</Typography.Text><div className="realtime-cell-secondary">{row.ruleName || row.eventType}</div></div> },
    { title: '最近发生', dataIndex: 'lastOccurredAt', width: 175 },
    { title: '次数', dataIndex: 'occurrenceCount', width: 70 },
    { title: '操作', width: 190, fixed: 'right' as const, render: (_: unknown, row: RealtimeAlert) => <Space size={0}>
      <Button type="link" onClick={() => setDetail(row)}>详情</Button>
      {row.status === 'OPEN' && <Button type="link" icon={<CheckOutlined />} onClick={() => void handleAction('ack', row)}>确认</Button>}
      {(row.status === 'OPEN' || row.status === 'ACKNOWLEDGED') && <Button type="link" icon={<PauseOutlined />} onClick={() => void handleAction('mute', row)}>静默</Button>}
      {row.status === 'MUTED' && <Button type="link" onClick={() => void handleAction('unmute', row)}>解除</Button>}
    </Space> },
  ], [load]);

  const editRule = (rule: RealtimeAlertRule) => {
    setEditingRule(rule); ruleForm.setFieldsValue({ ...rule, enabled: Boolean(rule.enabled) });
  };

  return <div className="realtime-page realtime-sync-tasks-page realtime-alerts-page">
    <section className="realtime-sync-main-panel">
      <div className="realtime-page-heading">
        <Typography.Text type="secondary">发现、确认、静默并跟踪恢复，不重复刷屏</Typography.Text>
        <Tooltip title="刷新"><Button aria-label="刷新" icon={<ReloadOutlined />} onClick={() => void load()} /></Tooltip>
      </div>
      <Tabs activeKey={view} onChange={(key) => { setView(key as View); setPage(1); }} items={[
        { key: 'ACTIVE', label: '当前告警' }, { key: 'RECOVERED', label: '已恢复' }, { key: 'RULES', label: '告警规则' },
      ]} />
      {view !== 'RULES' && <>
        <div className="realtime-sync-filter-section"><div className="realtime-server-toolbar">
          <Input allowClear prefix={<SearchOutlined />} placeholder="告警 ID、任务、标题或详情" value={keyword} onChange={(event) => { setKeyword(event.target.value); setPage(1); }} />
          <Select allowClear placeholder="全部级别" value={severity} onChange={(value) => { setSeverity(value); setPage(1); }} options={[
            { label: '严重', value: 'critical' }, { label: '警告', value: 'warning' }, { label: '提示', value: 'info' },
          ]} />
        </div></div>
        <div className="realtime-sync-table-section"><Table rowKey="id" loading={loading} dataSource={rows} columns={alertColumns} scroll={{ x: 1180 }} pagination={{
          current: page, pageSize, total, showSizeChanger: true, pageSizeOptions: [20, 50, 100], showTotal: (value) => `共 ${value} 条`,
          onChange: (next, size) => { setPage(next); setPageSize(size); },
        }} /></div>
      </>}
      {view === 'RULES' && <Table rowKey="id" loading={loading} dataSource={rules} columns={[
        { title: '规则', dataIndex: 'ruleName', width: 190, render: (value: string, row: RealtimeAlertRule) => <div><Typography.Text strong>{value}</Typography.Text><div className="realtime-cell-secondary">{row.ruleCode}</div></div> },
        { title: '说明', dataIndex: 'description' }, { title: '级别', dataIndex: 'severity', width: 90 },
        { title: '阈值', dataIndex: 'thresholdValue', width: 90 }, { title: '连续采样', dataIndex: 'consecutiveSamples', width: 100 },
        { title: '窗口', dataIndex: 'windowSeconds', width: 100, render: (value: number) => `${value}s` },
        { title: '状态', dataIndex: 'enabled', width: 90, render: (value: boolean | number) => <Tag color={Boolean(value) ? 'success' : 'default'}>{Boolean(value) ? '启用' : '停用'}</Tag> },
        { title: '操作', width: 90, render: (_: unknown, row: RealtimeAlertRule) => <Button type="link" icon={<EditOutlined />} onClick={() => editRule(row)}>配置</Button> },
      ]} pagination={false} />}
    </section>

    <Drawer width={680} title={detail?.title || '告警详情'} open={Boolean(detail)} onClose={() => {
      setDetail(undefined); const next = new URLSearchParams(searchParams); next.delete('alertId'); setSearchParams(next, { replace: true });
    }}>
      {detail && <>
        <Descriptions bordered size="small" column={2} items={[
          { key: 'status', label: '状态', children: statusLabels[detail.status] || detail.status },
          { key: 'severity', label: '级别', children: detail.severity },
          { key: 'task', label: '任务', children: <Button type="link" onClick={() => openTask(detail)}>{detail.taskName}</Button> },
          { key: 'instance', label: '实例', children: detail.taskInstanceId || '-' },
          { key: 'rule', label: '规则', children: detail.ruleName || detail.eventType || '-' },
          { key: 'count', label: '发生次数', children: detail.occurrenceCount || 1 },
          { key: 'first', label: '首次发生', children: detail.firstOccurredAt || detail.createTime },
          { key: 'last', label: '最近发生', children: detail.lastOccurredAt || detail.updateTime },
          { key: 'detail', label: '详情', span: 2, children: detail.detail || '-' },
        ]} />
        <Typography.Title level={5} style={{ marginTop: 20 }}>证据</Typography.Title>
        <pre className="realtime-alert-evidence">{JSON.stringify(detail.evidence || {}, null, 2)}</pre>
        <Space style={{ marginTop: 16 }}>
          {detail.status === 'OPEN' && <Button type="primary" onClick={() => void handleAction('ack', detail)}>确认告警</Button>}
          {(detail.status === 'OPEN' || detail.status === 'ACKNOWLEDGED') && <Button onClick={() => void handleAction('mute', detail)}>静默 1 小时</Button>}
          {detail.status === 'MUTED' && <Button onClick={() => void handleAction('unmute', detail)}>解除静默</Button>}
          <Button onClick={() => openTask(detail)}>查看任务/实例</Button>
        </Space>
      </>}
    </Drawer>

    <Modal title={`配置规则 · ${editingRule?.ruleName || ''}`} open={Boolean(editingRule)} onCancel={() => setEditingRule(undefined)} onOk={async () => {
      if (!editingRule) return;
      try { await updateAlertRule(editingRule.id, await ruleForm.validateFields()); message.success('规则已更新'); setEditingRule(undefined); await load(); }
      catch (error) { if (error instanceof Error) message.error(error.message); }
    }}>
      <Form form={ruleForm} layout="vertical">
        <Form.Item name="enabled" label="启用" valuePropName="checked"><Switch /></Form.Item>
        <Form.Item name="severity" label="级别" rules={[{ required: true }]}><Select options={[{ value: 'info', label: '提示' }, { value: 'warning', label: '警告' }, { value: 'critical', label: '严重' }]} /></Form.Item>
        <Space align="start"><Form.Item name="thresholdValue" label="阈值" rules={[{ required: true }]}><InputNumber min={1} /></Form.Item>
          <Form.Item name="consecutiveSamples" label="连续采样次数" rules={[{ required: true }]}><InputNumber min={1} max={20} /></Form.Item>
          <Form.Item name="windowSeconds" label="统计窗口（秒）" rules={[{ required: true }]}><InputNumber min={1} max={86400} /></Form.Item></Space>
      </Form>
    </Modal>
  </div>;
}
