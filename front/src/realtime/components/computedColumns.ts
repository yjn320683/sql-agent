import type { MysqlColumn } from "../types";

export type ComputedColumnMode = "structured" | "advanced";

export type OfficialComputedFunction =
  | "year"
  | "month"
  | "day"
  | "hour"
  | "minute"
  | "second"
  | "date_format"
  | "now"
  | "substring"
  | "truncate"
  | "cast"
  | "upper"
  | "lower"
  | "trim";

export interface ComputedColumnDraftRow {
  mode: ComputedColumnMode;
  columnName: string;
  functionName?: OfficialComputedFunction;
  referenceColumn?: string;
  format?: string;
  temporalPrecision?: string;
  begin?: string;
  end?: string;
  width?: string;
  literalValue?: string;
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
  inputColumns: Map<string, MysqlColumn>[];
}

export const COMPUTED_FUNCTION_OPTIONS: Array<{
  value: OfficialComputedFunction;
  label: string;
}> = [
  { value: "year", label: "year - 年" },
  { value: "month", label: "month - 月" },
  { value: "day", label: "day - 日" },
  { value: "hour", label: "hour - 时" },
  { value: "minute", label: "minute - 分" },
  { value: "second", label: "second - 秒" },
  { value: "date_format", label: "date_format - 日期格式化" },
  { value: "now", label: "now - 当前时间" },
  { value: "substring", label: "substring - 字符串截取" },
  { value: "truncate", label: "truncate - 截断" },
  { value: "cast", label: "cast - 指定类型常量" },
  { value: "upper", label: "upper - 转大写" },
  { value: "lower", label: "lower - 转小写" },
  { value: "trim", label: "trim - 去除首尾空格" },
];

// Paimon 1.4.2 的计算列解析器以第一个右括号结束表达式，故这里只提供无参数的原子类型。
export const CAST_TYPE_OPTIONS = [
  "STRING", "BOOLEAN", "TINYINT", "SMALLINT", "INT", "BIGINT",
  "FLOAT", "DOUBLE", "DECIMAL", "DATE", "TIME", "TIMESTAMP", "TIMESTAMP_LTZ",
].map((value) => ({ value, label: value }));
const CAST_TYPES = new Set(CAST_TYPE_OPTIONS.map(({ value }) => value));

const OFFICIAL_FUNCTIONS = new Set<OfficialComputedFunction>(
  COMPUTED_FUNCTION_OPTIONS.map((option) => option.value),
);
const IDENTIFIER_PATTERN = /^[A-Za-z_][A-Za-z0-9_]*$/;
const INTEGER_PATTERN = /^\d+$/;
const TEMPORAL_PRECISIONS = new Set(["0", "3", "6", "9"]);
const TEMPORAL_FUNCTIONS = new Set<OfficialComputedFunction>([
  "year", "month", "day", "hour", "minute", "second", "date_format",
]);
const TEMPORAL_EXTRACT_FUNCTIONS = new Set<OfficialComputedFunction>([
  "year", "month", "day", "hour", "minute", "second",
]);
const STRING_FUNCTIONS = new Set<OfficialComputedFunction>(["upper", "lower", "trim"]);
const NULL_PROPAGATING_FUNCTIONS = new Set<OfficialComputedFunction>([
  "year", "month", "day", "hour", "minute", "second", "date_format",
  "substring", "truncate", "upper", "lower", "trim",
]);

export interface ComputedColumnKeySafety {
  safe: boolean;
  reason?: string;
}

const splitArguments = (value: string): string[] | undefined => {
  if (!value.trim()) return [];
  const result: string[] = [];
  let start = 0;
  let depth = 0;
  let quote = "";
  for (let index = 0; index < value.length; index += 1) {
    const character = value[index];
    if (quote) {
      if (character === quote && value[index - 1] !== "\\") quote = "";
      continue;
    }
    if (character === "'" || character === '"') {
      quote = character;
    } else if (character === "(") {
      depth += 1;
    } else if (character === ")") {
      depth -= 1;
      if (depth < 0) return undefined;
    } else if (character === "," && depth === 0) {
      const argument = value.slice(start, index).trim();
      if (!argument) return undefined;
      result.push(argument);
      start = index + 1;
    }
  }
  if (depth !== 0 || quote) return undefined;
  const last = value.slice(start).trim();
  if (!last) return undefined;
  result.push(last);
  return result;
};

