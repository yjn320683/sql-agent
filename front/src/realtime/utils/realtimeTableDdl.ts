import type { RealtimeTable } from '../types';

export interface RealtimeTableDraft {
  catalogName?: string;
  databaseName?: string;
  tableName?: string;
  tableComment?: string;
  tableType?: 'primary_key' | 'append_only';
  columns?: RealtimeTable['columns'];
  options?: Record<string, string>;
}

export interface RealtimeTableFormValue extends RealtimeTableDraft {
  bucket?: number;
  changelogProducer?: string;
  sinkParallelism?: number;
  consumerExpiration?: string;
  extraOptions?: Array<{ key?: string; value?: string }>;
}

const identifier = (value: string) => `\`${value.replace(/`/g, '``')}\``;
const literal = (value: unknown) => String(value).replace(/'/g, "''");

export function createRealtimeTableDdl(table?: RealtimeTableDraft): string {
  if (!table?.databaseName || !table.tableName || !table.columns?.length) return '';
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

export const REALTIME_TABLE_OPTION_KEYS = [
  'snapshot.time-retained',
  'snapshot.num-retained.min',
  'snapshot.num-retained.max',
  'compaction.min.file-num',
  'compaction.max.file-num',
  'target-file-size',
  'write-buffer-size',
] as const;

const fixedOptionKeys = new Set(['bucket', 'changelog-producer', 'sink.parallelism', 'consumer.expiration-time']);

export function toRealtimeTableDraft(value?: RealtimeTableFormValue): RealtimeTableDraft {
  const options: Record<string, string> = {};
  if (value?.bucket !== undefined && value.bucket !== null) options.bucket = String(value.bucket);
  if (value?.changelogProducer) options['changelog-producer'] = value.changelogProducer;
  if (value?.sinkParallelism !== undefined && value.sinkParallelism !== null) options['sink.parallelism'] = String(value.sinkParallelism);
  if (value?.consumerExpiration?.trim()) options['consumer.expiration-time'] = value.consumerExpiration.trim();
  value?.extraOptions?.forEach((item) => {
    if (item?.key?.trim() && item.value !== undefined && item.value !== '') options[item.key.trim()] = String(item.value).trim();
  });
  return {
    catalogName: value?.catalogName || 'paimon',
    databaseName: value?.databaseName?.trim(),
    tableName: value?.tableName?.trim(),
    tableComment: value?.tableComment?.trim(),
    tableType: value?.tableType,
    columns: value?.columns?.filter((column) => column?.name?.trim() && column?.dataType?.trim()),
    options,
  };
}

export function toRealtimeTableFormValue(table: RealtimeTableDraft): RealtimeTableFormValue {
  const options = table.options ?? {};
  return {
    ...table,
    bucket: numberOption(options.bucket),
    changelogProducer: options['changelog-producer'] || 'input',
    sinkParallelism: numberOption(options['sink.parallelism']),
    consumerExpiration: options['consumer.expiration-time'] || '',
    extraOptions: Object.entries(options)
      .filter(([key]) => !fixedOptionKeys.has(key))
      .map(([key, value]) => ({ key, value })),
  };
}

/** 解析本页面支持的 Paimon CREATE TABLE，不接受 AS SELECT 等会丢失结构的语法。 */
export function parseRealtimeTableDdl(raw: string): RealtimeTableFormValue {
  const ddl = raw.trim().replace(/;\s*$/, '');
  const head = /^CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?/i.exec(ddl);
  if (!head) throw new Error('仅支持 CREATE TABLE 建表语句');
  const open = findOutsideQuotes(ddl, '(', head[0].length);
  if (open < 0) throw new Error('DDL 中缺少字段定义');
  const qualifiedName = ddl.slice(head[0].length, open).trim();
  const names = splitQualifiedName(qualifiedName);
  if (names.length < 2 || names.length > 3) throw new Error('表名需要使用 database.table 或 catalog.database.table');
  if (names.length === 3 && names[0].toLowerCase() !== 'paimon') throw new Error('当前仅支持 paimon Catalog');
  const close = findMatchingParenthesis(ddl, open);
  if (close < 0) throw new Error('字段定义括号不完整');

  const definitions = splitTopLevel(ddl.slice(open + 1, close));
  const columns: NonNullable<RealtimeTableDraft['columns']> = [];
  let primaryKeys: string[] = [];
  definitions.forEach((definition) => {
    const primary = /^PRIMARY\s+KEY\s*\(([^)]*)\)(?:\s+NOT\s+ENFORCED)?$/i.exec(definition.trim());
    if (primary) {
      primaryKeys = splitTopLevel(primary[1]).map(unquoteIdentifier);
      return;
    }
    const column = parseColumn(definition);
    columns.push(column);
  });
  if (!columns.length) throw new Error('DDL 中至少需要一个字段');
  const knownColumns = new Set(columns.map((column) => column.name.toLowerCase()));
  primaryKeys.forEach((key) => {
    if (!knownColumns.has(key.toLowerCase())) throw new Error(`主键字段不存在：${key}`);
  });

  const suffix = ddl.slice(close + 1).trim();
  const partitions = parseIdentifierListClause(suffix, /PARTITIONED\s+BY\s*/ig);
  partitions.forEach((key) => {
    if (!knownColumns.has(key.toLowerCase())) throw new Error(`分区字段不存在：${key}`);
  });
  columns.forEach((column) => {
    column.primaryKey = primaryKeys.some((key) => key.toLowerCase() === column.name.toLowerCase());
    column.partitionKey = partitions.some((key) => key.toLowerCase() === column.name.toLowerCase());
    if (column.primaryKey) column.nullable = false;
  });

  const options = parseOptions(suffix);
  const commentMatch = /(?:^|\s)COMMENT\s+'((?:''|[^'])*)'/i.exec(suffix.replace(/WITH\s*\([\s\S]*\)\s*$/i, ''));
  const unsupported = Object.keys(options).filter((key) => !fixedOptionKeys.has(key) && !REALTIME_TABLE_OPTION_KEYS.includes(key as typeof REALTIME_TABLE_OPTION_KEYS[number]));
  if (unsupported.length) throw new Error(`暂不支持的表属性：${unsupported.join('、')}`);
  return toRealtimeTableFormValue({
    catalogName: names.length === 3 ? names[0] : 'paimon',
    databaseName: names[names.length - 2],
    tableName: names[names.length - 1],
    tableComment: commentMatch ? commentMatch[1].replace(/''/g, "'") : '',
    tableType: primaryKeys.length ? 'primary_key' : 'append_only',
    columns,
    options,
  });
}

