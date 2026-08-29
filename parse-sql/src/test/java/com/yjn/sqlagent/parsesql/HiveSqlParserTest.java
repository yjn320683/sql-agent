package com.yjn.sqlagent.parsesql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HiveSqlParserTest {
    private final HiveSqlParser parser = new HiveSqlParser();

    @Test
    void parsesInsertWithCteJoinPartitionAndPlaceholder() {
        String sql = "WITH base AS (SELECT id FROM ods.orders WHERE dt = ${biz_date})\n"
                + "INSERT OVERWRITE TABLE `dw`.`order_summary` PARTITION (dt=${biz_date})\n"
                + "SELECT b.id FROM base b JOIN dim.users u ON b.id=u.id";

        SqlParseResult result = parser.parseStatement(sql);

        assertEquals(SqlStatementType.WITH, result.getStatementType());
        assertEquals(Arrays.asList("ods.orders", "dim.users"), result.getInputTables());
        assertEquals(Arrays.asList("dw.order_summary"), result.getOutputTables());
        assertFalse(result.hasUnresolvedTableReferences());
    }

    @Test
    void excludesCteReferencesButKeepsNestedPhysicalTables() {
        String sql = "WITH first_cte AS (SELECT * FROM ods.a), "
                + "second_cte AS (SELECT * FROM first_cte JOIN ods.b ON first_cte.id=ods.b.id) "
                + "SELECT * FROM second_cte UNION ALL SELECT * FROM ods.c";

        SqlParseResult result = parser.parseStatement(sql);

        assertEquals(Arrays.asList("ods.a", "ods.b", "ods.c"), result.getInputTables());
        assertTrue(result.getOutputTables().isEmpty());
    }

    @Test
    void appliesCteNamesOnlyInsideTheirVisibleQueryScope() {
        String sql = "SELECT * FROM shadowed WHERE EXISTS ("
                + "WITH shadowed AS (SELECT id FROM ods.inner_source) "
                + "SELECT 1 FROM shadowed)";

        SqlParseResult result = parser.parseStatement(sql);

        assertEquals(Arrays.asList("shadowed", "ods.inner_source"), result.getInputTables());
    }

    @Test
    void parsesCreateTableAsSelectWithoutAllowingItAtConsumerLayer() {
        SqlParseResult result = parser.parseStatement(
                "CREATE TABLE IF NOT EXISTS tmp.result AS SELECT * FROM ods.source");

        assertEquals(SqlStatementType.CREATE_TABLE_AS_SELECT, result.getStatementType());
        assertEquals(Arrays.asList("ods.source"), result.getInputTables());
        assertEquals(Arrays.asList("tmp.result"), result.getOutputTables());
    }

    @Test
    void splitsOnlyTopLevelSemicolons() {
        List<String> statements = parser.splitStatements(
                "select ';' AS value from ods.a; -- comment ;\n"
                        + "insert into table dw.b select * from ods.c; /* trailing ; */");

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).contains("';'"));
        assertTrue(statements.get(1).contains("insert into"));
    }

    @Test
    void rewritesOnlyTableTokens() {
        String sql = "INSERT OVERWRITE TABLE dw.target\n"
                + "SELECT 'dw.target' AS label FROM dw.source s -- dw.source\n"
                + "WHERE s.name = 'dw.source'";
        Map<String, String> replacements = new LinkedHashMap<>();
        replacements.put("dw.target", "tmp.target_1");
        replacements.put("dw.source", "tmp.source_1");

        String rewritten = parser.rewriteTables(sql, replacements);

        assertTrue(rewritten.contains("TABLE tmp.target_1"));
        assertTrue(rewritten.contains("FROM tmp.source_1"));
        assertTrue(rewritten.contains("'dw.target'"));
        assertTrue(rewritten.contains("-- dw.source"));
        assertTrue(rewritten.contains("'dw.source'"));
    }

    @Test
    void reportsReadableSyntaxPosition() {
        SqlParseException error = assertThrows(SqlParseException.class,
                () -> parser.parseStatement("select from"));

        assertTrue(error.getMessage().contains("第1行"));
        assertEquals(1, error.getLine());
    }
}
