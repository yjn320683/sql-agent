import { describe, expect, it } from 'vitest';
import { availableChangeActions, changeDetailButtonText, normalizeChangeAction } from './changeActions';

describe('变更记录动作', () => {
  it('新旧动作值统一显示为参考项目文案', () => {
    expect(normalizeChangeAction('EDIT')).toBe('编辑');
    expect(normalizeChangeAction('编辑任务')).toBe('编辑');
    expect(normalizeChangeAction('DELETE')).toBe('删除');
    expect(normalizeChangeAction('START')).toBe('启动');
    expect(normalizeChangeAction('STOP')).toBe('停止');
    expect(normalizeChangeAction('REFRESH')).toBe('状态同步');
  });

  it('详情入口与动作类型保持一致', () => {
    expect(changeDetailButtonText('EDIT')).toBe('查看变更');
    expect(changeDetailButtonText('START')).toBe('使用参数');
    expect(changeDetailButtonText('STOP')).toBe('停止详情');
  });

  it('操作筛选只展示当前任务实际存在的合法变更类型', () => {
    expect(availableChangeActions(['CREATE', 'DEBUG_START', 'ALERT_ACK', 'STOP']))
      .toEqual(['创建', '停止']);
    expect(availableChangeActions(['STATUS_SYNC', 'EDIT', 'START', 'EDIT']))
      .toEqual(['编辑', '启动', '状态同步']);
  });
});
