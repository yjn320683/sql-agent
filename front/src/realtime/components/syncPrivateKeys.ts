import type { MysqlTableSchema, TablePrivateConfig } from '../types';
import { computedColumnName, computedColumnKeySafety } from './computedColumns';

import { partitionField, partitionValueError } from './partitionDefaults';

const values = (value: unknown): string[] => Array.isArray(value)
  ? value.map(String).map((item) => item.trim()).filter(Boolean)
  : String(value ?? '').split(',').map((item) => item.trim()).filter(Boolean);

export function syncPrivateKeyErrors(table: string, config: TablePrivateConfig,
  schema?: MysqlTableSchema, tableOverrides: Record<string, unknown> = {}, typeMappings: string[] = []): string[] {
  if (!schema) return [];
  const expressions = config.computedColumns ?? [];
  const computed = new Map(expressions.flatMap((expression) => {
    const name = computedColumnName(expression);
    return name ? [[name, expression] as const] : [];
  }));
  const primary = config.primaryKeys?.length ? config.primaryKeys : schema.primaryKeys;
  const partitions = config.partitionKeys ?? [];
  const errors: string[] = [];
  if (!schema.primaryKeys.length) errors.push(`${table}：MySQL CDC 源表无物理主键`);
  const defaults = config.partitionDefaults?.values ?? {};
  if (Object.keys(defaults).length && schema.primaryKeys.some(key => !primary.includes(key))) errors.push(table + '：启用默认分区必须完整保留源物理主键');
  for (const key of partitions) {
    const field = partitionField(schema, expressions, key, typeMappings);
    const error = config.partitionDefaults ? partitionValueError(defaults[key], field) : field.allowed ? undefined : field.reason;
    if (error) errors.push(table + '：' + key + '：' + error);
  }
  if (Object.keys(defaults).length) {
    for (const key of primary) {
      if (!partitionField(schema, expressions, key, typeMappings).deterministic) errors.push(table + '：主键计算列必须确定性可回放：' + key);
    }
  }
  const keyError = (key: string) => {
    if (partitions.includes(key) && defaults[key] !== undefined) return undefined;
    const expression = computed.get(key);
    if (expression) {
      const safety = computedColumnKeySafety(expression, schema.columns, [], expressions);
      return safety.safe ? undefined : safety.reason;
    }
    const column = schema.columns.find((item) => item.name === key);
    if (!column) return `字段 ${key} 不存在`;
    return column.nullable === false ? undefined : `字段 ${key} 允许 NULL`;
  };
  for (const key of primary) {
    const error = keyError(key);
    if (error) errors.push(`${table}：${error}，不能作为 Paimon 主键`);
  }
  if (partitions.some((key) => !primary.includes(key))) {
    errors.push(`${table}：分区键必须包含在最终主键中`);
  }
  if (partitions.length && primary.every((key) => partitions.includes(key))) {
    errors.push(`${table}：分区键不能覆盖全部最终主键，请至少保留一个非分区主键字段`);
  }
  for (const key of partitions) {
    const error = keyError(key);
    if (error) errors.push(`${table}：${error}，不能作为 Paimon 分区键`);
  }
  if (String(tableOverrides['bucket-function.type'] ?? '').toLowerCase() === 'mod') {
    const configuredBucketKeys = values(tableOverrides['bucket-key']);
    const bucketKeys = configuredBucketKeys.length ? configuredBucketKeys : primary;
    const bucketColumn = bucketKeys.length === 1
      ? schema.columns.find((column) => column.name === bucketKeys[0]) : undefined;
    if (bucketKeys.length !== 1) errors.push(`${table}：mod 分桶的 Bucket Key 必须且只能包含一个字段`);
    else if (!bucketColumn || !/^(?:int|integer|bigint)(?:\b|\()/i.test(bucketColumn.type)) {
      errors.push(`${table}：mod 分桶的 Bucket Key ${bucketKeys[0]} 必须是 INT 或 BIGINT 物理字段`);
    }
  }
  return errors;
}
