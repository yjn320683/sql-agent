import { describe, expect, it } from 'vitest';
import type { AiContext, AiProposal, ChatMessage } from '../../types';
import { inspectProposal, recoverProposals } from './proposalRegistry';

const context: AiContext = { contextType: 'REALTIME_EXPORT_TASK', entityId: '9', revision: 'v2' };

describe('Agent Proposal Target Registry', () => {
  it('拒绝未登记字段和只读字段', () => {
    const unknown: AiProposal = { target: 'export-form', kind: 'CONFIG', patch: { secretField: true }, baseRevision: 'v2' };
    expect(inspectProposal(unknown, context)).toMatchObject({ status: 'INVALID' });
    const readOnly: AiProposal = { target: 'export-form', kind: 'CONFIG', patch: { taskConfig: { exportConfig: { mappings: [{ taskId: 12 }] } } }, baseRevision: 'v2' };
    expect(inspectProposal(readOnly, context)).toMatchObject({ status: 'INVALID' });
  });

  it('区分过期、页面不支持和允许的建议', () => {
    expect(inspectProposal({ target: 'export-form', kind: 'CONFIG', patch: { name: 'new' }, baseRevision: 'v1' }, context).status).toBe('STALE');
    expect(inspectProposal({ target: 'offline-sql', kind: 'SQL', after: 'select 1' }, context).status).toBe('UNSUPPORTED');
    expect(inspectProposal({ target: 'export-form', kind: 'CONFIG', patch: { taskConfig: { exportConfig: { sourceDatabase: 'ods' } } }, baseRevision: 'v2' }, context).status).toBe('APPLIED');
    expect(inspectProposal({ target: 'export-form', kind: 'CONFIG', patch: { taskConfig: { exportConfig: 'ods' } }, baseRevision: 'v2' }, context).status).toBe('INVALID');
  });

  it('从历史工具步骤恢复稳定 proposalId', () => {
    const messages: ChatMessage[] = [{ role: 'assistant', content: '', steps: [{
      kind: 'tool', id: 'toolu_9', name: 'mcp__sql_agent__platform_proposal_present', semanticType: 'proposal',
      input: { target: 'export-form', kind: 'CONFIG', patch: { name: 'new' } },
    }] }];
    expect(recoverProposals(messages)).toEqual([expect.objectContaining({ proposalId: 'toolu_9', target: 'export-form' })]);
  });
});
