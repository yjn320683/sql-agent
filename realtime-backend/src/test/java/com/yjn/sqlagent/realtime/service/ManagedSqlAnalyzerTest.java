package com.yjn.sqlagent.realtime.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class ManagedSqlAnalyzerTest {

    private final ManagedSqlAnalyzer analyzer = new ManagedSqlAnalyzer();

    @Test
    void extractsManagedTablesFromStatementSet() {
        ManagedSqlAnalyzer.Analysis analysis = analyzer.analyze(
                "SET 'pipeline.name' = 'orders';\n"
                + "CREATE TEMPORARY VIEW v_orders AS SELECT * FROM ods.orders;\n"
                + "EXECUTE STATEMENT SET BEGIN\n"
                + "  INSERT INTO dwd.order_summary SELECT * FROM v_orders;\n"
                + "  INSERT INTO dwd.order_audit SELECT * FROM ods.orders;\n"
                + "END;", "default");

        assertEquals(List.of("paimon.ods.orders"), analysis.getInputs());
        assertEquals(List.of("paimon.dwd.order_summary", "paimon.dwd.order_audit"), analysis.getOutputs());
        assertEquals(2, analysis.getInsertCount());
    }

    @Test
    void rejectsPhysicalDdlAndUnsafeSet() {
        assertThrows(IllegalArgumentException.class,
                () -> analyzer.analyze("DROP TABLE d.t", "default"));
        assertThrows(IllegalArgumentException.class,
                () -> analyzer.analyze("SET 'fs.default-scheme'='file:///'", "default"));
    }
}
