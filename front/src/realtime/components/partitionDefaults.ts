import type { TablePrivateConfig, MysqlColumn, MysqlTableSchema } from "../types";
import { parseComputedColumnExpression } from "./computedColumns";
import { datePartitionError, recommendDatePartition } from "./partitionDateFormat";

export interface PartitionField { type: string; allowed: boolean; deterministic: boolean; format?: string; reason?: string }
const allowedTypes = new Set(["DATE", "TINYINT", "SMALLINT", "INT", "BIGINT", "STRING", "CHAR", "VARCHAR"]);
const baseType = (type: string) => type.trim().toUpperCase().split(/[ (]/)[0];
export const partitionSourceType = (column: MysqlColumn, mappings: string[]): string => {
  const raw = column.type.toUpperCase();
  const base = baseType(raw);
  if (mappings.includes("to-string")) return "STRING";
  const unsigned = /UNSIGNED|ZEROFILL/.test(raw);
  if (base === "TINYINT") return !unsigned && /TINYINT\s*\(1\)/.test(raw) && !mappings.includes("tinyint1-not-bool")
    ? "BOOLEAN" : unsigned ? "SMALLINT" : "TINYINT";
  if (base === "SMALLINT") return unsigned ? "INT" : "SMALLINT";
  if (["INT", "INTEGER"].includes(base)) return unsigned ? "BIGINT" : "INT";
  if (base === "MEDIUMINT") return unsigned ? "BIGINT" : "INT";
  if (base === "YEAR") return "INT";
  if (base === "BIGINT") return unsigned && !mappings.includes("bigint-unsigned-to-bigint") ? "DECIMAL" : "BIGINT";
  if (["CHAR", "VARCHAR"].includes(base)) return mappings.includes("char-to-string") ? "STRING" : raw.replace(/\s+.*$/, "");
  if (base === "LONGTEXT" && mappings.includes("longtext-to-bytes")) return "BYTES";
  if (["TINYTEXT", "TEXT", "MEDIUMTEXT", "LONGTEXT", "JSON", "ENUM"].includes(base)) return "STRING";
  return base;
};

export const partitionField = (schema: MysqlTableSchema | undefined, expressions: string[], key: string,
  mappings: string[] = [], visiting = new Set<string>()): PartitionField => {
  if (visiting.has(key)) return { type: "未知", allowed: false, deterministic: false, reason: "计算列循环依赖" };
  visiting.add(key);
  const parsed = expressions.map(parseComputedColumnExpression).find((item) => item?.columnName === key);
  let type = "未知";
  let deterministic = true;
  let directAllowed = true;
  let format: string | undefined;
  let reason: string | undefined;
  if (parsed) {
    const func = parsed.functionName;
    const reference = func === "cast" || func === "now" ? undefined
      : partitionField(schema, expressions, parsed.arguments[0], mappings, visiting);
    deterministic = func !== "now" && (reference?.deterministic ?? true);
    if (!deterministic) {
      reason = func === "now"
        ? "now() 的结果随执行时间变化，请使用源表时间字段生成分区值"
        : reference?.reason;
    }
    if (["year", "month", "day", "hour", "minute", "second"].includes(func)) type = "INT";
    if (["date_format", "substring", "upper", "lower", "trim"].includes(func)) type = "STRING";
    if (func === "truncate") type = reference?.type ?? "未知";
    if (func === "cast") type = parsed.arguments[1] ?? "STRING";
    if (func === "now") type = "TIMESTAMP";
    if (func === "date_format") format = parsed.arguments[1];
  } else {
    const column = schema?.columns.find((item) => item.name === key);
    if (column) {
      type = partitionSourceType(column, mappings);
      directAllowed = ["DATE", "TINYINT", "SMALLINT", "MEDIUMINT", "INT", "INTEGER", "BIGINT", "YEAR",
        "CHAR", "VARCHAR", "TINYTEXT", "TEXT", "MEDIUMTEXT", "LONGTEXT"].includes(baseType(column.type));
      if (!directAllowed) reason = `源字段 ${key} 的类型 ${column.type} 不支持直接作为分区键，请通过计算列转换为日期、整数或字符类型`;
    }
  }
  const allowed = directAllowed && deterministic && allowedTypes.has(baseType(type));
  return { type, deterministic, allowed, format,
    reason: allowed ? undefined : reason ?? (type === "未知"
      ? `无法确定字段 ${key} 的类型，请检查字段或计算列表达式`
      : `字段 ${key} 的类型 ${type} 不支持作为分区键，请通过计算列转换为日期、整数或字符类型`) };
};

export const recommendedPartitionValue = (field: PartitionField, format = field.format): string => {
  if (field.type === "DATE") return "9999-12-01";
  if (["TINYINT", "SMALLINT", "INT", "BIGINT"].includes(field.type)) return "-1";
  return format ? recommendDatePartition(format) : "default";
};

export const partitionValueError = (value: string | undefined, field: PartitionField): string | undefined => {
  if (!field.allowed) return field.reason;
  if (typeof value !== "string" || !value.trim()) return "默认分区值不能为空";
  if (value.includes("/")) return "默认分区值不能包含 /";
  const bits = ({ TINYINT: 8, SMALLINT: 16, INT: 32, BIGINT: 64 } as Record<string, number>)[field.type];
  if (bits) {
    if (!/^[+-]?\d+$/.test(value)) return "默认值必须为整数";
    const boundary = BigInt(2) ** BigInt(bits - 1);
    if (BigInt(value) < -boundary || BigInt(value) >= boundary) return "默认值超出整数范围";
  }
  if (field.type === "DATE") {
    const date = new Date(`${value}T00:00:00Z`);
    if (!/^\d{4}-\d{2}-\d{2}$/.test(value) || Number.isNaN(date.getTime()) || date.toISOString().slice(0, 10) !== value) return "日期必须为合法 yyyy-MM-dd";
  }
  const max = field.type.match(/^(?:VAR)?CHAR\((\d+)\)$/)?.[1];
  if (max && [...value].length > Number(max)) return "默认值超过字符长度限制";
  if (field.format) return datePartitionError(value, field.format);
  return undefined;
};

export const partitionLiteral = (value: string, type?: string) => {
  if (["TINYINT", "SMALLINT", "INT", "BIGINT"].includes(type ?? "")) return value;
  return `${type === "DATE" ? "DATE " : ""}'${value.replace(/'/g, "''")}'`;
};
export const partitionDefaultsSummary = (config?: TablePrivateConfig) => {
  const values = config?.partitionDefaults?.values;
  if (!values || !Object.keys(values).length) return "未配置";
  const keys = config.partitionKeys?.length ? config.partitionKeys : Object.keys(values);
  return keys.map((key) => values[key]).filter((value) => value !== undefined).join("/") || "未配置";
};
