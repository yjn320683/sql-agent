import { describe, expect, it, vi } from 'vitest';
import { dispatchFrame, type StreamCallbacks } from './chat';

function callbacks(): StreamCallbacks {
  return {
    onThinking: vi.fn(), onText: vi.fn(), onToolUse: vi.fn(), onToolResult: vi.fn(),
    onPermissionRequest: vi.fn(), onUserQuestionRequest: vi.fn(), onProposal: vi.fn(),
    onDone: vi.fn(), onError: vi.fn(),
  };
}

describe('页面 AI Proposal SSE', () => {
  it('保留字符串基线版本与字段补丁', () => {
    const cb = callbacks();
    const event = dispatchFrame([
      'event: proposal',
      'data: {"proposalId":"toolu_proposal","target":"sync-task-form","kind":"CONFIG","patch":{"flinkConf":{"parallelism":2}},"baseRevision":"2026-09-08T15:00:00","risks":["需重新校验"]}',
    ].join('\n'), cb);

    expect(event).toBe('proposal');
    expect(cb.onProposal).toHaveBeenCalledWith(expect.objectContaining({
      target: 'sync-task-form',
      proposalId: 'toolu_proposal',
      baseRevision: '2026-09-08T15:00:00',
      patch: { flinkConf: { parallelism: 2 } },
    }));
  });
});