function parseColumn(definition: string): NonNullable<RealtimeTableDraft['columns']>[number] {
  const value = definition.trim();
  const nameMatch = /^(?:`((?:``|[^`])+)`|([A-Za-z_][A-Za-z0-9_]*))\s+/.exec(value);
  if (!nameMatch) throw new Error(`无法解析字段定义：${value}`);
  const name = (nameMatch[1] || nameMatch[2]).replace(/``/g, '`');
  const remainder = value.slice(nameMatch[0].length);
  const marker = /\s+(NOT\s+NULL|NULL|COMMENT\s+')/i.exec(remainder);
  const dataType = (marker ? remainder.slice(0, marker.index) : remainder).trim();
  if (!dataType) throw new Error(`字段 ${name} 缺少数据类型`);
  const commentMatch = /\bCOMMENT\s+'((?:''|[^'])*)'/i.exec(remainder);
  return {
    name,
    dataType,
    nullable: !/\bNOT\s+NULL\b/i.test(remainder),
    primaryKey: false,
    partitionKey: false,
    comment: commentMatch ? commentMatch[1].replace(/''/g, "'") : '',
  };
}

function parseOptions(suffix: string): Record<string, string> {
  const withMatch = /\bWITH\s*\(/ig;
  const match = withMatch.exec(suffix);
  if (!match) return {};
  const open = suffix.indexOf('(', match.index);
  const close = findMatchingParenthesis(suffix, open);
  if (close < 0) throw new Error('WITH 表属性括号不完整');
  const result: Record<string, string> = {};
  splitTopLevel(suffix.slice(open + 1, close)).forEach((item) => {
    const option = /^\s*'((?:''|[^'])+)'\s*=\s*'((?:''|[^'])*)'\s*$/.exec(item);
    if (!option) throw new Error(`无法解析表属性：${item.trim()}`);
    result[option[1].replace(/''/g, "'")] = option[2].replace(/''/g, "'");
  });
  return result;
}

function parseIdentifierListClause(value: string, pattern: RegExp): string[] {
  const match = pattern.exec(value);
  if (!match) return [];
  const open = value.indexOf('(', match.index);
  const close = findMatchingParenthesis(value, open);
  if (close < 0) throw new Error('分区字段括号不完整');
  return splitTopLevel(value.slice(open + 1, close)).map(unquoteIdentifier);
}

function splitQualifiedName(value: string): string[] {
  return splitOutsideQuotes(value, '.').map(unquoteIdentifier).filter(Boolean);
}

function unquoteIdentifier(value: string): string {
  const trimmed = value.trim();
  if (trimmed.startsWith('`') && trimmed.endsWith('`')) return trimmed.slice(1, -1).replace(/``/g, '`');
  if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(trimmed)) throw new Error(`标识符格式不正确：${trimmed}`);
  return trimmed;
}

