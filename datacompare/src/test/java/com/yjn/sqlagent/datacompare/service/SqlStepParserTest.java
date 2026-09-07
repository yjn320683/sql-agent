package com.yjn.sqlagent.datacompare.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yjn.sqlagent.datacompare.model.SqlStep;
import com.yjn.sqlagent.parsesql.SqlLineageParser;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class SqlStepParserTest {
    private final SqlStepParser parser = new SqlStepParser(new SqlLineageParser());

    @Test
    void parsesStepMarkersAndAddsRequiredProducer() {
        String sql = "====step:1====\n"
                + "INSERT OVERWRITE TABLE tmp.base SELECT id FROM ods.source;\n"
                + "====step:2====\n"
                + "INSERT OVERWRITE TABLE dw.target SELECT id FROM tmp.base;";

        List<SqlStep> steps = parser.parse(sql);
        List<SqlStep> selected = parser.selected(steps, Collections.singletonList("Step(2-1)"));

        assertEquals(2, steps.size());
        assertTrue(steps.get(0).getOutputTables().contains("tmp.base"));
        assertTrue(steps.get(1).getRequiredSteps().contains("Step(1-1)"));
        assertEquals(2, selected.size());
    }

    @Test
    void rewriteOnlyChangesKnownOutputTables() {
        List<SqlStep> steps = parser.parse(
                "INSERT OVERWRITE TABLE dw.target SELECT 'dw.target' label FROM ods.source;");

        String rewritten = parser.rewrite(steps, Collections.singletonMap("dw.target", "verify.dc_1"));

        assertTrue(rewritten.contains("verify.dc_1"));
        assertTrue(rewritten.contains("ods.source"));
        assertTrue(rewritten.contains("'dw.target'"));
    }
}
