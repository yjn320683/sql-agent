import { describe, expect, it } from "vitest";
import type { MysqlTableSchema } from "../types";
import { partitionDefaultsSummary, partitionField, partitionLiteral, partitionSourceType, partitionValueError, recommendedPartitionValue } from "./partitionDefaults";

const schema: MysqlTableSchema = { table: "orders", primaryKeys: ["id"], columns: [
  { name: "id", type: "bigint", nullable: false }, { name: "time", type: "timestamp", nullable: true },
  { name: "p", type: "int", nullable: true }, { nullable: true, name: "short", type: "varchar(3)" },
  { nullable: true, name: "float", type: "double" },
] };

describe("NULL partition configuration", () => {
  it("rejects slash in defaults and date formats", () => {
    const field = { type: "STRING", allowed: true, deterministic: true };
    expect(partitionValueError("a/b", field)).toBe("默认分区值不能包含 /");
    expect(partitionValueError("default", { ...field, format: "yyyy/MM/dd" })).toContain("不能包含 /");
    expect(recommendedPartitionValue({ ...field, format: "yyyy/MM/dd" })).toBe("");
  });
  it("shows configured partition values in partition-key order", () => {
    expect(partitionDefaultsSummary()).toBe("未配置");
    expect(partitionDefaultsSummary({ partitionKeys: ["year", "business_date"],
      partitionDefaults: { version: 1, values: { business_date: "2020-12-11", year: "2020" } } }))
      .toBe("2020/2020-12-11");
  });
  it.each([
    ["yyyyMMdd", "99991201"], ["yyyy-MM-dd", "9999-12-01"],
    ["yyyyMM", "999912"],
    ["yyyyMMddHH0000", "99991201000000"], ["yyyy-MM-dd HH:mm:ss", "9999-12-01 00:00:00"],
    ["yyyy-MM-dd'T'HH:mm:ss.SSS", "9999-12-01T00:00:00.000"],
  ])("recommends and validates a date value for %s", (format, expected) => {
    const field = { type: "STRING", allowed: true, deterministic: true, format };
    expect(recommendedPartitionValue(field)).toBe(expected);
    expect(partitionValueError(expected, field)).toBeUndefined();
    expect(partitionValueError("default", field)).toContain("符合");
  });

  it("validates custom date separators and never falls back to default for unsupported formats", () => {
    const field = { type: "STRING", allowed: true, deterministic: true, format: "yyyy.MM.dd HH:mm:ss" };
    for (const value of ["2026.02.30 00:00:00", "2026.12.01 24:00:00", "2026.12.01 00:60:00"]) {
      expect(partitionValueError(value, field)).toContain("合法");
    }
    expect(partitionValueError("2024.02.29 00:00:00", field)).toBeUndefined();
    const unsupported = { ...field, format: "yyyy-MMM-dd" };
    expect(recommendedPartitionValue(unsupported)).toBe("");
    expect(partitionValueError("9999-Dec-01", unsupported)).toContain("暂不支持");
  });
  it("explains time-dependent expressions and unsupported field types", () => {
    const expressions = ["n=now()", "dt=date_format(n,yyyyMMdd)"];
    for (const key of ["n", "dt"]) {
      expect(partitionField(schema, expressions, key).reason)
        .toBe("now() 的结果随执行时间变化，请使用源表时间字段生成分区值");
    }
    expect(partitionField(schema, [], "time").reason).toContain("类型 timestamp 不支持直接作为分区键");
    expect(partitionField(schema, [], "float").reason).toContain("类型 double 不支持直接作为分区键");
    expect(partitionField(schema, [], "id").reason).toBeUndefined();
  });
  it("limits direct types even with to-string but admits deterministic computed results", () => {
    expect(partitionField(schema, [], "time", ["to-string"]).allowed).toBe(false);
    expect(partitionField(schema, [], "float", ["to-string"]).allowed).toBe(false);
    const dt = partitionField(schema, ["dt=date_format(time,yyyyMMdd)"], "dt");
    expect(dt.allowed).toBe(true);
    expect(dt.type).toBe("STRING");
    expect(recommendedPartitionValue(dt)).toBe("99991201");
    expect(partitionField(schema, ["n=now()", "dt=date_format(n,yyyyMMdd)"], "dt").allowed).toBe(false);
  });
  it("validates numeric bounds, exact lengths and real dates", () => {
    const number = partitionField(schema, [], "p");
    expect(recommendedPartitionValue(number)).toBe("-1");
    expect(partitionValueError("2147483648", number)).toContain("范围");
    expect(partitionValueError("default", partitionField(schema, [], "short"))).toContain("长度");
    const date = partitionField(schema, ["d=cast(2026-01-01,DATE)"], "d");
    expect(partitionValueError("2026-02-30", date)).toContain("合法");
    expect(partitionValueError("9999-12-01", date)).toBeUndefined();
  });
  it("keeps SQL literals escaped and recommends each business format", () => {
    expect(partitionLiteral("O'Reilly")).toBe("'O''Reilly'");
    expect(partitionLiteral("9999-12-01", "DATE")).toBe("DATE '9999-12-01'");
    const field = partitionField(schema, ["dt=substring(time,0,8)"], "dt");
    expect(recommendedPartitionValue(field, "yyyyMM")).toBe("999912");
    expect(recommendedPartitionValue(field, "yyyyMMddHH0000")).toBe("99991201000000");
  });
  it("matches Paimon unsigned mediumint mapping and validates calendar values", () => {
    expect(partitionSourceType({ nullable: true, name: "p", type: "mediumint unsigned" }, [])).toBe("BIGINT");
    for (const [format, value] of [["yyyy-MM-dd", "2026-02-30"], ["yyyyMM", "202613"],
      ["yyyyMMdd", "20260229"], ["yyyyMMddHH0000", "20261013240000"]]) {
      expect(partitionValueError(value, { type: "STRING", allowed: true, deterministic: true, format })).toContain("合法");
    }
    expect(partitionValueError("20240229", { type: "STRING", allowed: true, deterministic: true, format: "yyyyMMdd" })).toBeUndefined();
  });
});
