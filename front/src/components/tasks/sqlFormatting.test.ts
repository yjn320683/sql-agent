import { describe, expect, it } from 'vitest';
import { formatHiveScript } from './sqlFormatting';

describe('formatHiveScript', () => {
  it('保留 Step、注释和参数，并可重复格式化', () => {
    const source = '====step:1:load====\n-- comment\nselect ${biz_date} as d from ods.t where ds=${biz_date}';
    const once = formatHiveScript(source);
    expect(once).toContain('====step:1:load====');
    expect(once).toContain('-- comment');
    expect(once).toContain('${biz_date}');
    expect(formatHiveScript(once)).toBe(once);
  });
});
