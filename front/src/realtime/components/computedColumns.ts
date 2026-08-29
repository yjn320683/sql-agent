import type { MysqlColumn } from '../types';

export type ComputedColumnMode = 'structured' | 'advanced';
export type OfficialComputedFunction = 'year' | 'month' | 'day' | 'hour' | 'minute' | 'second'
  | 'date_format' | 'now' | 'substring' | 'truncate' | 'cast' | 'upper' | 'lower' | 'trim';

export interface ComputedColumnDraftRow {
  mode: ComputedColumnMode;
  columnName: string;
  functionName?: OfficialComputedFunction;
  referenceColumn?: string;
  format?: string;
  begin?: string;
  end?: string;
  width?: string;
  castType?: string;
  expression: string;
}

export interface ParsedComputedColumnExpression {
  columnName: string;
  functionName: string;
  arguments: string[];
  expression: string;
}

export interface ComputedColumnValidationResult {
  expressions: string[];
  names: Set<string>;
  errors: string[];
}

export const COMPUTED_FUNCTION_OPTIONS: Array<{ value: OfficialComputedFunction; label: string }> = [
  { value: 'year', label: 'year - 年' }, { value: 'month', label: 'month - 月' },
  { value: 'day', label: 'day - 日' }, { value: 'hour', label: 'hour - 时' },
  { value: 'minute', label: 'minute - 分' }, { value: 'second', label: 'second - 秒' },
  { value: 'date_format', label: 'date_format - 日期格式化' }, { value: 'now', label: 'now - 当前时间' },
  { value: 'substring', label: 'substring - 字符串截取' }, { value: 'truncate', label: 'truncate - 截断' },
  { value: 'cast', label: 'cast - 类型转换' }, { value: 'upper', label: 'upper - 转大写' },
  { value: 'lower', label: 'lower - 转小写' }, { value: 'trim', label: 'trim - 去除首尾空格' },
];

const OFFICIAL = new Set(COMPUTED_FUNCTION_OPTIONS.map((item) => item.value));
const IDENTIFIER = /^[A-Za-z_][A-Za-z0-9_]*$/;
const INTEGER = /^\d+$/;
const CAST_TYPE = /^[A-Za-z][A-Za-z0-9_]*(?:\s+[A-Za-z][A-Za-z0-9_]*)*(?:\(\s*\d+\s*(?:,\s*\d+\s*)?\))?$/;
const TEMPORAL = new Set<OfficialComputedFunction>(['year', 'month', 'day', 'hour', 'minute', 'second', 'date_format']);
const EXTRACT = new Set<OfficialComputedFunction>(['year', 'month', 'day', 'hour', 'minute', 'second']);
const STRING = new Set<OfficialComputedFunction>(['substring', 'upper', 'lower', 'trim']);

const splitArguments = (value: string): string[] | undefined => {
  if (!value.trim()) return [];
  const result: string[] = [];
  let start = 0; let depth = 0; let quote = '';
  for (let index = 0; index < value.length; index += 1) {
    const character = value[index];
    if (quote) { if (character === quote && value[index - 1] !== '\\') quote = ''; continue; }
    if (character === "'" || character === '"') quote = character;
    else if (character === '(') depth += 1;
    else if (character === ')') { depth -= 1; if (depth < 0) return undefined; }
    else if (character === ',' && depth === 0) {
      const argument = value.slice(start, index).trim(); if (!argument) return undefined;
      result.push(argument); start = index + 1;
    }
  }
  const last = value.slice(start).trim();
  if (depth !== 0 || quote || !last) return undefined;
  result.push(last); return result;
};

const balanced = (value: string, open: number) => {
  let depth = 0; let quote = '';
  for (let index = open; index < value.length; index += 1) {
    const character = value[index];
    if (quote) { if (character === quote && value[index - 1] !== '\\') quote = ''; continue; }
    if (character === "'" || character === '"') quote = character;
    else if (character === '(') depth += 1;
    else if (character === ')') { depth -= 1; if (depth < 0 || (depth === 0 && index !== value.length - 1)) return false; }
  }
  return depth === 0 && !quote;
};

