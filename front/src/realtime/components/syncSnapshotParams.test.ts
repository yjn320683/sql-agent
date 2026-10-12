import { describe, expect, it } from 'vitest';
import { snapshotParams } from './syncSnapshotParams';

describe('snapshotParams', () => {
  it('只用快照中保存的参数，保持已知字段的分组和控件类型', () => {
    const params = snapshotParams({
      mysql_conf: { 'scan.snapshot.fetch.size': '512' },
      table_conf: { 'sequence.field': '__meta_op_ts', 'changelog-producer': 'lookup', custom: 'old' },
      flink_conf: {},
    });
    expect(params.map((param) => `${param.paramType}.${param.paramKey}`)).toEqual([
      'mysql_conf.scan.snapshot.fetch.size', 'table_conf.changelog-producer', 'table_conf.sequence.field', 'table_conf.custom',
    ]);
    expect(params[0]).toMatchObject({ required: true, inputType: 'select', paramValue: '[{"label":"512","value":"512"}]' });
    expect(params[1]).toMatchObject({ required: true, inputType: 'select', paramValue: '[{"label":"lookup","value":"lookup"}]' });
    expect(params[2]).toMatchObject({ required: true, inputType: 'input' });
    expect(params[3]).toMatchObject({ required: false, keyDesc: 'custom' });
    expect(params.some((param) => param.paramKey === 'bucket')).toBe(false);
  });
});
