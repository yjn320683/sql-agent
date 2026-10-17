import { describe, expect, it } from 'vitest';
import type { DiagnosticReport } from '../../types';
import { diagnosticAiContext, diagnosticInterpretationPrompt } from './diagnosticHandoff';

const report: DiagnosticReport = {
  targetKind: 'REALTIME_INSTANCE', targetId: 250, revision: 2, status: 'PARTIAL', complete: false,
  failureStage: 'UNKNOWN', summary: '实例已失败，但当前证据不足以确定根因。',
  generatedAt: '2026-10-13T10:39:19', findings: [],
  evidence: [{ id: 'instance-status', type: 'STATUS', source: 'rt_task_instance', label: '实例状态', value: 'failed' }],
  missingEvidence: ['实例没有 Job ID'],
};

describe('实例诊断转交页面 AI', () => {
  it('绑定当前任务和实例，并携带完整的诊断报告', () => {
    const context = diagnosticAiContext({ taskId: 1101, instanceId: 250, report });
    expect(context).toMatchObject({
      contextType: 'REALTIME_INSTANCE', entityId: '250', parentId: '1101', revision: 2,
    });
    expect(context.draft?.diagnosticReport).toBe(report);
  });

  it('预填解读请求，不自动要求恢复或修改', () => {
    expect(diagnosticInterpretationPrompt(250, 2)).toContain('实例 #250');
    expect(diagnosticInterpretationPrompt(250, 2)).toContain('不要自动执行恢复或修改');
  });
});
