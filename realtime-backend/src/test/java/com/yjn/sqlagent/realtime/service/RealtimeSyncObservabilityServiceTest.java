package com.yjn.sqlagent.realtime.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.refEq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yjn.sqlagent.realtime.repository.RealtimeObservabilityRepository;
import com.yjn.sqlagent.realtime.repository.RealtimeSyncRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RealtimeSyncObservabilityServiceTest {
    private final RealtimeObservabilityRepository observability = mock(RealtimeObservabilityRepository.class);
    private final RealtimeSyncRepository tasks = mock(RealtimeSyncRepository.class);
    private final RealtimeRuntimeService runtime = mock(RealtimeRuntimeService.class);
    private final RealtimeServerService servers = mock(RealtimeServerService.class);
    private final RealtimeTableService tables = mock(RealtimeTableService.class);
    private final RealtimeSyncObservabilityService service = new RealtimeSyncObservabilityService(observability, tasks, runtime, servers, tables);

    @Test
    void detectsOnlyAdditiveColumnsAsAutomaticallyApplicable() {
        when(tasks.requiredTask(9L)).thenReturn(Map.of("sourceServerId", 2));
        when(tasks.mappings(9L)).thenReturn(List.of(Map.of("sourceDatabase", "src", "sourceTable", "orders", "targetDatabase", "ods", "targetTable", "orders", "realtimeTableId", 5)));
        when(servers.schema(2L, "orders")).thenReturn(Map.of("columns", List.of(column("id", "BIGINT", false), column("remark", "VARCHAR(64)", true))));
        when(tables.detail(5L)).thenReturn(Map.of("columns", List.of(targetColumn("id", "BIGINT", false))));
        when(observability.schemaEvents(9L)).thenReturn(List.of());

        service.detectSchemaChanges(9L);

        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(observability).upsertSchemaEvent(eq(9L), eq(5L), eq("src"), eq("orders"), eq("ods"), eq("orders"), eq("ADD_COLUMNS"), eq("PENDING"), payload.capture(), eq("检测到 1 个可安全新增字段"));
        List<?> additions = (List<?>) payload.getValue().get("addColumns"); assertEquals(1, additions.size());
    }

    @Test
    void blocksIncompatibleTypeInsteadOfChangingIt() {
        when(tasks.requiredTask(9L)).thenReturn(Map.of("sourceServerId", 2));
        when(tasks.mappings(9L)).thenReturn(List.of(Map.of("sourceDatabase", "src", "sourceTable", "orders", "targetDatabase", "ods", "targetTable", "orders", "realtimeTableId", 5)));
        when(servers.schema(2L, "orders")).thenReturn(Map.of("columns", List.of(column("id", "VARCHAR", false))));
        when(tables.detail(5L)).thenReturn(Map.of("columns", List.of(targetColumn("id", "BIGINT", false))));
        when(observability.schemaEvents(9L)).thenReturn(List.of());

        service.detectSchemaChanges(9L);

        verify(observability).upsertSchemaEvent(eq(9L), eq(5L), eq("src"), eq("orders"), eq("ods"), eq("orders"), eq("INCOMPATIBLE"), eq("BLOCKED"), refEq(Map.of("incompatibleColumns", List.of(Map.of("column", "id", "sourceType", "VARCHAR", "targetType", "BIGINT")))), eq("存在不兼容字段类型，必须人工迁移"));
    }

    @Test
    void refreshedProgressPersistsRuntimeSnapshotAndReturnsStoredView() {
        Map<String, Object> sync = Map.of("sourceLagMs", 120L, "snapshotSplitsFinished", 3, "snapshotSplitsRemaining", 1);
        when(runtime.runtime(9L, 22L)).thenReturn(Map.of("sync", sync));
        when(observability.progress(9L, 22L)).thenReturn(Map.of("sourceLagMs", 120L));

        Map<String, Object> result = service.progress(9L, 22L, true);

        verify(tasks).requiredInstance(9L, 22L);
        verify(observability).upsertProgress(9L, 22L, sync);
        assertEquals(120L, result.get("sourceLagMs"));
    }

    @Test
    void unavailableRuntimeFallsBackToLastProgressSnapshot() {
        when(runtime.runtime(9L, 22L)).thenThrow(new IllegalStateException("Flink unavailable"));
        when(observability.progress(9L, 22L)).thenReturn(Map.of("sourceLagMs", 450L));

        Map<String, Object> result = service.progress(9L, 22L, true);

        verify(observability, never()).upsertProgress(eq(9L), eq(22L), anyMap());
        assertEquals(450L, result.get("sourceLagMs"));
    }

    @Test
    void dirtyRecordRequiresAnErrorAndValidatesInstanceOwnership() {
        assertThrows(IllegalArgumentException.class,
                () -> service.addDirty(9L, 22L, Map.of("rawPayload", Map.of("id", 1))));
        verify(tasks, never()).requiredInstance(9L, 22L);

        when(observability.insertDirty(eq(9L), eq(22L), anyMap())).thenReturn(77L);
        long id = service.addDirty(9L, 22L, Map.of("errorMessage", "字段转换失败", "rawPayload", Map.of("id", 1)));

        verify(tasks).requiredInstance(9L, 22L);
        assertEquals(77L, id);
    }

    @Test
    void dirtyRecordPaginationIsBounded() {
        assertThrows(IllegalArgumentException.class, () -> service.dirtyPage(9L, true, 0, 20));
        assertThrows(IllegalArgumentException.class, () -> service.dirtyPage(9L, true, 1, 101));
    }

    @Test
    void appliesOnlyPendingAddColumnEventToItsOwnTask() {
        Map<String, Object> addition = Map.of("name", "remark", "dataType", "STRING", "nullable", true);
        when(observability.requiredSchemaEvent(17L)).thenReturn(Map.of(
                "taskId", 9L, "realtimeTableId", 5L, "status", "PENDING",
                "changeType", "ADD_COLUMNS", "change", Map.of("addColumns", List.of(addition))));
        when(tables.applySyncEvolution(eq(5L), anyMap(), eq("admin"))).thenReturn(Map.of("applied", true));

        Map<String, Object> result = service.applySchemaChange(9L, 17L, "admin");

        assertEquals(true, result.get("applied"));
        verify(tables).applySyncEvolution(eq(5L), eq(Map.of("addColumns", List.of(addition))), eq("admin"));
        verify(observability).markSchemaApplied(17L, "admin");
    }

    @Test
    void rejectsForeignOrBlockedSchemaEvents() {
        when(observability.requiredSchemaEvent(17L)).thenReturn(Map.of(
                "taskId", 10L, "realtimeTableId", 5L, "status", "PENDING",
                "changeType", "ADD_COLUMNS", "change", Map.of("addColumns", List.of())));
        assertThrows(IllegalArgumentException.class, () -> service.applySchemaChange(9L, 17L, "admin"));

        when(observability.requiredSchemaEvent(18L)).thenReturn(Map.of(
                "taskId", 9L, "realtimeTableId", 5L, "status", "BLOCKED",
                "changeType", "INCOMPATIBLE", "change", Map.of("incompatibleColumns", List.of())));
        assertThrows(IllegalStateException.class, () -> service.applySchemaChange(9L, 18L, "admin"));
        verify(tables, never()).applySyncEvolution(eq(5L), anyMap(), eq("admin"));
    }

    @Test
    void identicalSchemasDoNotCreateAnEvolutionEvent() {
        when(tasks.requiredTask(9L)).thenReturn(Map.of("sourceServerId", 2));
        when(tasks.mappings(9L)).thenReturn(List.of(Map.of("sourceDatabase", "src", "sourceTable", "orders", "targetDatabase", "ods", "targetTable", "orders", "realtimeTableId", 5)));
        when(servers.schema(2L, "orders")).thenReturn(Map.of("columns", List.of(column("id", "BIGINT", false))));
        when(tables.detail(5L)).thenReturn(Map.of("columns", List.of(targetColumn("id", "BIGINT", false))));
        when(observability.schemaEvents(9L)).thenReturn(List.of());

        service.detectSchemaChanges(9L);

        verify(observability, never()).upsertSchemaEvent(eq(9L), eq(5L), eq("src"), eq("orders"), eq("ods"), eq("orders"),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), anyMap(), org.mockito.ArgumentMatchers.anyString());
    }

    private Map<String, Object> column(String name, String type, boolean nullable) { return Map.of("name", name, "type", type, "nullable", nullable); }
    private Map<String, Object> targetColumn(String name, String type, boolean nullable) { return Map.of("name", name, "dataType", type, "nullable", nullable); }
}
