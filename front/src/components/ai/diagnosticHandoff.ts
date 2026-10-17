import type { AiContext, DiagnosticReport } from '../../types';

export const AI_DIAGNOSTIC_HANDOFF_EVENT = 'sql-agent:ai-diagnostic-handoff';

export interface AiDiagnosticHandoff {
  taskId: number;
  instanceId: number;
  report: DiagnosticReport;
}

export const diagnosticInterpretationPrompt = (instanceId: number, revision: number) =>
  `请解读实例 #${instanceId} 的诊断报告（修订 ${revision}）。区分已确认的证据与推测，说明缺失的证据、可能影响和下一步只读排查建议；不要把未确认的原因说成结论，也不要自动执行恢复或修改。`;

export function diagnosticAiContext({ taskId, instanceId, report }: AiDiagnosticHandoff): AiContext {
  return {
    contextType: 'REALTIME_INSTANCE',
    entityId: String(instanceId),
    parentId: String(taskId),
    title: `实时实例 #${instanceId} · 诊断`,
    revision: report.revision,
    draft: { diagnosticReport: report },
  };
}

export function handoffDiagnosticToAi(detail: AiDiagnosticHandoff): void {
  window.dispatchEvent(new CustomEvent<AiDiagnosticHandoff>(AI_DIAGNOSTIC_HANDOFF_EVENT, { detail }));
}
