import { describe, expect, it } from 'vitest';
import type { MysqlColumn } from '../types';
import {
  buildStructuredComputedExpression,
  computedExpressionToDraft,
  emptyComputedColumnDraft,
  isFunctionCompatibleWithColumn,
  parseComputedColumnExpression,
  validateComputedColumnRows,
  type OfficialComputedFunction,
} from './computedColumns';

const columns: MysqlColumn[] = [
  { name: 'created_at', type: 'timestamp', nullable: false },
  { name: 'name', type: 'varchar(255)', nullable: true },
  { name: 'amount', type: 'decimal(18,2)', nullable: true },
  { name: 'id', type: 'bigint', nullable: false },
];

describe('同步任务计算列', () => {
  it('可以无损解析并重新生成所有官方函数', () => {
    const expressions = [
      'y=year(created_at)', 'm=month(created_at)', 'd=day(created_at)',
      'h=hour(created_at)', 'mi=minute(created_at)', 's=second(created_at)',
      'dt=date_format(created_at,yyyyMMdd)', 'current_time=now()',
      'short_name=substring(name,0,3)', 'amount_group=truncate(amount,10)',
      'amount_text=cast(amount,STRING)', 'upper_name=upper(name)',
      'lower_name=lower(name)', 'clean_name=trim(name)',
    ];
    expect(expressions.map((expression) => {
      const draft = computedExpressionToDraft(expression);
      return [draft.mode, buildStructuredComputedExpression(draft)];
    })).toEqual(expressions.map((expression) => ['structured', expression]));
  });

  it('保留自定义嵌套函数并校验引用字段', () => {
    expect(parseComputedColumnExpression('custom_key=my_func(name,nested(1,2))')?.arguments)
      .toEqual(['name', 'nested(1,2)']);
    expect(validateComputedColumnRows([
      { mode: 'advanced', columnName: '', expression: 'custom_key=my_func(name,nested(1,2))' },
    ], columns).expressions).toEqual(['custom_key=my_func(name,nested(1,2))']);
    expect(validateComputedColumnRows([
      { mode: 'advanced', columnName: '', expression: 'custom_key=my_func(missing,1)' },
    ], columns).errors[0]).toBe('计算列引用字段不存在：missing');
  });

  it('给出与参考项目一致的参数边界错误', () => {
    expect(validateComputedColumnRows([{
      ...emptyComputedColumnDraft(), columnName: 'create_date', functionName: 'date_format',
      referenceColumn: 'created_at', format: "'yyyyMMdd'",
    }], columns).errors[0]).toBe('date_format 格式参数不需要单引号或双引号');
    expect(validateComputedColumnRows([{
      ...emptyComputedColumnDraft(), columnName: 'short_name', functionName: 'substring',
      referenceColumn: 'name', begin: '5', end: '2',
    }], columns).errors[0]).toBe('substring 结束位置必须大于开始位置');
    expect(validateComputedColumnRows([{
      ...emptyComputedColumnDraft(), columnName: 'event_time', functionName: 'cast',
      referenceColumn: 'created_at', castType: 'TIMESTAMP_LTZ(3)',
    }], columns).errors.filter(Boolean)).toEqual([]);
    expect(validateComputedColumnRows([{
      mode: 'advanced', columnName: '', expression: 'date_format(created_at,yyyyMMdd)',
    }], columns).errors[0]).toBe('缺少计算列名，请填写：create_date=date_format(created_at,yyyyMMdd)');
  });

  it('拒绝物理字段重名、计算列重名和不兼容函数', () => {
    expect(validateComputedColumnRows([
      { mode: 'advanced', columnName: '', expression: 'name=custom_now()' },
      { mode: 'advanced', columnName: '', expression: 'same=custom_now()' },
      { mode: 'advanced', columnName: '', expression: 'same=custom_now()' },
    ], columns).errors).toEqual(['计算列名称与源字段重复：name', undefined, '计算列名称不能重复：same']);
    const compatible = (functionName: OfficialComputedFunction, name: string) =>
      isFunctionCompatibleWithColumn(functionName, columns.find((column) => column.name === name));
    expect(compatible('substring', 'name')).toBe(true);
    expect(compatible('substring', 'amount')).toBe(false);
    expect(compatible('truncate', 'amount')).toBe(true);
    expect(compatible('year', 'amount')).toBe(false);
  });
});
