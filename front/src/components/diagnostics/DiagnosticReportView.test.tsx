import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { DiagnosticReport } from '../../types';
import DiagnosticReportView from './DiagnosticReportView';

const partialReport: DiagnosticReport = {
  targetKind: 'REALTIME_INSTANCE', targetId: 282, revision: 2, status: 'PARTIAL', complete: false,
  failureStage: 'NONE', summary: '当前实例未处于失败状态，未发现可确认的故障根因。',
  generatedAt: '2026-10-13T10:39:19', findings: [],
  evidence: [{ id: 'instance-status', type: 'STATUS', source: 'rt_task_instance', label: '实例状态', value: 'canceled' }],
  missingEvidence: ['实例没有 Job ID，无法采集 Flink 指标和 Checkpoint。'],
};

describe('诊断报告展示', () => {
  it('区分无故障结论、证据缺口和原始证据', () => {
    const html = renderToStaticMarkup(<DiagnosticReportView report={partialReport} />);
    expect(html).toContain('诊断概览');
    expect(html).toContain('未发现可确认的故障根因');
    expect(html).toContain('证据缺口');
    expect(html).toContain('实例没有 Job ID');
    expect(html).toContain('diagnostic-evidence-instance-status');
    expect(html).not.toContain('诊断发现 <span>0</span>');
    expect(html).not.toContain('查看恢复选项');
  });

  it('已确认故障展示关联证据，人工建议不是无效操作按钮', () => {
    const report: DiagnosticReport = {
      ...partialReport, complete: true, status: 'COMPLETE', failureStage: 'CONNECTION', missingEvidence: [],
      findings: [{
        code: 'CONNECTION_FAILURE', severity: 'ERROR', title: '数据源或目标连接失败',
        cause: '连接被拒绝', impact: '实例未能正常运行', evidenceRefs: ['instance-status'],
        actions: [{ type: 'MANUAL', label: '检查连接', description: '人工核对' }],
      }],
    };
    const html = renderToStaticMarkup(<DiagnosticReportView report={report} />);
    expect(html).toContain('数据源或目标连接失败');
    expect(html).toContain('关联证据');
    expect(html).toContain('建议：检查连接');
    expect(html).not.toContain('>检查连接</button>');
  });

  it('规则引用的证据未采集时明确标记待核实', () => {
    const report: DiagnosticReport = {
      ...partialReport, failureStage: 'CHECKPOINT', summary: 'Checkpoint/Savepoint 失败',
      findings: [{ code: 'CHECKPOINT_FAILURE', severity: 'ERROR', title: 'Checkpoint/Savepoint 失败',
        cause: '规则命中', impact: '运行受影响', evidenceRefs: ['checkpoints'], actions: [] }],
    };
    const html = renderToStaticMarkup(<DiagnosticReportView report={report} />);
    expect(html).toContain('此结论需要人工复核');
    expect(html).toContain('checkpoints 未采集');
    expect(html).not.toContain('>checkpoints</button>');
  });
});