const hasBalancedFunctionCall = (value: string, openIndex: number) => {
  let depth = 0;
  let quote = "";
  for (let index = openIndex; index < value.length; index += 1) {
    const character = value[index];
    if (quote) {
      if (character === quote && value[index - 1] !== "\\") quote = "";
      continue;
    }
    if (character === "'" || character === '"') {
      quote = character;
    } else if (character === "(") {
      depth += 1;
    } else if (character === ")") {
      depth -= 1;
      if (depth === 0 && index !== value.length - 1) return false;
      if (depth < 0) return false;
    }
  }
  return depth === 0 && !quote;
};

export const parseComputedColumnExpression = (
  rawExpression?: string,
): ParsedComputedColumnExpression | undefined => {
  const expression = (rawExpression ?? "").trim();
  const equals = expression.indexOf("=");
  if (equals <= 0) return undefined;
  const columnName = expression.slice(0, equals).trim();
  const functionExpression = expression.slice(equals + 1).trim();
  const open = functionExpression.indexOf("(");
  if (open <= 0 || !functionExpression.endsWith(")") || !hasBalancedFunctionCall(functionExpression, open)) {
    return undefined;
  }
  const functionName = functionExpression.slice(0, open).trim();
  if (!IDENTIFIER_PATTERN.test(columnName) || !IDENTIFIER_PATTERN.test(functionName)) return undefined;
  const argumentsValue = functionExpression.slice(open + 1, -1);
  const args = splitArguments(argumentsValue);
  if (!args) return undefined;
  return { columnName, functionName, arguments: args, expression };
};

export const computedColumnName = (expression?: string) => (
  parseComputedColumnExpression(expression)?.columnName
);

export const computedColumnKeySafety = (
  expression: string,
  columns: MysqlColumn[],
  knownNonNullFields: string[] = [],
  expressions: string[] = [],
  visiting = new Set<string>(),
): ComputedColumnKeySafety => {
  const parsed = parseComputedColumnExpression(expression);
  if (!parsed) return { safe: false, reason: `计算列表达式 ${expression} 无法证明结果非空` };
  if (visiting.has(parsed.columnName)) return { safe: false, reason: `计算列 ${parsed.columnName} 存在循环依赖` };
  visiting.add(parsed.columnName);
  if (parsed.functionName === "now" && parsed.arguments.length === 0) return { safe: true };
  if (parsed.functionName === "cast" && parsed.arguments.length >= 1
      && parsed.arguments.length <= 2 && parsed.arguments[0].trim()) return { safe: true };
  if (!NULL_PROPAGATING_FUNCTIONS.has(parsed.functionName as OfficialComputedFunction)) {
    return { safe: false, reason: `计算列 ${expression} 无法证明结果非空` };
  }
  const referenceField = parsed.arguments[0];
  const sourceColumn = columns.find((column) => column.name === referenceField);
  if (!sourceColumn) {
    const dependency = expressions.find((value) => computedColumnName(value) === referenceField);
    if (dependency) {
      return computedColumnKeySafety(dependency, columns, knownNonNullFields, expressions, visiting);
    }
  }
  const sourceIsNonNull = sourceColumn?.nullable === false
    || knownNonNullFields.includes(referenceField);
  return sourceIsNonNull
    ? { safe: true }
    : {
      safe: false,
      reason: `计算列 ${expression} 引用字段 ${referenceField} 允许 NULL，结果可能为空`,
    };
};

const officialFunction = (value: string): value is OfficialComputedFunction => (
  OFFICIAL_FUNCTIONS.has(value as OfficialComputedFunction)
);

