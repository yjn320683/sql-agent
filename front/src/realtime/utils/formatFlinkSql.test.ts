import { describe, expect, it } from 'vitest';
import { formatFlinkSql } from './formatFlinkSql';

describe('formatFlinkSql', () => {
  it('formats Flink SET and statement-set scripts without changing tokens', () => {
    const sql = "SET 'pipeline.name'='codex-compute-accept'; EXECUTE STATEMENT SET BEGIN INSERT INTO tmp.out SELECT id,payload,remark FROM tmp.source WHERE id > 0; END;";
    const formatted = formatFlinkSql(sql);
    expect(formatted).toContain("SET 'pipeline.name'='codex-compute-accept';\nEXECUTE STATEMENT SET\nBEGIN\n  INSERT INTO tmp.out\n  SELECT id, payload, remark\n  FROM tmp.source\n  WHERE id > 0;\nEND;");
    expect(formatFlinkSql(formatted)).toBe(formatted);
  });

  it('preserves quoted text, comments and semicolons inside literals', () => {
    const sql = "select '-- FROM;x', 'it''s; ok', `order`, \"WHERE;x\" from t -- SELECT hidden\nwhere id=1 /* FROM ; literal */;";
    const formatted = formatFlinkSql(sql);
    expect(formatted).toContain("'-- FROM;x'");
    expect(formatted).toContain("'it''s; ok'");
    expect(formatted).toContain('`order`');
    expect(formatted).toContain('"WHERE;x"');
    expect(formatted).toContain('-- SELECT hidden\nwhere id=1 /* FROM ; literal */;');
    expect(formatFlinkSql(formatted)).toBe(formatted);
  });

  it('does not change an empty draft or normalize keyword casing', () => {
    expect(formatFlinkSql('  ')).toBe('');
    expect(formatFlinkSql('select a from t')).toBe('select a\nfrom t');
  });

  it('keeps CASE END inside the statement-set block', () => {
    const sql = 'EXECUTE STATEMENT SET BEGIN INSERT INTO t SELECT CASE WHEN id=1 THEN 1 ELSE 0 END FROM s; END;';
    const formatted = formatFlinkSql(sql);
    expect(formatted).toContain('  SELECT CASE WHEN id=1 THEN 1 ELSE 0 END\n  FROM s;\nEND;');
    expect(formatFlinkSql(formatted)).toBe(formatted);
  });
});
