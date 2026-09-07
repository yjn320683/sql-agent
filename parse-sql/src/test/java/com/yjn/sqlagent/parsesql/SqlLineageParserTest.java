package com.yjn.sqlagent.parsesql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SqlLineageParserTest {
    private final SqlLineageParser parser = new SqlLineageParser();

    @Test
    void expandsWildcardAndMapsInsertTargetByMetadataOrder() {
        CountingMetadata metadata = new CountingMetadata()
                .table("ods.orders", "id", "buyer_id", "amount")
                .table("dw.order_fact", "id", "buyer_id", "amount");

        StatementLineage result = parser.parseStatement(request(
                "insert into dw.order_fact select * from ods.orders", metadata));

        assertEquals(Collections.singletonList("ods.orders"), result.getInputTables());
        assertEquals(Collections.singletonList("dw.order_fact"), result.getOutputTables());
        assertEquals(Arrays.asList("id", "buyer_id", "amount"), result.getColumnLineages().stream()
                .map(ColumnLineage::getTargetColumn).collect(java.util.stream.Collectors.toList()));
        assertEquals(1, metadata.calls("ods.orders"));
        assertEquals(1, metadata.calls("dw.order_fact"));
    }

    @Test
    void tracesCteJoinFilterGroupHavingOrderAndWindow() {
        CountingMetadata metadata = new CountingMetadata()
                .table("ods.orders", "id", "buyer_id", "amount", "dt")
                .table("dim.buyers", "id", "region");
        String sql = "with o as (select id, buyer_id, amount, dt from ods.orders where dt='2026-09-07') "
                + "select b.region, sum(o.amount) as total, row_number() over (partition by b.region order by o.id) rn "
                + "from o join dim.buyers b on o.buyer_id=b.id "
                + "group by b.region having sum(o.amount)>0 order by total";

        StatementLineage result = parser.parseStatement(request(sql, metadata));

        assertEquals(Arrays.asList("ods.orders", "dim.buyers"), result.getInputTables());
        assertEquals(3, result.getColumnLineages().size());
        assertFalse(result.getJoins().isEmpty());
        assertContainsUsage(result, ColumnUsageType.JOIN);
        assertContainsUsage(result, ColumnUsageType.FILTER);
        assertContainsUsage(result, ColumnUsageType.GROUP_BY);
        assertContainsUsage(result, ColumnUsageType.HAVING);
        assertContainsUsage(result, ColumnUsageType.ORDER_BY);
        assertContainsUsage(result, ColumnUsageType.WINDOW_PARTITION);
        assertContainsUsage(result, ColumnUsageType.WINDOW_ORDER);
    }

    @Test
    void mergesUnionSourcesByOrdinal() {
        CountingMetadata metadata = new CountingMetadata()
                .table("a.left_t", "id")
                .table("b.right_t", "id");
        StatementLineage result = parser.parseStatement(request(
                "select id from a.left_t union all select id from b.right_t", metadata));

        assertEquals(1, result.getColumnLineages().size());
        assertEquals(2, result.getColumnLineages().get(0).getSources().size());
    }

    @Test
    void associatesUnknownUnqualifiedColumnWithEveryVisibleTableAndDiagnosesAmbiguity() {
        StatementLineage result = parser.parseStatement(ParseRequest.builder(
                "select id from db.a join db.b on a.k=b.k").build());

        ColumnLineage id = result.getColumnLineages().get(0);
        assertEquals(2, id.getSources().size());
        assertTrue(result.getDiagnostics().stream().anyMatch(item -> "AMBIGUOUS_COLUMN".equals(item.getCode())));
    }

    @Test
    void keepsWildcardWhenMetadataIsUnavailable() {
        StatementLineage result = parser.parseStatement(ParseRequest.builder("select * from ods.missing").build());

        assertEquals("*", result.getColumnLineages().get(0).getTargetColumn());
        assertTrue(result.getDiagnostics().stream().anyMatch(item -> "METADATA_UNAVAILABLE".equals(item.getCode())));
        assertTrue(result.getDiagnostics().stream().anyMatch(item -> "WILDCARD_NOT_EXPANDED".equals(item.getCode())));
    }

    @Test
    void doesNotCompareTargetCountBeforeWildcardCanBeExpanded() {
        StatementLineage result = parser.parseStatement(ParseRequest.builder(
                "insert into dw.target (id, name) select * from ods.missing").build());

        assertTrue(result.getDiagnostics().stream().anyMatch(item -> "WILDCARD_NOT_EXPANDED".equals(item.getCode())));
        assertFalse(result.getDiagnostics().stream()
                .anyMatch(item -> "TARGET_COLUMN_COUNT_MISMATCH".equals(item.getCode())));
    }

    @Test
    void mapsInsertValuesRowToExplicitTargetColumns() {
        StatementLineage result = parser.parseStatement(ParseRequest.builder(
                "insert into dw.audit (id, name, created_at) "
                        + "values (1, 'one', current_timestamp), (2, 'two', current_timestamp)").build());

        assertEquals(Arrays.asList("id", "name", "created_at"), result.getColumnLineages().stream()
                .map(ColumnLineage::getTargetColumn).collect(java.util.stream.Collectors.toList()));
        assertFalse(result.getDiagnostics().stream()
                .anyMatch(item -> "TARGET_COLUMN_COUNT_MISMATCH".equals(item.getCode())));
    }

    @Test
    void supportsNestedFunctionsInsideParameterizedCast() {
        StatementLineage result = parser.parseStatement(ParseRequest.builder(
                "insert into dw.target select cast(coalesce(src.amount, 0) as decimal(18, 2)) amount "
                        + "from ods.source src").build());

        assertEquals(Collections.singletonList("dw.target"), result.getOutputTables());
        assertEquals("amount", result.getColumnLineages().get(0).getTargetColumn());
    }

    @Test
    void supportsIfExpressionInsideCtasCast() {
        StatementLineage result = parser.parseStatement(ParseRequest.builder(
                "create table dw.target as select cast(if(src.enabled = 1, src.amount, null) "
                        + "as decimal(18, 2)) amount from ods.source src").build());

        assertEquals(SqlStatementType.CREATE_TABLE_AS_SELECT, result.getStatementType());
        assertEquals(Collections.singletonList("ods.source"), result.getInputTables());
    }

    @Test
    void supportsDeleteTargetAlias() {
        CountingMetadata metadata = new CountingMetadata().table("dw.events", "event_id", "dt");

        StatementLineage result = parser.parseStatement(request(
                "delete from dw.events e where e.dt >= '2026-09-01'", metadata));

        assertEquals(SqlStatementType.DELETE, result.getStatementType());
        assertContainsUsage(result, ColumnUsageType.FILTER);
        assertTrue(result.getColumnUsages().stream().flatMap(item -> item.getColumns().stream())
                .anyMatch(item -> "dt".equals(item.getColumn())));
    }

    @Test
    void parsesFlinkStatementSetAndStepScriptWithoutSplittingStringLiteral() {
        String sql = "====step:1:load====\nSET 'execution.checkpointing.interval' = '60 s';\n"
                + "EXECUTE STATEMENT SET BEGIN\n"
                + "INSERT INTO dw.a SELECT id, ';' AS marker FROM ods.src;\n"
                + "INSERT INTO dw.b SELECT id FROM ods.src;\nEND;";
        SqlScriptLineage result = parser.parseScript(ParseRequest.builder(sql)
                .dialect(SqlDialect.FLINK).mode(ParseMode.STRICT).build());

        assertEquals(3, result.getStatements().size());
        assertEquals(Arrays.asList("dw.a", "dw.b"), result.getOutputTables());
    }

    @Test
    void supportsMultipleStatementSetsAndCaseEndExpressions() {
        String sql = "EXECUTE STATEMENT SET BEGIN\n"
                + "INSERT INTO dw.a SELECT CASE WHEN id > 0 THEN id ELSE 0 END FROM ods.src;\nEND;\n"
                + "EXECUTE STATEMENT SET BEGIN\n"
                + "INSERT INTO dw.b SELECT id FROM ods.src;\nEND;";

        SqlScriptLineage result = parser.parseScript(ParseRequest.builder(sql)
                .dialect(SqlDialect.FLINK).build());

        assertEquals(2, result.getStatements().size());
        assertEquals(Arrays.asList("dw.a", "dw.b"), result.getOutputTables());
    }

    @Test
    void rewritesOnlyPhysicalTableRanges() {
        String sql = "with src as (select id from ods.raw where note='ods.raw') "
                + "insert into dw.target select id from src -- ods.raw\n";
        Map<String, String> replacements = new LinkedHashMap<>();
        replacements.put("ods.raw", "sandbox.raw_copy");
        replacements.put("dw.target", "sandbox.target_copy");

        String rewritten = parser.rewriteTables(ParseRequest.builder(sql).build(), replacements);

        assertTrue(rewritten.contains("from sandbox.raw_copy"));
        assertTrue(rewritten.contains("insert into sandbox.target_copy"));
        assertTrue(rewritten.contains("note='ods.raw'"));
        assertTrue(rewritten.contains("-- ods.raw"));
    }

    @Test
    void tolerantScriptCollectsSyntaxDiagnostics() {
        SqlScriptLineage result = parser.parseScript(ParseRequest.builder("select 1; select from broken; select 2")
                .mode(ParseMode.TOLERANT).build());
        assertEquals(2, result.getStatements().size());
        assertEquals(1, result.getDiagnostics().size());
        assertThrows(SqlParseException.class, () -> parser.parseScript(ParseRequest.builder(
                "select 1; select from broken").mode(ParseMode.STRICT).build()));
    }

    @Test
    void tolerantScriptRecoversTopLevelCommandsSeparatedOnlyByNewlines() {
        String sql = "insert into dw.first select id from ods.source\n"
                + "drop if exists tmp.old_table\n"
                + "create table dw.second as select id from ods.source";

        SqlScriptLineage result = parser.parseScript(ParseRequest.builder(sql)
                .mode(ParseMode.TOLERANT).build());

        assertEquals(Arrays.asList("dw.first", "dw.second"), result.getOutputTables());
        assertTrue(result.getStatements().stream().flatMap(item -> item.getDiagnostics().stream())
                .anyMatch(item -> "UNSUPPORTED_STATEMENT".equals(item.getCode())));
        assertFalse(result.getDiagnostics().stream().anyMatch(item -> "SYNTAX_ERROR".equals(item.getCode())));
    }

    @Test
    void stripsOnlyActualComments() {
        String sql = "-- leading ;\nselect '--not-comment', `a/*b*/` /* trailing ; */ from db.t";

        String stripped = parser.stripComments(sql);

        assertTrue(stripped.startsWith("select '--not-comment'"));
        assertTrue(stripped.contains("`a/*b*/`"));
        assertFalse(stripped.contains("leading"));
        assertFalse(stripped.contains("trailing"));
    }

    @Test
    void resolvesCorrelatedExistsAndInSubqueries() {
        CountingMetadata metadata = new CountingMetadata()
                .table("ods.orders", "id", "buyer_id")
                .table("dim.buyers", "id", "enabled")
                .table("risk.blocked", "buyer_id");
        String sql = "select o.id from ods.orders o where exists (select 1 from dim.buyers b "
                + "where b.id=o.buyer_id and b.enabled=1) and o.buyer_id in "
                + "(select buyer_id from risk.blocked)";

        StatementLineage result = parser.parseStatement(request(sql, metadata));

        assertEquals(Arrays.asList("ods.orders", "dim.buyers", "risk.blocked"), result.getInputTables());
        assertTrue(result.getColumnUsages().stream().filter(item -> item.getType() == ColumnUsageType.FILTER)
                .flatMap(item -> item.getColumns().stream())
                .anyMatch(item -> "buyer_id".equals(item.getColumn()) && "ods.orders".equals(item.getTable().qualifiedName())));
    }

    @Test
    void resolvesUsingAndNaturalJoinColumnsFromBothSides() {
        CountingMetadata metadata = new CountingMetadata()
                .table("db.a", "id", "left_value")
                .table("db.b", "id", "right_value");

        StatementLineage using = parser.parseStatement(request("select a.id from db.a join db.b using(id)", metadata));
        StatementLineage natural = parser.parseStatement(request("select a.id from db.a natural left join db.b", metadata));

        assertEquals(2, using.getJoins().get(0).getColumns().size());
        assertEquals(1, using.getJoins().get(0).getLeftColumns().size());
        assertEquals(1, using.getJoins().get(0).getRightColumns().size());
        assertEquals(2, natural.getJoins().get(0).getColumns().size());
    }

    @Test
    void keepsNestedCteAliasScopesSeparate() {
        CountingMetadata metadata = new CountingMetadata()
                .table("db.outer_t", "id")
                .table("db.inner_t", "id");
        String sql = "with x as (select id from db.outer_t), y as "
                + "(with x as (select id from db.inner_t) select id from x) "
                + "select x.id, y.id as inner_id from x join y on x.id=y.id";

        StatementLineage result = parser.parseStatement(request(sql, metadata));

        assertEquals(Arrays.asList("db.outer_t", "db.inner_t"), result.getInputTables());
        assertEquals("db.outer_t", result.getColumnLineages().get(0).getSources().get(0).getTable().qualifiedName());
        assertEquals("db.inner_t", result.getColumnLineages().get(1).getSources().get(0).getTable().qualifiedName());
    }

    @Test
    void handlesLateralViewUnnestArrayAndLambdaWithoutInventingLambdaColumn() {
        CountingMetadata metadata = new CountingMetadata().table("db.events", "id", "items", "payload");
        String lateralSql = "select e.id, item from db.events e lateral view explode(e.items) lv as item";
        String unnestSql = "select e.id, u.item from db.events e cross join unnest(e.items) as u(item)";
        String lambdaSql = "select transform(items, x -> x + 1) mapped from db.events";

        StatementLineage lateral = parser.parseStatement(request(lateralSql, metadata));
        StatementLineage unnest = parser.parseStatement(request(unnestSql, metadata));
        StatementLineage lambda = parser.parseStatement(request(lambdaSql, metadata));

        assertEquals(2, lateral.getColumnLineages().size());
        assertEquals(2, unnest.getColumnLineages().size());
        assertFalse(lambda.getDiagnostics().stream().anyMatch(item -> item.getMessage().contains("字段：x")));
        assertEquals(Collections.singletonList("items"), lambda.getColumnLineages().get(0).getSources().stream()
                .map(SourceColumn::getColumn).collect(java.util.stream.Collectors.toList()));
    }

    @Test
    void mapsDynamicPartitionAndRejectsDynamicTableRewrite() {
        CountingMetadata metadata = new CountingMetadata()
                .table("ods.src", "id", "dt")
                .table("dw.target", "id", "dt");
        StatementLineage result = parser.parseStatement(request(
                "insert overwrite table dw.target partition(dt) select id, dt from ods.src", metadata));

        assertEquals(Arrays.asList("id", "dt"), result.getColumnLineages().stream()
                .map(ColumnLineage::getTargetColumn).collect(java.util.stream.Collectors.toList()));

        StatementLineage dynamic = parser.parseStatement(ParseRequest.builder(
                "insert into ${target_db}.${target_table} select id from ods.src").build());
        assertEquals(1, dynamic.getUnresolvedTableReferences().size());
    }

    @Test
    void excludesStaticPartitionFromInsertColumnMapping() {
        CountingMetadata metadata = new CountingMetadata()
                .table("ods.src", "id")
                .table("dw.target", "id", "dt");
        StatementLineage result = parser.parseStatement(request(
                "insert overwrite table dw.target partition(dt='2026-09-07') select id from ods.src", metadata));

        assertEquals(Collections.singletonList("id"), result.getColumnLineages().stream()
                .map(ColumnLineage::getTargetColumn).collect(java.util.stream.Collectors.toList()));
        assertContainsUsage(result, ColumnUsageType.PARTITION_WRITE);
    }

    @Test
    void parsesCtasViewUpdateDeleteAndTracksDerivedSources() {
        CountingMetadata metadata = new CountingMetadata()
                .table("ods.src", "id", "amount")
                .table("dw.target", "id", "amount");
        StatementLineage ctas = parser.parseStatement(request(
                "create table dw.copy as select id, amount * 2 doubled from ods.src", metadata));
        StatementLineage view = parser.parseStatement(request(
                "create view dw.v (key, value) as select id, amount from ods.src", metadata));
        StatementLineage update = parser.parseStatement(request(
                "update dw.target t set amount = amount + 1 where id > 0", metadata));
        StatementLineage delete = parser.parseStatement(request(
                "delete from dw.target where id = 1", metadata));

        assertEquals(SqlStatementType.CREATE_TABLE_AS_SELECT, ctas.getStatementType());
        assertFalse(ctas.getColumnLineages().get(1).getSources().get(0).isDirect());
        assertEquals(Arrays.asList("key", "value"), view.getColumnLineages().stream()
                .map(ColumnLineage::getTargetColumn).collect(java.util.stream.Collectors.toList()));
        assertEquals(TableAccessRole.READ_WRITE, update.getTableAccesses().get(0).getRole());
        assertContainsUsage(update, ColumnUsageType.UPDATE_SET);
        assertEquals(TableAccessRole.READ_WRITE, delete.getTableAccesses().get(0).getRole());
        assertContainsUsage(delete, ColumnUsageType.FILTER);
    }

    @Test
    void preservesQuotedIdentifierContainingDot() {
        TableIdentifier table = TableIdentifier.parse("`db.with.dot`.`table.with.dot`", "", "default");
        assertEquals("db.with.dot", table.getDatabase());
        assertEquals("table.with.dot", table.getTable());
    }

    @Test
    void metadataFailureIsPartialAndQueriedOnce() {
        AtomicInteger calls = new AtomicInteger();
        TableMetadataProvider failing = table -> {
            calls.incrementAndGet();
            throw new IllegalStateException("timeout");
        };
        StatementLineage result = parser.parseStatement(request(
                "select a.*, a.id from db.a a where a.id > 0", failing));
        assertEquals(1, calls.get());
        assertTrue(result.isPartial());
    }

    @Test
    void appliesUseContextAndResolvesTemporaryViewToPhysicalSources() {
        CountingMetadata metadata = new CountingMetadata().table("catalog_a.ods.orders", "id", "amount");
        String scriptSql = "use catalog_a.ods; "
                + "create temporary view v_orders as select id, amount * 2 doubled from orders; "
                + "insert into dwd.summary select id, doubled from v_orders";

        SqlScriptLineage script = parser.parseScript(ParseRequest.builder(scriptSql)
                .dialect(SqlDialect.FLINK).defaultCatalog("paimon").metadataProvider(metadata).build());

        StatementLineage insert = script.getStatements().get(2);
        assertEquals(Arrays.asList("id", "doubled"), script.getStatements().get(1).getColumnLineages().stream()
                .map(ColumnLineage::getTargetColumn).collect(java.util.stream.Collectors.toList()));
        assertEquals(1, script.getStatements().get(1).getColumnLineages().get(1).getSources().size());
        assertEquals(Collections.singletonList("catalog_a.ods.orders"), insert.getInputTables());
        assertEquals(Collections.singletonList("catalog_a.dwd.summary"), insert.getOutputTables());
        assertEquals("catalog_a.ods.orders",
                insert.getColumnLineages().get(0).getSources().get(0).getTable().qualifiedName());
        assertEquals(1, insert.getColumnLineages().get(1).getSources().size());
        assertFalse(insert.getColumnLineages().get(1).getSources().get(0).isDirect());
        assertFalse(script.getInputTables().stream().anyMatch(name -> name.endsWith(".v_orders")));
    }

    @Test
    void acceptsHiveSetContextWithRawValuesAndQueryForms() {
        String sql = "set hive.exec.dynamic.partition.mode=nonstrict;"
                + "set mapreduce.job.queuename=root.data/warehouse:batch;"
                + "set -v; set; select 1";

        SqlScriptLineage result = parser.parseScript(ParseRequest.builder(sql)
                .dialect(SqlDialect.HIVE).build());

        assertEquals(5, result.getStatements().size());
        assertEquals(SqlStatementType.SET, result.getStatements().get(0).getStatementType());
        assertEquals(SqlStatementType.SET, result.getStatements().get(2).getStatementType());
        assertEquals(SqlStatementType.SELECT, result.getStatements().get(4).getStatementType());
        assertTrue(result.getDiagnostics().isEmpty());
    }

    @Test
    void tolerantScriptKeepsQueriesAfterUnsupportedControlCommands() {
        String sql = "echo preparing; truncate table tmp.stage; select id from ods.source";

        SqlScriptLineage result = parser.parseScript(ParseRequest.builder(sql)
                .dialect(SqlDialect.HIVE).mode(ParseMode.TOLERANT).build());

        assertEquals(3, result.getStatements().size());
        assertEquals(SqlStatementType.OTHER, result.getStatements().get(0).getStatementType());
        assertEquals("UNSUPPORTED_STATEMENT",
                result.getStatements().get(1).getDiagnostics().get(0).getCode());
        assertEquals(SqlStatementType.SELECT, result.getStatements().get(2).getStatementType());
        assertEquals(Collections.singletonList("ods.source"), result.getInputTables());
        assertTrue(result.getDiagnostics().isEmpty());
    }

    private ParseRequest request(String sql, TableMetadataProvider metadata) {
        return ParseRequest.builder(sql).metadataProvider(metadata).build();
    }

    private void assertContainsUsage(StatementLineage result, ColumnUsageType type) {
        assertTrue(result.getColumnUsages().stream().anyMatch(item -> item.getType() == type), type.name());
    }

    private static final class CountingMetadata implements TableMetadataProvider {
        private final Map<String, TableSchema> schemas = new LinkedHashMap<>();
        private final Map<String, AtomicInteger> calls = new LinkedHashMap<>();

        private CountingMetadata table(String name, String... columns) {
            TableIdentifier table = TableIdentifier.parse(name, "", "default");
            java.util.List<TableColumnMetadata> values = new java.util.ArrayList<>();
            for (String column : columns) values.add(new TableColumnMetadata(column, "string", false));
            schemas.put(table.normalizedName(), new TableSchema(table, values));
            return this;
        }

        @Override
        public Optional<TableSchema> getTable(TableIdentifier table) {
            calls.computeIfAbsent(table.normalizedName(), ignored -> new AtomicInteger()).incrementAndGet();
            return Optional.ofNullable(schemas.get(table.normalizedName()));
        }

        private int calls(String name) {
            TableIdentifier table = TableIdentifier.parse(name, "", "default");
            AtomicInteger count = calls.get(table.normalizedName());
            return count == null ? 0 : count.get();
        }
    }
}
