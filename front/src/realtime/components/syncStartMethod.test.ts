import { describe, expect, it } from 'vitest';
import { showStateRecoveryFallback, syncStartMethodLabel, syncStartMethodOptions } from './syncStartMethod';

describe('syncStartMethodLabel', () => {
  it('distinguishes all persisted start methods without guessing', () => {
    expect(syncStartMethodLabel('savepoint')).toBe('从 Savepoint 恢复');
    expect(syncStartMethodLabel('checkpoint')).toBe('从 Checkpoint 恢复');
    expect(syncStartMethodLabel('direct', 1)).toBe('从指定时间戳开始消费');
    expect(syncStartMethodLabel('direct', undefined, 'timestamp')).toBe('从指定时间戳开始消费');
    expect(syncStartMethodLabel('direct', undefined, 'initial')).toBe('首次全量同步');
    expect(syncStartMethodLabel('direct')).toBe('未记录');
  });
});

describe('syncStartMethodOptions', () => {
  it('locks a required Savepoint until the user explicitly enables fallback', () => {
    const policy = {
      productionLocked: true,
      requiredStartType: 'savepoint' as const,
      requiredStatePath: 'hdfs://state/savepoint-1',
    };
    expect(showStateRecoveryFallback(policy)).toBe(true);
    expect(syncStartMethodOptions(policy).map(({ value, disabled }) => [value, disabled])).toEqual([
      ['direct', true], ['savepoint', false], ['checkpoint', false], ['timestamp', false],
    ]);
  });

  it('requires checkpoint or timestamp when changed tables have no Savepoint', () => {
    const options = syncStartMethodOptions({ productionLocked: true, syncTableSetChanged: true });
    expect(options.map(({ value, disabled }) => [value, disabled])).toEqual([
      ['direct', true], ['savepoint', true], ['checkpoint', false], ['timestamp', false],
    ]);
  });
});
