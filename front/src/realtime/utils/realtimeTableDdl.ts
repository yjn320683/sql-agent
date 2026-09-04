import type { RealtimeTable } from '../types';

const identifier = (value: string) => `\`${value.replace(/`/g, '``')}\``;
const literal = (value: unknown) => String(value).replace(/'/g, "''");

export function createRealtimeTableDdl(table?: RealtimeTable): string {
  if (!table?.columns?.length) return '';
  const primaryKeys = table.columns.filter((column) => column.primaryKey).map((column) => identifier(column.name));
  const partitions = table.columns.filter((column) => column.partitionKey).map((column) => identifier(column.name));
  const definitions = table.columns.map((column) => {
    const nullableClause = column.nullable || /\bNOT\s+NULL\b/i.test(column.dataType) ? '' : ' NOT NULL';
    return `  ${identifier(column.name)} ${column.dataType}${nullableClause}${column.comment ? ` COMMENT '${literal(column.comment)}'` : ''}`;
  });
  if (primaryKeys.length) definitions.push(`  PRIMARY KEY (${primaryKeys.join(', ')}) NOT ENFORCED`);
  const options = Object.entries(table.options ?? {}).sort(([left], [right]) => left.localeCompare(right)).map(([key, value]) => `  '${literal(key)}' = '${literal(value)}'`);
  return [
    `CREATE TABLE ${identifier(table.catalogName || 'paimon')}.${identifier(table.databaseName)}.${identifier(table.tableName)} (`,
    definitions.join(',\n'),
    ')',
    table.tableComment ? `COMMENT '${literal(table.tableComment)}'` : '',
    partitions.length ? `PARTITIONED BY (${partitions.join(', ')})` : '',
    options.length ? `WITH (\n${options.join(',\n')}\n)` : '',
  ].filter(Boolean).join('\n') + ';';
}
