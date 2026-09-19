import { Alert, Button, Collapse, Descriptions, Empty, Space, Tag, Typography } from 'antd';
import { LinkOutlined, ReloadOutlined } from '@ant-design/icons';
import type { DiagnosticReport } from '../../types';

interface Props {
  report?: DiagnosticReport;
  loading?: boolean;
  onRefresh?: () => void;
  onAction?: (type: string) => void;
}

const severityColor: Record<string, string> = { ERROR: 'error', WARNING: 'warning', INFO: 'processing' };

export default function DiagnosticReportView({ report, loading, onRefresh, onAction }: Props) {
  if (!report && !loading) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无诊断报告" />;
  if (!report) return <div className="diagnostic-report-loading">正在采集诊断证据…</div>;
  return <div className="diagnostic-report-view">
    <div className="diagnostic-report-header">
      <div>
        <Space><Typography.Title level={5}>{report.summary || '诊断报告'}</Typography.Title><Tag color={report.complete ? 'success' : 'warning'}>{report.complete ? '证据完整' : '部分证据'}</Tag></Space>
        <Typography.Text type="secondary">失败阶段：{report.failureStage || 'UNKNOWN'} · 修订 {report.revision} · {String(report.generatedAt || '').replace('T', ' ').slice(0, 19)}</Typography.Text>
      </div>
      {onRefresh && <Button icon={<ReloadOutlined />} loading={loading} onClick={onRefresh}>重新采集</Button>}
    </div>
    {report.missingEvidence.length > 0 && <Alert showIcon type="warning" message="部分证据不可用" description={report.missingEvidence.join('；')} />}
    <div className="diagnostic-findings">
      {report.findings.length === 0 ? <Alert showIcon type="info" message="未发现可确认的故障根因" description="平台不会在证据不足时猜测根因，可继续查看下方原始证据。" /> : report.findings.map((finding) => <section key={finding.code} className="diagnostic-finding-card">
        <Space><Tag color={severityColor[finding.severity]}>{finding.severity}</Tag><Typography.Text strong>{finding.title}</Typography.Text><Typography.Text code>{finding.code}</Typography.Text></Space>
        <Descriptions size="small" column={1} items={[{ key: 'cause', label: '判断依据', children: finding.cause }, { key: 'impact', label: '影响', children: finding.impact }, { key: 'refs', label: '证据引用', children: finding.evidenceRefs.length > 0 ? <Space wrap>{finding.evidenceRefs.map((ref) => <Button key={ref} type="link" size="small" className="diagnostic-evidence-ref" onClick={() => document.getElementById(`diagnostic-evidence-${ref}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' })}>{ref}</Button>)}</Space> : '-' }]} />
        {finding.actions.length > 0 && <Space wrap>{finding.actions.map((action) => <Button key={`${finding.code}-${action.type}-${action.label}`} size="small" onClick={() => action.link ? window.location.assign(action.link) : onAction?.(action.type)}>{action.label}</Button>)}</Space>}
      </section>)}
    </div>
    <Collapse className="diagnostic-evidence-collapse" items={[{
      key: 'evidence', label: `原始证据（${report.evidence.length}）`, children: report.evidence.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无可用证据" /> : <div className="diagnostic-evidence-list">{report.evidence.map((item) => <div key={item.id} id={`diagnostic-evidence-${item.id}`} className="diagnostic-evidence-item"><div><Tag>{item.type}</Tag><Typography.Text strong>{item.label}</Typography.Text><Typography.Text type="secondary"> · {item.source}</Typography.Text>{item.link && <Button type="link" size="small" icon={<LinkOutlined />} onClick={() => window.location.assign(item.link!)}>查看来源</Button>}</div><Typography.Paragraph code copyable ellipsis={{ rows: 4, expandable: true }}>{item.value || '-'}</Typography.Paragraph></div>)}</div>,
    }]} />
  </div>;
}
