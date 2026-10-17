import { describe, expect, it } from "vitest";
import { isTimeColumn, timeFilterError, timeFilterSummary } from "./timeFilter";
import type { MysqlTimeFilter } from "../types";

const columns = [{ nullable: true, name: "created_at", type: "datetime(6)" }, { nullable: true, name: "d", type: "date" }, { nullable: true, name: "id", type: "bigint" }];
const filter: MysqlTimeFilter = { version: 1, field: "created_at", sourceType: "datetime(6)", start: "2026-01-01 00:00:00.123456", timeZone: "Asia/Shanghai" };
describe("time filter", () => {
  it("preserves microseconds and displays strict greater-than", () => {
    expect(timeFilterError(filter, columns)).toBeUndefined();
    expect(timeFilterSummary(filter)).toBe("created_at > 2026-01-01 00:00:00.123456");
    expect(timeFilterSummary()).toBe("未配置");
  });
  it.each(["2026-02-30 00:00:00", "2026-01-01 24:00:00", "2026-01-01 00:00:00.1234567", "", "2026-01-01"])("rejects invalid datetime %s", (start) => {
    expect(timeFilterError({ ...filter, start }, columns)).toBeTruthy();
  });
  it("checks type, field, timezone, date and source precision", () => {
    expect(timeFilterError({ ...filter, field: "id" }, columns)).toBeTruthy();
    expect(timeFilterError({ ...filter, timeZone: "invalid" }, columns)).toBeTruthy();
    expect(timeFilterError({ ...filter, sourceType: "datetime(3)" }, [{ nullable: true, name: "created_at", type: "datetime(3)" }])).toBeTruthy();
    expect(timeFilterError({ ...filter, field: "d", sourceType: "date", start: "2024-02-29" }, columns)).toBeUndefined();
    expect(isTimeColumn({ nullable: true, name: "t", type: "TIMESTAMP(6)" })).toBe(true);
    expect(isTimeColumn({ nullable: true, name: "t", type: "time" })).toBe(false);
  });
  it.each(["2026-03-08 02:30:00", "2026-11-01 01:30:00"])("rejects ambiguous or missing local timestamps %s", (start) => {
    expect(timeFilterError({ ...filter, sourceType: "timestamp", timeZone: "America/New_York", start }, [{ nullable: true, name: "created_at", type: "timestamp" }])).toContain("不存在或存在歧义");
  });
});