function splitTopLevel(value: string): string[] {
  const result: string[] = [];
  let start = 0; let depth = 0; let quote = '';
  for (let index = 0; index < value.length; index += 1) {
    const char = value[index];
    if (quote) {
      if (char === quote && value[index + 1] === quote) { index += 1; continue; }
      if (char === quote) quote = '';
    } else if (char === "'" || char === '`') quote = char;
    else if (char === '(') depth += 1;
    else if (char === ')') depth -= 1;
    else if (char === ',' && depth === 0) { result.push(value.slice(start, index).trim()); start = index + 1; }
  }
  const last = value.slice(start).trim(); if (last) result.push(last);
  return result;
}

function splitOutsideQuotes(value: string, delimiter: string): string[] {
  const result: string[] = []; let start = 0; let quote = '';
  for (let index = 0; index < value.length; index += 1) {
    const char = value[index];
    if (quote) {
      if (char === quote && value[index + 1] === quote) { index += 1; continue; }
      if (char === quote) quote = '';
    } else if (char === "'" || char === '`') quote = char;
    else if (char === delimiter) { result.push(value.slice(start, index)); start = index + 1; }
  }
  result.push(value.slice(start)); return result;
}

function findOutsideQuotes(value: string, target: string, from = 0): number {
  let quote = '';
  for (let index = from; index < value.length; index += 1) {
    const char = value[index];
    if (quote) {
      if (char === quote && value[index + 1] === quote) { index += 1; continue; }
      if (char === quote) quote = '';
    } else if (char === "'" || char === '`') quote = char;
    else if (char === target) return index;
  }
  return -1;
}

function findMatchingParenthesis(value: string, open: number): number {
  let depth = 0; let quote = '';
  for (let index = open; index < value.length; index += 1) {
    const char = value[index];
    if (quote) {
      if (char === quote && value[index + 1] === quote) { index += 1; continue; }
      if (char === quote) quote = '';
    } else if (char === "'" || char === '`') quote = char;
    else if (char === '(') depth += 1;
    else if (char === ')' && --depth === 0) return index;
  }
  return -1;
}

function numberOption(value?: string): number | undefined {
  if (value === undefined || value === '') return undefined;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : undefined;
}
