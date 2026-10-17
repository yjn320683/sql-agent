import { describe, expect, it } from "vitest";
import type { MysqlColumn } from "../types";
import {
  type OfficialComputedFunction,
  buildStructuredComputedExpression,
  computedColumnKeySafety,
  computedExpressionToDraft,
  emptyComputedColumnDraft,
  isFunctionCompatibleWithColumn,
  parseComputedColumnExpression,
  validateComputedColumnRows,
} from "./computedColumns";

const columns: MysqlColumn[] = [
  { nullable: true, name: "created_at", type: "timestamp" },
  { nullable: true, name: "name", type: "varchar(255)" },
  { nullable: true, name: "amount", type: "decimal(18,2)" },
  { nullable: true, name: "id", type: "bigint" },
  { nullable: true, name: "epoch_text", type: "varchar(20)" },
];

describe("computed column expressions", () => {
  it("validates chained partition computations without replacing the nullable source", () => {
    const result = validateComputedColumnRows([
      computedExpressionToDraft("dt=date_format(created_at,yyyyMMdd)"),
      computedExpressionToDraft("month=substring(dt,0,6)"),
    ], columns);
    expect(result.errors.filter(Boolean)).toEqual([]);
    expect(result.expressions).toEqual(["dt=date_format(created_at,yyyyMMdd)", "month=substring(dt,0,6)"]);
  });
  it("parses every official Paimon function into structured mode", () => {
    const expressions = [
      "y=year(created_at)",
      "m=month(created_at)",
      "d=day(created_at)",
      "h=hour(created_at)",
      "mi=minute(created_at)",
      "s=second(created_at)",
      "dt=date_format(created_at,yyyyMMdd)",
      "current_time=now()",
      "short_name=substring(name,0,3)",
      "amount_group=truncate(amount,10)",
      "constant_number=cast(11,INT)",
      "upper_name=upper(name)",
      "lower_name=lower(name)",
      "clean_name=trim(name)",
    ];

    expect(expressions.map((expression) => {
      const draft = computedExpressionToDraft(expression);
      return [draft.mode, buildStructuredComputedExpression(draft)];
    })).toEqual(expressions.map((expression) => ["structured", expression]));
  });

  it("rejects unsupported expressions without falling back to advanced mode", () => {
    expect(computedExpressionToDraft("custom_key=my_func(name,nested(1,2))"))
      .toMatchObject({ mode: "structured", expression: "custom_key=my_func(name,nested(1,2))" });
    expect(computedExpressionToDraft("broken expression"))
      .toEqual({ mode: "structured", columnName: "", expression: "broken expression" });
    expect(parseComputedColumnExpression("custom_key=my_func(name,nested(1,2))")?.arguments)
      .toEqual(["name", "nested(1,2)"]);
  });

  it("generates parameterized structured expressions without adding quotes", () => {
    expect(buildStructuredComputedExpression({
      ...emptyComputedColumnDraft(),
      columnName: "create_date",
      functionName: "date_format",
      referenceColumn: "created_at",
      format: "yyyyMMdd",
    })).toBe("create_date=date_format(created_at,yyyyMMdd)");
    expect(buildStructuredComputedExpression({
      ...emptyComputedColumnDraft(),
      columnName: "create_date",
      functionName: "date_format",
      referenceColumn: "id",
      format: "yyyyMMdd",
      temporalPrecision: "3",
    })).toBe("create_date=date_format(id,yyyyMMdd,3)");
    expect(buildStructuredComputedExpression({
      ...emptyComputedColumnDraft(),
      columnName: "short_name",
      functionName: "substring",
      referenceColumn: "name",
      begin: "1",
      end: "5",
    })).toBe("short_name=substring(name,1,5)");
  });

  it("validates structured parameter boundaries and precise input errors", () => {
    const quotedFormat = validateComputedColumnRows([{
      ...emptyComputedColumnDraft(),
      columnName: "create_date",
      functionName: "date_format",
      referenceColumn: "created_at",
      format: "'yyyyMMdd'",
    }], columns);
    expect(quotedFormat.errors[0]).toBe("date_format 格式参数不需要单引号或双引号");

    const substring = validateComputedColumnRows([{
      ...emptyComputedColumnDraft(),
      columnName: "short_name",
      functionName: "substring",
      referenceColumn: "name",
      begin: "5",
      end: "2",
    }], columns);
    expect(substring.errors[0]).toBe("substring 结束位置必须大于开始位置");

    const truncate = validateComputedColumnRows([{
      ...emptyComputedColumnDraft(),
      columnName: "amount_group",
      functionName: "truncate",
      referenceColumn: "amount",
      width: "0",
    }], columns);
    expect(truncate.errors[0]).toBe("truncate 宽度必须是正整数");

    const cast = validateComputedColumnRows([{
      ...emptyComputedColumnDraft(),
      columnName: "constant_number",
      functionName: "cast",
      literalValue: "11",
      castType: "11",
    }], columns);
    expect(cast.errors[0]).toBe("cast 不支持目标数据类型：11");

    const constantCast = validateComputedColumnRows([{
      ...emptyComputedColumnDraft(),
      columnName: "constant_number",
      functionName: "cast",
      literalValue: "11",
      castType: "INT",
    }], columns);
    expect(constantCast.errors.filter(Boolean)).toEqual([]);
    expect(constantCast.expressions).toEqual(["constant_number=cast(11,INT)"]);
    expect(computedExpressionToDraft("constant_text=cast(hello)")).toMatchObject({
      mode: "structured", literalValue: "hello", castType: undefined,
    });

    const missingName = validateComputedColumnRows([{
      mode: "advanced",
      columnName: "",
      expression: "date_format(created_at,yyyyMMdd)",
    }], columns);
    expect(missingName.errors[0]).toBe(
      "缺少计算列名，请填写：create_date=date_format(created_at,yyyyMMdd)",
    );
  });

  it.each([
    { literalValue: "hello", valid: true },
    { literalValue: "11", valid: true },
    { literalValue: "O'Reilly", valid: false },
    { literalValue: 'say "hello', valid: false },
    { literalValue: "'hello'", valid: true },
    { literalValue: '"hello"', valid: true },
    { literalValue: "C:\\data", valid: true },
    { literalValue: "'hello\\'", valid: false },
  ])("keeps modal and task validation consistent for $literalValue", ({ literalValue, valid }) => {
    const row = {
      ...emptyComputedColumnDraft(),
      columnName: "constant",
      functionName: "cast" as const,
      literalValue,
      castType: "STRING",
    };
    const expression = buildStructuredComputedExpression(row);
    const modal = validateComputedColumnRows([row], columns);
    const task = validateComputedColumnRows([computedExpressionToDraft(expression)], columns);
    expect(modal.errors).toEqual(task.errors);
    if (valid) {
      expect(modal.errors).toEqual([]);
      expect(modal.expressions).toEqual([expression]);
      expect(task.expressions).toEqual([expression]);
    } else {
      expect(modal.errors).toEqual(["计算列表达式格式错误：" + expression]);
      expect(modal.expressions).toEqual([]);
      expect(task.expressions).toEqual([]);
    }
  });

  it("rejects custom advanced functions using the structured function whitelist", () => {
    const valid = validateComputedColumnRows([
      { mode: "advanced", columnName: "", expression: "custom_key=my_func(name,nested(1,2))" },
      { mode: "advanced", columnName: "", expression: "generated_at=custom_now()" },
    ], columns);
    expect(valid.errors).toEqual(["不支持计算列函数：my_func", "不支持计算列函数：custom_now"]);
    expect(valid.expressions).toEqual([]);

    const invalid = validateComputedColumnRows([
      { mode: "advanced", columnName: "", expression: "custom_key=my_func(missing,1)" },
    ], columns);
    expect(invalid.errors[0]).toBe("不支持计算列函数：my_func");

    const incompatible = validateComputedColumnRows([
      { mode: "advanced", columnName: "", expression: "upper_id=upper(id)" },
    ], columns);
    expect(incompatible.errors[0]).toBe("upper 不支持源字段类型：id[bigint]");
  });

  it("rejects physical or duplicate output names", () => {
    const result = validateComputedColumnRows([
      { mode: "advanced", columnName: "", expression: "name=now()" },
      { mode: "advanced", columnName: "", expression: "same=now()" },
      { mode: "advanced", columnName: "", expression: "same=now()" },
    ], columns);
    expect(result.errors).toEqual([
      "计算列名称与源字段重复：name",
      undefined,
      "计算列名称不能重复：same",
    ]);
  });

  it("round-trips optional precision in ordinary and advanced temporal expressions", () => {
    for (const fn of ["year", "month", "day", "hour", "minute", "second", "date_format"]) {
      for (const precision of [0, 3, 6, 9]) {
        const expression = fn === "date_format"
          ? `c=${fn}(id,yyyy-MM-dd,${precision})` : `c=${fn}(id,${precision})`;
        const ordinary = computedExpressionToDraft(expression);
        expect(ordinary).toMatchObject({ mode: "structured", temporalPrecision: String(precision) });
        expect(buildStructuredComputedExpression(ordinary)).toBe(expression);
        for (const row of [ordinary, { mode: "advanced" as const, columnName: "", expression }]) {
          const result = validateComputedColumnRows([row], columns);
          expect(result.errors.filter(Boolean)).toEqual([]);
          expect(result.expressions).toEqual([expression]);
        }
      }
    }
    expect(validateComputedColumnRows([computedExpressionToDraft("c=date_format(id,yyyy-MM-dd)")], columns)
      .expressions).toEqual(["c=date_format(id,yyyy-MM-dd)"]);
  });

  it("rejects invalid precision and non-epoch input equally in both modes", () => {
    for (const expression of ["c=year(id,2)", "c=date_format(id,yyyy-MM-dd,-1)",
      "c=month(id,abc)", "c=date_format(created_at,yyyy-MM-dd,3)",
      "c=year(created_at,0)"]) {
      const normal = validateComputedColumnRows([computedExpressionToDraft(expression)], columns);
      const advanced = validateComputedColumnRows([{ mode: "advanced", columnName: "", expression }], columns);
      expect(normal.errors).toEqual(advanced.errors);
      expect(normal.errors.filter(Boolean)).toHaveLength(1);
    }
  });

  it.each(["c=upper(name,1)", "c=now(name)", "c=date_format(created_at)",
    "c=substring(name)", "c=substring(name,5,2)", "c=truncate(id,0)"])(
    "rejects advanced parameters unavailable in structured mode: %s", (expression) => {
      const result = validateComputedColumnRows([{ mode: "advanced", columnName: "", expression }], columns);
      expect(result.errors.filter(Boolean)).toHaveLength(1);
      expect(result.expressions).toEqual([]);
    },
  );

  it.each(["c=substring(name,2147483648)", "c=substring(name,0,2147483648)", "c=truncate(id,2147483648)",
    "c=date_format(created_at,yyyy','MM)", "c=date_format(created_at,yyyy/MM/dd)",
    "c=date_format(created_at,yyyy'/'MM)"])("uses the same validation for both modes: %s", (expression) => {
    const ordinary = computedExpressionToDraft(expression);
    expect(ordinary.mode).toBe("structured");
    const normal = validateComputedColumnRows([ordinary], columns);
    const advanced = validateComputedColumnRows([{ mode: "advanced", columnName: "", expression }], columns);
    expect(normal.errors).toEqual(advanced.errors);
    expect(normal.errors.filter(Boolean)).toHaveLength(1);
  });

  it("filters functions by source field type", () => {
    const compatible = (functionName: OfficialComputedFunction, name: string) => (
      isFunctionCompatibleWithColumn(functionName, columns.find((column) => column.name === name))
    );
    expect(compatible("date_format", "created_at")).toBe(true);
    expect(compatible("substring", "name")).toBe(true);
    expect(compatible("substring", "amount")).toBe(true);
    expect(compatible("truncate", "amount")).toBe(true);
    expect(compatible("year", "amount")).toBe(false);
    expect(compatible("cast", "amount")).toBe(true);
    expect(compatible("upper", "id")).toBe(false);
    expect(compatible("upper", "name")).toBe(true);
    expect(compatible("substring", "id")).toBe(true);
    expect(isFunctionCompatibleWithColumn("upper", columns[3], ["to-string"])).toBe(true);
    expect(isFunctionCompatibleWithColumn("upper", { nullable: true, name: "code", type: "char(8)" })).toBe(false);
    expect(isFunctionCompatibleWithColumn("upper", { nullable: true, name: "code", type: "char(8)" }, ["char-to-string"])).toBe(true);
    expect(isFunctionCompatibleWithColumn("truncate", { nullable: true, name: "ratio", type: "double" })).toBe(false);
    expect(isFunctionCompatibleWithColumn("truncate", { nullable: true, name: "code", type: "char(8)" })).toBe(true);
    expect(isFunctionCompatibleWithColumn("truncate", { nullable: true, name: "large", type: "decimal(50,2)" })).toBe(true);
    expect(isFunctionCompatibleWithColumn("upper", { nullable: true, name: "large", type: "decimal(50,2)" })).toBe(true);
    expect(isFunctionCompatibleWithColumn("upper", { nullable: true, name: "tags", type: "set('a','b')" })).toBe(false);
    expect(isFunctionCompatibleWithColumn("year", { nullable: true, name: "clock", type: "time" })).toBe(false);
  });

  it("derives whether computed columns are safe for primary and partition keys", () => {
    const keyColumns: MysqlColumn[] = [
      { name: "id", type: "bigint", nullable: false },
      { name: "created_at", type: "timestamp", nullable: false },
      { name: "updated_at", type: "timestamp", nullable: true },
    ];

    expect(computedColumnKeySafety("day_key=day(created_at)", keyColumns).safe).toBe(true);
    expect(computedColumnKeySafety("day_key=day(updated_at)", keyColumns)).toEqual({
      safe: false,
      reason: "计算列 day_key=day(updated_at) 引用字段 updated_at 允许 NULL，结果可能为空",
    });
    expect(computedColumnKeySafety("current_time=now()", keyColumns).safe).toBe(true);
    expect(computedColumnKeySafety("constant_key=cast(11,INT)", keyColumns).safe).toBe(true);
    expect(computedColumnKeySafety("custom_key=my_func(id)", keyColumns)).toEqual({
      safe: false,
      reason: "计算列 custom_key=my_func(id) 无法证明结果非空",
    });
  });

  it("follows computed key dependencies and rejects nullable or cyclic chains", () => {
    const expressions = ["y=year(created_at)", "y_group=truncate(y,10)"];
    expect(computedColumnKeySafety(expressions[1], [{ name: "created_at", type: "timestamp", nullable: false }], [], expressions).safe).toBe(true);
    expect(computedColumnKeySafety(expressions[1], [{ name: "created_at", type: "timestamp", nullable: true }], [], expressions).safe).toBe(false);
    const constant = ["n=cast(11,INT)", "n_group=truncate(n,10)"];
    expect(computedColumnKeySafety(constant[1], [], [], constant).safe).toBe(true);
    const cycle = ["a=upper(b)", "b=lower(a)"];
    expect(computedColumnKeySafety(cycle[0], [], [], cycle).reason).toContain("循环依赖");
    expect(computedColumnKeySafety("a=upper(missing)", [], [], []).safe).toBe(false);
  });
});
