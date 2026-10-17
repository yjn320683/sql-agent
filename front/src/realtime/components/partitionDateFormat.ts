// 与后端 DateTimeFormatter 的常用数字日期格式一致；不把 Java 格式当作其他库的格式解释。
type DatePart = { literal: string } | { token: string };
const values: Record<string, string> = { y: "9999", u: "9999", M: "12", d: "01", H: "00", m: "00", s: "00", S: "000000000" };

const partsOf = (format: string): DatePart[] | undefined => {
  const parts: DatePart[] = [];
  for (let index = 0; index < format.length;) {
    const character = format[index];
    if (character === "'") {
      if (format[index + 1] === "'") {
        parts.push({ literal: "'" }); index += 2; continue;
      }
      let literal = "";
      let closed = false;
      index += 1;
      while (index < format.length) {
        if (format[index] === "'") {
          if (format[index + 1] === "'") { literal += "'"; index += 2; continue; }
          index += 1; closed = true; break;
        }
        literal += format[index++];
      }
      if (!closed) return undefined;
      parts.push({ literal });
    } else if (/[A-Za-z]/.test(character)) {
      let end = index + 1;
      while (format[end] === character) end += 1;
      const token = format.slice(index, end);
      if (!values[character]
          || ((character === "y" || character === "u") ? ![2, 4].includes(token.length)
            : character === "S" ? token.length > 9 : token.length > 2)) return undefined;
      parts.push({ token }); index = end;
    } else {
      // 可选段、保留字符暂不支持，避免推荐与后端解释不同的值。
      if ("[]{}#".includes(character)) return undefined;
      parts.push({ literal: character }); index += 1;
    }
  }
  return parts;
};

export const recommendDatePartition = (format: string): string => {
  if (format.includes("/")) return "";
  const parts = partsOf(format);
  if (!parts) return "";
  return parts.map((part) => {
    if ("literal" in part) return part.literal;
    const kind = part.token[0];
    const value = values[kind];
    if (kind === "S") return value.slice(0, part.token.length);
    if ((kind === "y" || kind === "u") && part.token.length === 2) return value.slice(-2);
    return part.token.length === 1 ? String(Number(value)) : value;
  }).join("");
};

export const datePartitionError = (value: string, format: string): string | undefined => {
  if (format.includes("/")) return "分区日期格式不能包含 /，请使用 - 或无分隔符格式";
  const parts = partsOf(format);
  if (!parts) return `暂不支持日期格式 ${format}，请使用数字年、月、日、时、分、秒格式`;
  const tokens: string[] = [];
  const pattern = parts.map((part) => {
    if ("literal" in part) return part.literal.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    tokens.push(part.token);
    return part.token.length === 1 && part.token !== "S" ? "(\\d{1,2})" : `(\\d{${part.token.length}})`;
  }).join("");
  const match = new RegExp(`^${pattern}$`).exec(value);
  if (!match) return `默认值须符合 ${format}`;
  const fields: Record<string, number> = {};
  for (let index = 0; index < tokens.length; index += 1) {
    const token = tokens[index];
    const key = token[0] === "u" ? "y" : token[0];
    const number = Number(match[index + 1]) + (key === "y" && token.length === 2 ? 2000 : 0);
    if (fields[key] !== undefined && fields[key] !== number) return `默认值须为合法的 ${format} 日期`;
    fields[key] = number;
  }
  const year = fields.y ?? 2000;
  const month = fields.M ?? 1;
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const maxDay = fields.M === undefined ? 31 : [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1];
  if (month < 1 || month > 12 || (fields.d !== undefined && (fields.d < 1 || fields.d > maxDay))
      || (fields.H !== undefined && fields.H > 23) || (fields.m !== undefined && fields.m > 59)
      || (fields.s !== undefined && fields.s > 59)) return `默认值须为合法的 ${format} 日期`;
  return undefined;
};
