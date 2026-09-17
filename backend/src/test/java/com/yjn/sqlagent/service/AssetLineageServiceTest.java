package com.yjn.sqlagent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AssetLineageServiceTest {
    @Test
    void impactUnionsAllRequestedColumnsWithoutFallingBackToUnknownTableScope() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("FROM task_lineage_relation"))).thenReturn(List.of(
                relation("id", "order_id"), relation("amount", "order_amount")));
        when(jdbc.queryForList(contains("FROM rt_task_table_reference"))).thenReturn(List.of());
        AssetLineageService service = new AssetLineageService(jdbc);

        Map<String, Object> result = service.impact(Map.of(
                "catalog", "hive", "database", "ods", "table", "orders",
                "columns", List.of("id", "amount"), "changeType", "INCOMPATIBLE_SCHEMA_CHANGE"));

        assertEquals(1, ((List<?>) result.get("affectedTasks")).size());
        assertEquals(2, ((List<?>) result.get("affectedAssets")).size());
        assertTrue(Boolean.TRUE.equals(result.get("fieldImpactKnown")));
        assertTrue(Boolean.TRUE.equals(result.get("complete")));
    }

    private Map<String, Object> relation(String sourceColumn, String targetColumn) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("task_scope", "OFFLINE"); row.put("task_id", 42L);
        row.put("task_name", "订单加工"); row.put("task_type", "offline");
        row.put("relation_kind", "COLUMN_DERIVATION"); row.put("complete_flag", true);
        row.put("source_catalog", "hive"); row.put("source_database", "ods");
        row.put("source_table", "orders"); row.put("source_column", sourceColumn);
        row.put("target_catalog", "hive"); row.put("target_database", "dwd");
        row.put("target_table", "orders"); row.put("target_column", targetColumn);
        return row;
    }
}
