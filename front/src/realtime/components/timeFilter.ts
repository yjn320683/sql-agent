import type { MysqlColumn, MysqlTimeFilter } from "../types";

export const timeSourceType = (type: string) => type.trim().toLowerCase().replace("(0)", "");
export const timePrecision = (type: string) => Number(/\(([0-6])\)/.exec(type)?.[1] ?? 0);
export const isTimeColumn = (column: MysqlColumn) => /^(date|(datetime|timestamp)(\([0-6]\))?)$/.test(timeSourceType(column.type));
export const timeFilterSummary = (filter?: MysqlTimeFilter) => filter ? `${filter.field} > ${filter.start}` : "未配置";

export const timeFilterError = (filter: MysqlTimeFilter | undefined, columns: MysqlColumn[]): string | undefined => {
  if (!filter) return;
  const column = columns.find((item) => item.name === filter.field);
  if (!column || !isTimeColumn(column) || timeSourceType(column.type) !== filter.sourceType) return "过滤字段不存在或源字段类型已变化";
  if (filter.version !== 1) return "不支持的时间过滤版本";
  if (!filter.timeZone) return "时间过滤时区无效";
  try { new Intl.DateTimeFormat("en", { timeZone: filter.timeZone }).format(); } catch { return "时间过滤时区无效"; }
  const dateOnly = filter.sourceType === "date";
  const match = /^(\d{4})-(\d{2})-(\d{2})(?: (\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,6}))?)?$/.exec(filter.start);
  if (!match || (dateOnly ? match[4] !== undefined : match[4] === undefined)) return "请选择有效的过滤开始时间";
  const [, y, m, d, h = "0", minute = "0", s = "0", fraction = ""] = match;
  const year = Number(y), month = Number(m), day = Number(d);
  const days = new Date(Date.UTC(year, month, 0)).getUTCDate();
  if (year < 1000 || month < 1 || month > 12 || day < 1 || day > days || Number(h) > 23 || Number(minute) > 59 || Number(s) > 59) return "请选择有效的过滤开始时间";
  if (fraction.length > timePrecision(filter.sourceType)) return "过滤开始时间精度超过源字段精度";
  if (filter.sourceType.startsWith("timestamp")) {
    // Sample the adjacent offsets, then require exactly one instant for this wall-clock time.
    const formatter = new Intl.DateTimeFormat("en-GB", { timeZone: filter.timeZone, year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23" });
    const wallMillis = (instant: number) => {
      const parts = Object.fromEntries(formatter.formatToParts(instant).map(part => [part.type, part.value]));
      return Date.UTC(Number(parts.year), Number(parts.month) - 1, Number(parts.day), Number(parts.hour), Number(parts.minute), Number(parts.second));
    };
    const base = Date.UTC(year, month - 1, day, Number(h), Number(minute), Number(s));
    const offsets = new Set([-86400000, 0, 86400000].map(delta => wallMillis(base + delta) - base - delta));
    if ([...offsets].filter(offset => wallMillis(base - offset) === base).length !== 1) return "过滤开始时间在所选时区不存在或存在歧义";
  }
};