const parsedOfficialDraft = (
  parsed: ParsedComputedColumnExpression,
): ComputedColumnDraftRow | undefined => {
  if (!officialFunction(parsed.functionName)) return undefined;
  const [referenceColumn, second, third] = parsed.arguments;
  const common = {
    mode: "structured" as const,
    columnName: parsed.columnName,
    functionName: parsed.functionName,
    referenceColumn,
    expression: parsed.expression,
  };
  if (parsed.functionName === "now") {
    return parsed.arguments.length === 0 ? { ...common, referenceColumn: undefined } : undefined;
  }
  if (TEMPORAL_EXTRACT_FUNCTIONS.has(parsed.functionName)) {
    return parsed.arguments.length >= 1 && parsed.arguments.length <= 2
      ? { ...common, temporalPrecision: second } : undefined;
  }
  if (["upper", "lower", "trim"].includes(parsed.functionName)) {
    return parsed.arguments.length === 1 ? common : undefined;
  }
  if (parsed.functionName === "date_format") {
    return parsed.arguments.length >= 2 && parsed.arguments.length <= 3
      ? { ...common, format: second, temporalPrecision: third } : undefined;
  }
  if (parsed.functionName === "substring") {
    if (parsed.arguments.length < 2 || parsed.arguments.length > 3 || !INTEGER_PATTERN.test(second)) return undefined;
    if (third !== undefined && !INTEGER_PATTERN.test(third)) return undefined;
    return { ...common, begin: second, end: third };
  }
  if (parsed.functionName === "truncate") {
    return parsed.arguments.length === 2 && INTEGER_PATTERN.test(second)
      ? { ...common, width: second }
      : undefined;
  }
  if (parsed.functionName === "cast") {
    return (parsed.arguments.length === 1 || parsed.arguments.length === 2)
      && referenceColumn.trim() && (!second || CAST_TYPES.has(second))
      ? { ...common, referenceColumn: undefined, literalValue: referenceColumn, castType: second }
      : undefined;
  }
  return undefined;
};

export const computedExpressionToDraft = (expression?: string): ComputedColumnDraftRow => {
  const value = (expression ?? "").trim();
  const parsed = parseComputedColumnExpression(value);
  const structured = parsed ? parsedOfficialDraft(parsed) : undefined;
  return structured ?? {
    mode: "structured",
    columnName: parsed?.columnName ?? "",
    expression: value,
  };
};

export const emptyComputedColumnDraft = (): ComputedColumnDraftRow => ({
  mode: "structured",
  columnName: "",
  expression: "",
});

export const buildStructuredComputedExpression = (row: ComputedColumnDraftRow) => {
  const name = row.columnName.trim();
  const func = row.functionName;
  if (!name || !func) return "";
  if (func === "now") return `${name}=now()`;
  if (func === "cast") {
    const literal = row.literalValue?.trim();
    if (!literal) return "";
    return `${name}=cast(${literal}${row.castType ? `,${row.castType}` : ""})`;
  }
  const reference = row.referenceColumn?.trim();
  if (!reference) return "";
  const precision = row.temporalPrecision === undefined || row.temporalPrecision === ""
    ? "" : `,${row.temporalPrecision}`;
  if (func === "date_format") {
    const format = row.format?.trim();
    if (!format) return "";
    return `${name}=date_format(${reference},${format}${precision})`;
  }
  if (func === "substring") {
    if (row.begin === undefined || row.begin === "") return "";
    return `${name}=substring(${[reference, row.begin, row.end].filter((value) => value !== undefined && value !== "").join(",")})`;
  }
  if (func === "truncate") {
    return row.width ? `${name}=truncate(${reference},${row.width})` : "";
  }
  if (TEMPORAL_EXTRACT_FUNCTIONS.has(func)) {
    return `${name}=${func}(${reference}${precision})`;
  }
  return `${name}=${func}(${reference})`;
};

