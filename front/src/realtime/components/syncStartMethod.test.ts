import { describe, expect, it } from 'vitest';
import { showStateRecoveryFallback, syncStartMethodLabel, syncStartMethodOptions } from './syncStartMethod';

describe('syncStartMethodLabel', () => {
  it('distinguishes all persisted start methods without guessing', () => {
    expect(syncStartMethodLabel('savepoint')).toBe('从 Savepoint 恢复');
    expect(syncStartMethodLabel('checkpoint')).toBe('从 Checkpoint 恢复');
    expect(syncStartMethodLabel('direct', 1)).toBe('从指定时间戳开始消费');
    expect(syncStartMethodLabel('direct', undefined, 'timestamp')).toBe('从指定时间戳开始消费');
    expect(syncStartMethodLabel('direct', undefined, 'initial')).toBe('首次全量同步');
    expect(syncStartMethodLabel('direct', undefined, 'earliest-offset')).toBe('从最早消费点同步');
    expect(syncStartMethodLabel('direct', undefined, 'latest-offset')).toBe('从最新消费点同步');
    expect(syncStartMethodLabel('direct')).toBe('未记录');
  });
});

describe('syncStartMethodOptions', () => {
  it('offers both stored recovery types even with an older required Savepoint policy', () => {
    const policy = {
      productionLocked: true,
      requiredStartType: 'savepoint' as const,
      requiredStatePath: 'hdfs://state/savepoint-1',
    };
    expect(showStateRecoveryFallback(policy)).toBe(true);
    expect(syncStartMethodOptions(policy).map(({ value, disabled }) => [value, disabled])).toEqual([
      ['direct', true], ['savepoint', false], ['checkpoint', false],
    ]);
  });

  it('requires a stored state when changed tables have no Savepoint', () => {
    const options = syncStartMethodOptions({ productionLocked: true, syncTableSetChanged: true });
    expect(options.map(({ value, disabled }) => [value, disabled])).toEqual([
      ['direct', true], ['savepoint', false], ['checkpoint', false],
    ]);
  });

  it('never allows another initial snapshot after a production instance exists', () => {
    const options = syncStartMethodOptions({ productionLocked: true, canResetConsumptionPoint: true });
    expect(options.find((item) => item.value === 'direct')?.disabled).toBe(true);
    expect(options.find((item) => item.value === 'checkpoint')?.disabled).toBe(false);
  });

  it('allows an explicit incremental restart when the table set is unchanged', () => {
    const options = syncStartMethodOptions({ productionLocked: true }, 'incremental');
    expect(options.find((item) => item.value === 'direct')?.disabled).toBe(false);
  });
});