export const parseComputedColumnExpression = (raw?: string): ParsedComputedColumnExpression | undefined => {
  const expression = (raw ?? '').trim(); const equals = expression.indexOf('=');
  if (equals <= 0) return undefined;
  const columnName = expression.slice(0, equals).trim(); const call = expression.slice(equals + 1).trim();
  const open = call.indexOf('(');
  if (open <= 0 || !call.endsWith(')') || !balanced(call, open)) return undefined;
  const functionName = call.slice(0, open).trim();
  if (!IDENTIFIER.test(columnName) || !IDENTIFIER.test(functionName)) return undefined;
  const args = splitArguments(call.slice(open + 1, -1));
  return args ? { columnName, functionName, arguments: args, expression } : undefined;
};

export const computedColumnName = (expression?: string) => parseComputedColumnExpression(expression)?.columnName;

export const computedExpressionToDraft = (expression?: string): ComputedColumnDraftRow => {
  const value = (expression ?? '').trim(); const parsed = parseComputedColumnExpression(value);
  if (!parsed || !OFFICIAL.has(parsed.functionName as OfficialComputedFunction)) {
    return { mode: 'advanced', columnName: parsed?.columnName ?? '', expression: value };
  }
  const functionName = parsed.functionName as OfficialComputedFunction;
  const [referenceColumn, second, third] = parsed.arguments;
  const base = { mode: 'structured' as const, columnName: parsed.columnName, functionName, referenceColumn, expression: value };
  if (functionName === 'now' && parsed.arguments.length === 0) return { ...base, referenceColumn: undefined };
  if ((EXTRACT.has(functionName) || ['upper', 'lower', 'trim'].includes(functionName)) && parsed.arguments.length === 1) return base;
  if (functionName === 'date_format' && parsed.arguments.length === 2) return { ...base, format: second };
  if (functionName === 'substring' && parsed.arguments.length >= 2 && parsed.arguments.length <= 3 && INTEGER.test(second) && (!third || INTEGER.test(third))) return { ...base, begin: second, end: third };
  if (functionName === 'truncate' && parsed.arguments.length === 2 && INTEGER.test(second)) return { ...base, width: second };
  if (functionName === 'cast' && parsed.arguments.length === 2) return { ...base, castType: second };
  return { mode: 'advanced', columnName: parsed.columnName, expression: value };
};

export const emptyComputedColumnDraft = (): ComputedColumnDraftRow => ({ mode: 'structured', columnName: '', expression: '' });

export const buildStructuredComputedExpression = (row: ComputedColumnDraftRow) => {
  const name = row.columnName.trim(); const func = row.functionName;
  if (!name || !func) return '';
  if (func === 'now') return `${name}=now()`;
  const reference = row.referenceColumn?.trim(); if (!reference) return '';
  if (func === 'date_format') return row.format?.trim() ? `${name}=date_format(${reference},${row.format.trim()})` : '';
  if (func === 'substring') return row.begin === undefined || row.begin === '' ? '' : `${name}=substring(${[reference, row.begin, row.end].filter((item) => item !== undefined && item !== '').join(',')})`;
  if (func === 'truncate') return row.width ? `${name}=truncate(${reference},${row.width})` : '';
  if (func === 'cast') return row.castType?.trim() ? `${name}=cast(${reference},${row.castType.trim()})` : '';
  return `${name}=${func}(${reference})`;
};

const normalizedType = (type?: string) => (type ?? '').trim().toLowerCase();
const isString = (type?: string) => /(char|text|string|json|enum|set)/.test(normalizedType(type));
const isInteger = (type?: string) => /(tinyint|smallint|mediumint|integer|bigint|\bint\b)/.test(normalizedType(type));
const isDecimal = (type?: string) => /(decimal|numeric|float|double|real)/.test(normalizedType(type));
const isTemporal = (type?: string) => /(date|datetime|timestamp|time|year)/.test(normalizedType(type));

export const isFunctionCompatibleWithColumn = (functionName: OfficialComputedFunction, column?: MysqlColumn) => {
  if (functionName === 'now' || !column) return true;
  if (TEMPORAL.has(functionName)) return isTemporal(column.type) || isString(column.type) || isInteger(column.type);
  if (STRING.has(functionName)) return isString(column.type);
  if (functionName === 'truncate') return isString(column.type) || isInteger(column.type) || isDecimal(column.type);
  return true;
};

