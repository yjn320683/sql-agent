package com.yjn.sqlagent.parsesql;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 来自两个参考项目能力清单的脱敏、确定性回归样本。 */
class ReferenceCapabilityRegressionTest {
    private final SqlLineageParser parser = new SqlLineageParser();

    @Test
    void parsesHiveAndTrinoCapabilitySamples() {
        List<String> samples = Arrays.asList(
                "select goods_code, count(*), concat_ws(',', collect_set(name)) from tmp.goods group by goods_code",
                "select t.*, lv.* from testdb.movie_info t lateral view explode(array(1,2,3)) lv as item",
                "select movie_info.*, u.* from movie_info, unnest(array[1,2,3]) as u(item)",
                "select cast(from_utc_timestamp(expire_dt,'Asia/Shanghai') as decimal(10,0)) from rpt.task",
                "select cast(at_timezone(with_timezone(timestamp '2026-09-07 08:00:00','UTC'),'Asia/Shanghai') as timestamp)",
                "select rank() over(partition by subject order by score desc) rank_no from score",
                "select transform(items, (x) -> cast(x as bigint)) from db.events",
                "select region, category, sum(amount) from db.sales group by grouping sets ((region),(category),())",
                "select region, sum(amount) from db.sales group by rollup(region)",
                "select region, category, sum(amount) from db.sales group by cube(region,category)",
                "select case when amount > 0 then cast(amount as decimal(18,2)) else 0 end value from db.sales",
                "select * from emp a where exists(select 1 from emp b where a.manager_id=b.id)",
                "select * from db.a where id in (select id from db.b union select id from db.c)"
        );
        for (String sql : samples) {
            assertDoesNotThrow(() -> parser.parseStatement(ParseRequest.builder(sql)
                    .dialect(SqlDialect.HIVE).mode(ParseMode.STRICT).build()), sql);
        }
    }

    @Test
    void cachesMetadataAcrossWholeScript() {
        AtomicInteger calls = new AtomicInteger();
        TableMetadataProvider metadata = table -> {
            calls.incrementAndGet();
            return Optional.of(new TableSchema(table,
                    Collections.singletonList(new TableColumnMetadata("id", "bigint", false))));
        };
        SqlScriptLineage result = parser.parseScript(ParseRequest.builder(
                "select * from db.source; insert into db.target select * from db.source")
                .metadataProvider(metadata).build());

        assertEquals(2, calls.get()); // source 一次、target 一次
        assertEquals(2, result.getStatements().size());
    }

    @Test
    void parserInstanceIsThreadSafeAndDeterministic() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            Callable<String> task = () -> parser.parseStatement(ParseRequest.builder(
                    "with x as (select id from db.a) select id from x").build())
                    .getColumnLineages().get(0).getExpression();
            List<Future<String>> futures = pool.invokeAll(Collections.nCopies(60, task));
            for (Future<String> future : futures) assertEquals("id", future.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void syntaxDiagnosticContainsSourceRange() {
        SqlScriptLineage result = parser.parseScript(ParseRequest.builder("select from broken")
                .mode(ParseMode.TOLERANT).build());
        assertEquals(1, result.getDiagnostics().size());
        assertTrue(result.getDiagnostics().get(0).getStartOffset() >= 0);
        assertTrue(result.getDiagnostics().get(0).getEndOffset()
                > result.getDiagnostics().get(0).getStartOffset());
    }
}
