import { ClockCircleOutlined, InfoCircleOutlined, LinkOutlined, ReloadOutlined, WarningOutlined } from '@ant-design/icons';
import { Button, Empty, Tag, Tooltip, Typography } from 'antd';
import type { DiagnosticReport } from '../../types';

interface Props {
  report?: DiagnosticReport;
  loading?: boolean;
  onRefresh?: () => void;
  onAction?: (type: string) => void;
}

const severityColor: Record<string, string> = { ERROR: 'error', WARNING: 'warning', INFO: 'processing' };
const severityLabel: Record<string, string> = { ERROR: '严重', WARNING: '提醒', INFO: '信息' };
const stageLabel: Record<string, string> = { NONE: '未进入失败阶段', UNKNOWN: '待确定', CHECKPOINT: 'Checkpoint', RUNTIME: '运行阶段', SCHEMA: 'Schema', CONNECTION: '连接阶段', SUBMISSION: '提交阶段' };
const evidenceTypeLabel: Record<string, string> = { STATUS: '状态', IDENTIFIER: '标识', LOG: '日志', METRIC: '指标', CHECKPOINT: 'Checkpoint', ALERT: '告警', ERROR: '错误', STATE: '状态' };

export default function DiagnosticReportView({ report, loading, onRefresh, onAction }: Props) {
  if (!report && !loading) return <div className="diagnostic-report-empty"><Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无诊断报告" /></div>;
  if (!report) return <div className="diagnostic-report-loading">正在采集诊断证据…</div>;

  const hasError = report.findings.some((finding) => finding.severity === 'ERROR');
  const generatedAt = String(report.generatedAt || '').replace('T', ' ').slice(0, 19) || '-';
  const evidenceLabels = new Map(report.evidence.map((item) => [item.id, item.label]));
  const hasUnverifiedFinding = report.findings.some((finding) => finding.evidenceRefs.some((ref) => !evidenceLabels.has(ref)));
  const tone = hasError && !hasUnverifiedFinding ? 'error' : report.failureStage === 'NONE' ? 'neutral' : 'warning';
  const focusEvidence = (id: string) => document.getElementById(`diagnostic-evidence-${id}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' });

  return <div className="diagnostic-report-view">
    <section className={`diagnostic-overview diagnostic-overview-${tone}`} aria-label="诊断概览">
      <div className="diagnostic-overview-main">
        <div className="diagnostic-overview-result">
          <span className="diagnostic-overview-icon" aria-hidden="true">{hasError ? <WarningOutlined /> : <InfoCircleOutlined />}</span>
          <div>
            <Typography.Title level={4}>{report.summary || '诊断报告'}</Typography.Title>
            <Typography.Text type="secondary">{hasUnverifiedFinding ? '规则命中，但关联证据未采集齐；此结论需要人工复核。' : hasError ? '根据当前证据匹配到故障规则，请核对下方判断依据。' : '当前没有可确认的故障根因，报告不会在证据不足时推测。'}</Typography.Text>
          </div>
        </div>
        <div className="diagnostic-overview-meta">
          <span>失败阶段 <strong title={report.failureStage}>{stageLabel[report.failureStage] ?? report.failureStage ?? '待确定'}</strong></span>
          <span>报告修订 <strong>#{report.revision}</strong></span>
          <span><ClockCircleOutlined /> {generatedAt}</span>
        </div>
      </div>
      <div className="diagnostic-overview-side">
        <span className="diagnostic-overview-counts">证据 <strong>{report.evidence.length}</strong> / 缺失 <strong>{report.missingEvidence.length}</strong></span>
        <Tag color={report.complete ? 'success' : 'warning'}>{report.complete ? '证据完整' : '证据不完整'}</Tag>
        {onRefresh && <Button icon={<ReloadOutlined />} loading={loading} onClick={onRefresh}>重新采集</Button>}
      </div>
    </section>

    {report.missingEvidence.length > 0 && <section className="diagnostic-gap" aria-label="缺失证据">
      <WarningOutlined /><strong>证据缺口</strong><span>{report.missingEvidence.join('；')}</span>
    </section>}

    {report.findings.length > 0 && <section className="diagnostic-section" aria-label="诊断发现">
      <header className="diagnostic-section-heading">
        <h3>诊断发现 <span>{report.findings.length}</span></h3>
      </header>
      <div className="diagnostic-findings">{report.findings.map((finding) => <article key={finding.code} className={`diagnostic-finding-card diagnostic-finding-${finding.severity.toLowerCase()}`}>
          <div className="diagnostic-finding-title"><Tag color={severityColor[finding.severity]}>{severityLabel[finding.severity] ?? finding.severity}</Tag><h4>{finding.title}</h4><code>{finding.code}</code></div>
          <div className="diagnostic-finding-details"><div><span>判断依据</span><p>{finding.cause}</p></div><div><span>影响</span><p>{finding.impact}</p></div></div>
          {finding.evidenceRefs.length > 0 && <div className="diagnostic-finding-refs"><span>关联证据</span>{finding.evidenceRefs.map((ref) => evidenceLabels.has(ref)
            ? <Button key={ref} type="link" size="small" onClick={() => focusEvidence(ref)}>{evidenceLabels.get(ref)}</Button>
            : <Tag key={ref} color="warning">{ref} 未采集</Tag>)}</div>}
          {finding.actions.length > 0 && <div className="diagnostic-finding-actions">{finding.actions.map((action) => action.link
            ? <Button key={`${action.type}-${action.label}`} size="small" href={action.link}>{action.label}</Button>
            : ['RECOVER', 'VIEW_DDL'].includes(action.type) && onAction
              ? <Button key={`${action.type}-${action.label}`} size="small" onClick={() => onAction(action.type)}>{action.label}</Button>
              : <Tooltip key={`${action.type}-${action.label}`} title={action.description}><span className="diagnostic-action-advice">建议：{action.label}</span></Tooltip>)}</div>}
        </article>)}</div>
    </section>}

    <section className="diagnostic-section" aria-label="原始证据">
      <header className="diagnostic-section-heading"><h3>原始证据 <span>{report.evidence.length}</span></h3></header>
      {report.evidence.length === 0 ? <div className="diagnostic-evidence-empty"><Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无可用证据" /></div>
        : <div className="diagnostic-evidence-grid">{report.evidence.map((item) => <article key={item.id} id={`diagnostic-evidence-${item.id}`} className="diagnostic-evidence-item">
          <div className="diagnostic-evidence-heading"><div><Tag title={item.type}>{evidenceTypeLabel[item.type] ?? item.type}</Tag><strong>{item.label}</strong></div><span className="diagnostic-evidence-source">{item.source}</span>{item.link && <Button type="link" size="small" icon={<LinkOutlined />} href={item.link}>来源</Button>}</div>
          <Typography.Paragraph className="diagnostic-evidence-value" copyable ellipsis={{ rows: 2, expandable: true }}>{item.value || '-'}</Typography.Paragraph>
        </article>)}</div>}
    </section>
  </div>;
}