const quoted = (value?: string) => Boolean(value && /^(['"]).*\1$/.test(value.trim()));

const validateStructuredRow = (row: ComputedColumnDraftRow, columnsByName: Map<string, MysqlColumn>) => {
  const name = row.columnName.trim();
  if (!name) return '缺少计算列名，请填写：create_date=date_format(create_time,yyyyMMdd)';
  if (!IDENTIFIER.test(name)) return `计算列名称格式错误：${name}`;
  if (!row.functionName) return `计算列 ${name} 请选择函数`;
  if (row.functionName === 'now') return undefined;
  if (!row.referenceColumn) return `计算列 ${name} 请选择引用源字段`;
  const sourceColumn = columnsByName.get(row.referenceColumn);
  if (!sourceColumn) return `计算列引用字段不存在：${row.referenceColumn}`;
  if (!isFunctionCompatibleWithColumn(row.functionName, sourceColumn)) {
    return `${row.functionName} 不支持源字段类型：${row.referenceColumn}[${sourceColumn.type}]`;
  }
  if (row.functionName === 'date_format') {
    if (!row.format?.trim()) return 'date_format 缺少格式参数';
    if (quoted(row.format)) return 'date_format 格式参数不需要单引号或双引号';
  }
  if (row.functionName === 'substring') {
    if (!INTEGER.test(row.begin ?? '')) return 'substring 开始位置必须是非负整数';
    if (row.end && !INTEGER.test(row.end)) return 'substring 结束位置必须是非负整数';
    if (row.end && Number(row.end) <= Number(row.begin)) return 'substring 结束位置必须大于开始位置';
  }
  if (row.functionName === 'truncate' && (!INTEGER.test(row.width ?? '') || Number(row.width) <= 0)) {
    return 'truncate 宽度必须是正整数';
  }
  if (row.functionName === 'cast') {
    if (!row.castType?.trim()) return 'cast 目标数据类型不能为空';
    if (!CAST_TYPE.test(row.castType.trim())) return `cast 目标数据类型格式错误：${row.castType}`;
  }
  return undefined;
};

const validateAdvancedRow = (row: ComputedColumnDraftRow, columnsByName: Map<string, MysqlColumn>) => {
  const expression = row.expression.trim();
  if (!expression) return { error: '高级表达式不能为空' };
  const equals = expression.indexOf('=');
  if (equals < 0 && /^[A-Za-z_][A-Za-z0-9_]*\s*\(/.test(expression)) {
    return { error: `缺少计算列名，请填写：create_date=${expression}` };
  }
  if (equals === 0) return { error: `缺少计算列名，请填写：create_date=${expression.slice(1).trim() || 'date_format(create_time,yyyyMMdd)'}` };
  if (equals > 0) {
    const name = expression.slice(0, equals).trim();
    if (!IDENTIFIER.test(name)) return { error: `计算列名称格式错误：${name}` };
    const open = expression.indexOf('(', equals + 1);
    if (open > equals + 1) {
      const functionName = expression.slice(equals + 1, open).trim();
      if (!IDENTIFIER.test(functionName)) return { error: `计算列函数名称格式错误：${functionName}` };
    }
  }
  const parsed = parseComputedColumnExpression(expression);
  if (!parsed) return { error: `计算列表达式格式错误：${expression}` };
  if (parsed.functionName === 'date_format' && quoted(parsed.arguments[1])) {
    return { error: 'date_format 格式参数不需要单引号或双引号' };
  }
  if (parsed.arguments.length > 0 && !columnsByName.has(parsed.arguments[0])) {
    return { error: `计算列引用字段不存在：${parsed.arguments[0]}` };
  }
  return { parsed };
};

export const validateComputedColumnRows = (rows: ComputedColumnDraftRow[], columns: MysqlColumn[]): ComputedColumnValidationResult => {
  const byName = new Map(columns.map((column) => [column.name, column]));
  const names = new Set<string>(); const expressions: string[] = []; const errors: string[] = [];
  rows.forEach((row, index) => {
    let name = row.columnName.trim(); let expression = row.expression.trim(); let error: string | undefined;
    if (row.mode === 'structured') {
      error = validateStructuredRow(row, byName);
      expression = buildStructuredComputedExpression(row);
    } else {
      const advanced = validateAdvancedRow(row, byName);
      error = advanced.error;
      name = advanced.parsed?.columnName ?? name;
      expression = advanced.parsed?.expression ?? expression;
    }
    if (!error && byName.has(name)) error = `计算列名称与源字段重复：${name}`;
    if (!error && names.has(name)) error = `计算列名称不能重复：${name}`;
    if (error) errors[index] = error;
    else { names.add(name); expressions.push(expression); }
  });
  return { names, expressions, errors };
};
