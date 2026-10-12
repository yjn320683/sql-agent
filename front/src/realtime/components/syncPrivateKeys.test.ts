import { describe, expect, it } from 'vitest';
import type { MysqlTableSchema } from '../types';
import { syncPrivateKeyErrors } from './syncPrivateKeys';

const schema: MysqlTableSchema = {
  table: 'orders', primaryKeys: ['id', 'tenant'], columns: [
    { name: 'id', type: 'BIGINT', nullable: false },
    { name: 'tenant', type: 'VARCHAR(32)', nullable: false },
    { name: 'remark', type: 'VARCHAR(100)', nullable: true },
  ],
};

describe('syncPrivateKeyErrors', () => {
  it('允许非空主键的真子集作为分区键', () => {
    expect(syncPrivateKeyErrors('orders', { partitionKeys: ['id'] }, schema)).toEqual([]);
  });

  it('即时拒绝非主键分区、覆盖全部主键与可空字段', () => {
    expect(syncPrivateKeyErrors('orders', { partitionKeys: ['remark'] }, schema)
      .some((error) => error.includes('最终主键'))).toBe(true);
    expect(syncPrivateKeyErrors('orders', { partitionKeys: ['id', 'tenant'] }, schema)
      .some((error) => error.includes('覆盖全部'))).toBe(true);
    expect(syncPrivateKeyErrors('orders', { primaryKeys: ['remark'] }, schema)
      .some((error) => error.includes('允许 NULL'))).toBe(true);
  });

  it('无物理主键不能靠私有主键覆盖，空 Bucket Key 继承主键', () => {
    expect(syncPrivateKeyErrors('orders', { primaryKeys: ['id'] }, { ...schema, primaryKeys: [] })
      .some((error) => error.includes('无物理主键'))).toBe(true);
    expect(syncPrivateKeyErrors('orders', { primaryKeys: ['id'] }, schema,
      { 'bucket-function.type': 'mod', 'bucket-key': '' })).toEqual([]);
  });

  it('计算列遵守后端的非空证明规则', () => {
    expect(syncPrivateKeyErrors('orders', {
      primaryKeys: ['stamp'], computedColumns: ['stamp=now()'],
    }, schema)).toEqual([]);
    expect(syncPrivateKeyErrors('orders', {
      primaryKeys: ['day'], computedColumns: ['day=date_format(remark,yyyyMMdd)'],
    }, schema).some((error) => error.includes('允许 NULL'))).toBe(true);
  });

  it('mod 分桶只接受单个 INT 或 BIGINT 物理字段', () => {
    expect(syncPrivateKeyErrors('orders', {}, schema,
      { 'bucket-function.type': 'mod' }).some((error) => error.includes('只能包含一个'))).toBe(true);
    expect(syncPrivateKeyErrors('orders', {}, schema,
      { 'bucket-function.type': 'mod', 'bucket-key': 'tenant' })
      .some((error) => error.includes('INT 或 BIGINT'))).toBe(true);
    expect(syncPrivateKeyErrors('orders', {}, schema,
      { 'bucket-function.type': 'mod', 'bucket-key': 'id' })).toEqual([]);
  });
});
