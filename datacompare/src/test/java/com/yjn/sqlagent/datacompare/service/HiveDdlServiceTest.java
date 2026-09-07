package com.yjn.sqlagent.datacompare.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yjn.sqlagent.datacompare.model.TableColumn;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HiveDdlServiceTest {
    private final HiveDdlService service = new HiveDdlService();

    @Test
    void onlyAllowsAddAndChangeAndRewritesTarget() {
        String ddl = "ALTER TABLE dw.orders ADD COLUMNS (remark STRING);\n"
                + "ALTER TABLE dw.items CHANGE COLUMN amount amount DECIMAL(18,2);";
        Map<String, String> mappings = new LinkedHashMap<>();
        mappings.put("dw.orders", "verify.orders_debug");
        mappings.put("dw.items", "verify.items_debug");
        String rewritten = service.rewrite(ddl, mappings);
        assertTrue(rewritten.contains("orders_debug"));
        assertTrue(rewritten.contains("items_debug"));
        assertFalse(rewritten.contains("dw.orders"));
    }

    @Test
    void rejectsDropAndCrossDdlConflicts() {
        assertThrows(IllegalArgumentException.class,
                () -> service.parse("DROP TABLE dw.orders"));
        assertThrows(IllegalArgumentException.class, () -> service.requireNoOverlap(
                "ALTER TABLE dw.orders ADD COLUMNS (a STRING)",
                Collections.singletonList("ALTER TABLE dw.orders CHANGE COLUMN a a BIGINT")));
        assertEquals(1, service.affectedTables(
                "ALTER TABLE `dw`.`orders` ADD COLUMN b STRING").size());
    }

    @Test
    void lexerSplitterIgnoresSemicolonsInCommentsAndQuotedIdentifiers() {
        assertEquals(2, service.parse("-- keep ; in comment\n"
                + "ALTER TABLE `dw`.`orders` ADD COLUMNS (`a;b` STRING);"
                + "/* another ; */ ALTER TABLE dw.items ADD COLUMN note STRING;").size());
    }

    @Test
    void retrySkipsOnlyWhenAllMetadataAlreadyMatches() {
        HiveDdlService.DdlStatement add = service.parse(
                "ALTER TABLE dw.orders ADD COLUMNS (remark STRING, tags ARRAY<STRING>)").get(0);
        assertTrue(service.alreadyApplied(add, Arrays.asList(
                new TableColumn("remark", "string", null, false),
                new TableColumn("tags", "array<string>", null, false))));
        assertFalse(service.alreadyApplied(add, Collections.emptyList()));
        assertThrows(IllegalArgumentException.class, () -> service.alreadyApplied(add,
                Collections.singletonList(new TableColumn("remark", "string", null, false))));
        assertThrows(IllegalArgumentException.class, () -> service.alreadyApplied(add, Arrays.asList(
                new TableColumn("remark", "bigint", null, false),
                new TableColumn("tags", "array<string>", null, false))));
    }

    @Test
    void retryRecognizesAppliedChangeColumn() {
        HiveDdlService.DdlStatement change = service.parse(
                "ALTER TABLE dw.orders CHANGE COLUMN amount total DECIMAL(18, 2)").get(0);
        assertTrue(service.alreadyApplied(change,
                Collections.singletonList(new TableColumn("total", "decimal(18,2)", null, false))));
    }
}
