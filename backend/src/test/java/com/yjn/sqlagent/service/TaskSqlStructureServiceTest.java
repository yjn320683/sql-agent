package com.yjn.sqlagent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjn.sqlagent.exception.BusinessException;
import com.yjn.sqlagent.model.entity.SqlTaskVersionStep;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskSqlStructureServiceTest {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<List<String>>() { };
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TaskSqlStructureService service = new TaskSqlStructureService(objectMapper);

    @Test
    void parsesStepsAndPersistsTableRelations() throws Exception {
        String sql = "====step:1:prepare====\n"
                + "WITH src AS (SELECT id FROM ods.orders) "
                + "INSERT OVERWRITE TABLE tmp.orders SELECT id FROM src;\n"
                + "====step:2:publish====\n"
                + "INSERT INTO TABLE dw.orders SELECT id FROM tmp.orders";

        List<SqlTaskVersionStep> steps = service.parseVersionSteps(7L, 3, sql);

        assertEquals(2, steps.size());
        assertEquals("WITH", steps.get(0).getStatementType());
        assertEquals(Arrays.asList("ods.orders"), tables(steps.get(0).getInputTables()));
        assertEquals(Arrays.asList("tmp.orders"), tables(steps.get(0).getOutputTables()));
        assertEquals(Arrays.asList("tmp.orders"), tables(steps.get(1).getInputTables()));
        assertEquals(Arrays.asList("dw.orders"), tables(steps.get(1).getOutputTables()));
    }

    @Test
    void allowsSemicolonAndRuntimeParameterInsideExpression() {
        List<SqlTaskVersionStep> steps = service.parseVersionSteps(
                7L, 3, "SELECT ';' AS marker, ${limit_value} AS amount FROM ods.orders;");

        assertEquals(1, steps.size());
        assertEquals("SELECT", steps.get(0).getStatementType());
    }

    @Test
    void keepsExecutionWhitelistOutsideGrammar() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.parseVersionSteps(7L, 3,
                        "CREATE TABLE tmp.result AS SELECT * FROM ods.orders"));

        assertTrue(error.getMessage().contains("只允许SELECT、WITH或INSERT"));
    }

    @Test
    void versionAnalysisReusesOneParseForStepsAndCompleteLineageFacts() {
        TaskSqlStructureService.VersionAnalysis analysis = service.analyzeVersion(7L, 3,
                "INSERT INTO dw.orders SELECT o.id FROM ods.orders o "
                        + "JOIN ods.customers c ON o.customer_id=c.id WHERE c.active=1");

        Map<String, Object> facts = analysis.getLineage().toJson();
        @SuppressWarnings("unchecked") List<Map<String, Object>> statements =
                (List<Map<String, Object>>) facts.get("statements");
        assertEquals(1, analysis.getSteps().size());
        assertEquals(1, statements.size());
        assertTrue(((List<?>) statements.get(0).get("columnLineages")).size() > 0);
        assertTrue(((List<?>) statements.get(0).get("joins")).size() > 0);
        assertTrue(((List<?>) statements.get(0).get("columnUsages")).size() > 0);
    }

    private List<String> tables(String json) throws Exception {
        return objectMapper.readValue(json, STRING_LIST);
    }
}
