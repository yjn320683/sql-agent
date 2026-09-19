package com.yjn.sqlagent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yjn.sqlagent.model.dto.SqlQueryPreviewDTO;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SqlQueryPreviewServiceTest {
    @Test
    void previewsSingleStatementUsingItsActualStepZero() {
        AgentProxyService agent = mock(AgentProxyService.class);
        when(agent.previewSqlStructure(any())).thenReturn(Map.of(
                "steps", List.of(Map.of(
                        "stepNo", 0,
                        "stepName", "Step 0",
                        "renderedSql", "SELECT 1 AS value")),
                "parameters", Map.of()));
        when(agent.previewHiveQuery(any(), eq("default"), eq(10)))
                .thenReturn(Map.of("rows", List.of(List.of(1))));

        SqlQueryPreviewDTO request = new SqlQueryPreviewDTO();
        request.setSql("SELECT 1 AS value");
        request.setStepNo(0);
        request.setLimit(10);
        request.setDefaultDb("default");

        Map<String, Object> result = new SqlQueryPreviewService(agent).preview(request);

        assertEquals(0, result.get("stepNo"));
        assertEquals("SELECT 1 AS value", result.get("renderedSql"));
    }

    @Test
    void acceptsLegacyStepOneForSingleStatement() {
        AgentProxyService agent = mock(AgentProxyService.class);
        when(agent.previewSqlStructure(any())).thenReturn(Map.of(
                "steps", List.of(Map.of(
                        "stepNo", 0,
                        "stepName", "Step 0",
                        "renderedSql", "SELECT 1"))));
        when(agent.previewHiveQuery(any(), eq(null), eq(100))).thenReturn(Map.of());

        SqlQueryPreviewDTO request = new SqlQueryPreviewDTO();
        request.setSql("SELECT 1");
        request.setStepNo(1);

        assertEquals(0, new SqlQueryPreviewService(agent).preview(request).get("stepNo"));
    }
}