const normalizedType = (type?: string) => (type ?? "").trim().toLowerCase();
const mysqlBaseType = (type?: string) => normalizedType(type).match(/^[a-z]+/)?.[0] ?? "";
const isOversizedDecimal = (type?: string) => {
  const base = mysqlBaseType(type);
  if (!["decimal", "numeric", "fixed"].includes(base)) return false;
  const precision = normalizedType(type).match(/^[a-z]+\s*\(\s*(\d+)/)?.[1];
  return precision !== undefined && Number(precision) > 38;
};
const isStringType = (type?: string, mappings: string[] = []) => {
  if (mappings.includes("to-string")) return true;
  const base = mysqlBaseType(type);
  if (isOversizedDecimal(type)) return true;
  if (base === "char") return mappings.includes("char-to-string") || !/\(\s*[1-9]/.test(normalizedType(type));
  if (base === "longtext") return !mappings.includes("longtext-to-bytes");
  return ["varchar", "tinytext", "text", "mediumtext", "string", "json", "enum",
    "geometry", "point", "linestring", "polygon", "multipoint", "multilinestring",
    "multipolygon", "geometrycollection"].includes(base);
};
const isIntegerType = (type?: string, mappings: string[] = []) => {
  const base = mysqlBaseType(type);
  if (base === "tinyint" && /^tinyint\s*\(\s*1\s*\)/.test(normalizedType(type))
      && !mappings.includes("tinyint1-not-bool")) return false;
  if (base === "bigint" && /unsigned/.test(normalizedType(type))
      && !mappings.includes("bigint-unsigned-to-bigint")) return false;
  return ["tinyint", "smallint", "mediumint", "int", "integer", "bigint", "long", "year"].includes(base);
};
const isDecimalType = (type?: string, mappings: string[] = []) => {
  const base = mysqlBaseType(type);
  return (["decimal", "numeric", "fixed"].includes(base) && !isOversizedDecimal(type))
    || base === "serial"
    || (base === "bigint" && /unsigned/.test(normalizedType(type))
      && !mappings.includes("bigint-unsigned-to-bigint"));
};
const isTemporalType = (type?: string) => ["date", "datetime", "timestamp"].includes(mysqlBaseType(type));
export const isTemporalPrecisionCompatibleWithColumn = (column?: MysqlColumn, mappings: string[] = []) => (
  Boolean(column && (isIntegerType(column.type, mappings) || isStringType(column.type, mappings)))
);

export const isFunctionCompatibleWithColumn = (
  functionName: OfficialComputedFunction,
  column?: MysqlColumn,
  typeMappings: string[] = [],
) => {
  if (functionName === "now" || functionName === "cast" || !column) return true;
  if (TEMPORAL_FUNCTIONS.has(functionName)) {
    return isTemporalType(column.type) || isStringType(column.type, typeMappings)
      || isIntegerType(column.type, typeMappings);
  }
  if (STRING_FUNCTIONS.has(functionName)) return isStringType(column.type, typeMappings);
  if (functionName === "truncate") {
    return isStringType(column.type, typeMappings)
      || (mysqlBaseType(column.type) === "char" && !typeMappings.includes("to-string"))
      || isIntegerType(column.type, typeMappings) || isDecimalType(column.type, typeMappings);
  }
  // Paimon 1.4.2 的 substring 直接处理 CDC 序列化后的字符串，不限定字段的 Paimon 类型。
  return true;
};

const quoted = (value?: string) => Boolean(value && /^(['"]).*\1$/.test(value.trim()));

const validateStructuredRow = (
  row: ComputedColumnDraftRow,
  columnsByName: Map<string, MysqlColumn>,
  typeMappings: string[],
): string | undefined => {
  const name = row.columnName.trim();
  if (!row.functionName && row.expression.trim()) {
    return validateAdvancedRow(row, columnsByName, typeMappings).error
      ?? `不支持计算列表达式：${row.expression.trim()}`;
  }
  if (!name) return "缺少计算列名，请填写：create_date=date_format(create_time,yyyyMMdd)";
  if (!IDENTIFIER_PATTERN.test(name)) return `计算列名称格式错误：${name}`;
  if (!row.functionName) return `计算列 ${name} 请选择函数`;
  if (row.functionName === "now") return undefined;
  if (row.functionName === "cast") {
    if (!row.literalValue?.trim()) return "cast 常量值不能为空";
    if (/[(),]/.test(row.literalValue)) return "cast 常量值不能包含逗号或括号";
    if (row.castType && !CAST_TYPES.has(row.castType)) return `cast 不支持目标数据类型：${row.castType}`;
    return undefined;
  }
  if (!row.referenceColumn) return `计算列 ${name} 请选择引用源字段`;
  const sourceColumn = columnsByName.get(row.referenceColumn);
  if (!sourceColumn) return `计算列引用字段不存在：${row.referenceColumn}`;
  if (!isFunctionCompatibleWithColumn(row.functionName, sourceColumn, typeMappings)) {
    return `${row.functionName} 不支持源字段类型：${row.referenceColumn}[${sourceColumn.type}]`;
  }
  if (TEMPORAL_FUNCTIONS.has(row.functionName) && row.temporalPrecision !== undefined
      && row.temporalPrecision !== "") {
    if (!TEMPORAL_PRECISIONS.has(row.temporalPrecision)) return `${row.functionName} 精度只支持 0、3、6、9`;
    if (!isTemporalPrecisionCompatibleWithColumn(sourceColumn, typeMappings)) {
      return `${row.functionName} 精度仅适用于整数时间戳或数字字符串`;
    }
  }
  if (row.functionName === "date_format") {
    if (!row.format?.trim()) return "date_format 缺少格式参数";
    if (row.format.includes("/")) return "date_format 日期格式不能包含 /，请使用 - 或无分隔符格式";
    if (quoted(row.format)) return "date_format 格式参数不需要单引号或双引号";
    if (row.format.includes(",")) return "date_format 格式参数不能包含逗号";
  }
  if (row.functionName === "substring") {
    if (!INTEGER_PATTERN.test(row.begin ?? "")) return "substring 开始位置必须是非负整数";
    if (Number(row.begin) > 2147483647) return "substring 开始位置超出 INT 范围";
    if (row.end && !INTEGER_PATTERN.test(row.end)) return "substring 结束位置必须是非负整数";
    if (row.end && Number(row.end) > 2147483647) return "substring 结束位置超出 INT 范围";
    if (row.end && Number(row.end) <= Number(row.begin)) return "substring 结束位置必须大于开始位置";
  }
  if (row.functionName === "truncate") {
    if (!INTEGER_PATTERN.test(row.width ?? "") || Number(row.width) <= 0) return "truncate 宽度必须是正整数";
    if (Number(row.width) > 2147483647) return "truncate 宽度超出 INT 范围";
  }
  return undefined;
};

const missingNameExample = (expression: string) => {
  const value = expression.trim();
  return value || "date_format(create_time,yyyyMMdd)";
};

const validateAdvancedRow = (
  row: ComputedColumnDraftRow,
  columnsByName: Map<string, MysqlColumn>,
  typeMappings: string[],
): { error?: string; parsed?: ParsedComputedColumnExpression } => {
  const expression = row.expression.trim();
  if (!expression) return { error: "高级表达式不能为空" };
  const equals = expression.indexOf("=");
  if (equals < 0 && /^[A-Za-z_][A-Za-z0-9_]*\s*\(/.test(expression)) {
    return { error: `缺少计算列名，请填写：create_date=${missingNameExample(expression)}` };
  }
  if (equals === 0) {
    return { error: `缺少计算列名，请填写：create_date=${missingNameExample(expression.slice(1))}` };
  }
  if (equals > 0) {
    const name = expression.slice(0, equals).trim();
    if (!IDENTIFIER_PATTERN.test(name)) return { error: `计算列名称格式错误：${name}` };
    const open = expression.indexOf("(", equals + 1);
    if (open > equals + 1) {
      const functionName = expression.slice(equals + 1, open).trim();
      if (!IDENTIFIER_PATTERN.test(functionName)) {
        return { error: `计算列函数名称格式错误：${functionName}` };
      }
    }
  }
  const parsed = parseComputedColumnExpression(expression);
  if (!parsed) return { error: `计算列表达式格式错误：${expression}` };
  if (!officialFunction(parsed.functionName)) return { error: `不支持计算列函数：${parsed.functionName}` };
  if (parsed.functionName === "date_format" && quoted(parsed.arguments[1])) {
    return { error: "date_format 格式参数不需要单引号或双引号" };
  }
  if (parsed.functionName === "cast") {
    if (parsed.arguments.length < 1 || parsed.arguments.length > 2 || !parsed.arguments[0].trim()) {
      return { error: "cast 需要常量值，可选目标数据类型" };
    }
    if (/[(),]/.test(parsed.arguments[0])) return { error: "cast 常量值不能包含逗号或括号" };
    if (parsed.arguments[1] && !CAST_TYPES.has(parsed.arguments[1])) {
      return { error: `cast 不支持目标数据类型：${parsed.arguments[1]}` };
    }
  }
  if (parsed.functionName !== "cast" && parsed.arguments.length > 0) {
    const sourceColumn = columnsByName.get(parsed.arguments[0]);
    if (!sourceColumn) return { error: `计算列引用字段不存在：${parsed.arguments[0]}` };
    if (officialFunction(parsed.functionName)
        && !isFunctionCompatibleWithColumn(parsed.functionName, sourceColumn, typeMappings)) {
      return { error: `${parsed.functionName} 不支持源字段类型：${sourceColumn.name}[${sourceColumn.type}]` };
    }
  }
  // 高级模式只改变输入方式，参数与类型规则复用普通模式。
  const structured = parsedOfficialDraft(parsed);
  if (!structured) return { error: `${parsed.functionName} 参数数量或格式错误` };
  return { error: validateStructuredRow(structured, columnsByName, typeMappings), parsed };
};

export const validateComputedColumnRows = (
  rows: ComputedColumnDraftRow[],
  columns: MysqlColumn[],
  typeMappings: string[] = [],
): ComputedColumnValidationResult => {
  const columnsByName = new Map(columns.map((column) => [column.name, column]));
  const physicalNames = new Set(columnsByName.keys());
  const names = new Set<string>();
  const expressions: string[] = [];
  const errors: string[] = [];
  const inputColumns: Map<string, MysqlColumn>[] = [];
  rows.forEach((row, index) => {
    inputColumns[index] = new Map(columnsByName);
    let name = row.columnName.trim();
    let expression = row.expression.trim();
    let error: string | undefined;
    if (row.mode === "structured") {
      error = validateStructuredRow(row, columnsByName, typeMappings);
      expression = buildStructuredComputedExpression(row);
      if (!error) {
        // 弹窗生成的表达式也必须通过任务保存时的解析校验。
        error = validateAdvancedRow({ ...row, expression }, columnsByName, typeMappings).error;
      }
    } else {
      const advanced = validateAdvancedRow(row, columnsByName, typeMappings);
      error = advanced.error;
      name = advanced.parsed?.columnName ?? name;
      expression = advanced.parsed?.expression ?? expression;
    }
    if (!error && physicalNames.has(name)) error = `计算列名称与源字段重复：${name}`;
    if (!error && names.has(name)) error = `计算列名称不能重复：${name}`;
    if (error) {
      errors[index] = error;
      return;
    }
    names.add(name);
    expressions.push(expression);
    // 后续计算列可引用前面的计算结果；分区兜底在全部求值后才执行。
    const parsed = parseComputedColumnExpression(expression);
    if (parsed) {
      const reference = columnsByName.get(parsed.arguments[0]);
      const resultType = parsed.functionName === "cast" ? (parsed.arguments[1] ?? "STRING")
        : parsed.functionName === "now" ? "timestamp"
          : TEMPORAL_EXTRACT_FUNCTIONS.has(parsed.functionName as OfficialComputedFunction) ? "int"
            : parsed.functionName === "truncate" ? (reference?.type ?? "unknown") : "string";
      columnsByName.set(name, { name, type: resultType,
        nullable: !(parsed.functionName === "cast" || parsed.functionName === "now" || reference?.nullable === false) });
    }
  });
  return { expressions, names, errors, inputColumns };
};
