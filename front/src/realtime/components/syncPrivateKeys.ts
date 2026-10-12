import type { MysqlTableSchema, TablePrivateConfig } from '../types';
import { computedColumnName, parseComputedColumnExpression } from './computedColumns';

const NULL_PROPAGATING_FUNCTIONS = new Set([
  'year', 'month', 'day', 'hour', 'minute', 'second', 'date_format',
  'substring', 'truncate', 'cast', 'upper', 'lower', 'trim',
]);

const values = (value: unknown): string[] => Array.isArray(value)
  ? value.map(String).map((item) => item.trim()).filter(Boolean)
  : String(value ?? '').split(',').map((item) => item.trim()).filter(Boolean);

export function syncPrivateKeyErrors(table: string, config: TablePrivateConfig,
  schema?: MysqlTableSchema, tableOverrides: Record<string, unknown> = {}): string[] {
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
  const keyError = (key: string) => {
    const expression = computed.get(key);
    if (expression) {
      const parsed = parseComputedColumnExpression(expression);
      if (!parsed) return `计算列 ${expression} 无法证明结果非空`;
      if (parsed.functionName === 'now' && parsed.arguments.length === 0) return undefined;
      if (!NULL_PROPAGATING_FUNCTIONS.has(parsed.functionName)) return `计算列 ${expression} 无法证明结果非空`;
      const reference = schema.columns.find((column) => column.name === parsed.arguments[0]);
      return reference?.nullable === false ? undefined
        : `计算列 ${expression} 引用字段 ${parsed.arguments[0]} 允许 NULL，结果可能为空`;
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
