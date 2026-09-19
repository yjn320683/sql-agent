package com.yjn.sqlagent.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SchemaMigrationStatusServiceTest {
    @Test
    void untrackedSchemaIsNotReady() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("flyway_schema_history"))).thenReturn(0);
        completeContract(jdbc);

        Map<String, Object> result = new SchemaMigrationStatusService(jdbc).status();

        assertFalse((Boolean) result.get("compatible"));
        assertFalse((Boolean) result.get("tracked"));
    }

    @Test
    void requiredOrNewerVersionIsReady() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("flyway_schema_history"))).thenReturn(1);
        when(jdbc.queryForList(contains("flyway_schema_history"), eq(String.class)))
                .thenReturn(List.of(SchemaMigrationStatusService.REQUIRED_VERSION));
        completeContract(jdbc);

        assertTrue(new SchemaMigrationStatusService(jdbc).compatible());
        assertTrue(SchemaMigrationStatusService.compareVersions("20260919.1", "20260918.01") > 0);
    }

    @Test
    void matchingVersionWithMissingColumnIsNotReady() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("flyway_schema_history"))).thenReturn(1);
        when(jdbc.queryForList(contains("flyway_schema_history"), eq(String.class)))
                .thenReturn(List.of(SchemaMigrationStatusService.REQUIRED_VERSION));
        when(jdbc.queryForList(contains("information_schema.columns"), eq(String.class)))
                .thenReturn(List.of("chat_session.context_id", "chat_session.context_title",
                        "task_lineage_snapshot.lineage_json", "data_map_graph_outbox.status",
                        "data_map_graph_outbox.available_at", "data_map_graph_outbox.locked_at"));
        when(jdbc.queryForList(contains("information_schema.statistics"), eq(String.class)))
                .thenReturn(List.of("chat_session.idx_chat_session_context",
                        "data_map_graph_outbox.idx_data_map_outbox_recovery"));

        Map<String, Object> result = new SchemaMigrationStatusService(jdbc).status();

        assertFalse((Boolean) result.get("compatible"));
        assertTrue(String.valueOf(result.get("missingContracts")).contains("chat_session.context_type"));
    }

    private void completeContract(JdbcTemplate jdbc) {
        when(jdbc.queryForList(contains("information_schema.columns"), eq(String.class)))
                .thenReturn(List.of("chat_session.context_type", "chat_session.context_id",
                        "chat_session.context_title", "task_lineage_snapshot.lineage_json",
                        "data_map_graph_outbox.status", "data_map_graph_outbox.available_at",
                        "data_map_graph_outbox.locked_at"));
        when(jdbc.queryForList(contains("information_schema.statistics"), eq(String.class)))
                .thenReturn(List.of("chat_session.idx_chat_session_context",
                        "data_map_graph_outbox.idx_data_map_outbox_recovery"));
    }
}
