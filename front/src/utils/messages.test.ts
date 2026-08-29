import { describe, expect, it } from 'vitest';
import { historyMessageForDisplay, isVisibleHistoryMessage } from './messages';

describe('history messages', () => {
  it('restores the original user requirement from an agent prompt', () => {
    const message = historyMessageForDisplay({
      role: 'user',
      content: [
        '当前 command：sql_optimize',
        '当前 taskId：1',
        '当前 executionId：未指定',
        '必须先调用 sql_task_get 查询任务和 SQL，不得要求用户重新粘贴 SQL。',
        '',
        '用户需求：',
        '优化一下',
      ].join('\n'),
    });

    expect(message.content).toBe('优化一下');
  });

  it('keeps ordinary user messages and hides skill metadata', () => {
    const ordinary = { role: 'user' as const, content: '解释这段 SQL' };
    expect(historyMessageForDisplay(ordinary)).toEqual(ordinary);
    expect(isVisibleHistoryMessage(ordinary)).toBe(true);
    expect(isVisibleHistoryMessage({
      role: 'user',
      content: 'Base directory for this skill: /tmp/skill',
    })).toBe(false);
  });
});
