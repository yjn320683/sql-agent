package com.yjn.sqlagent.realtime.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.yjn.sqlagent.realtime.model.UnifiedTaskRequest;
import com.yjn.sqlagent.realtime.model.RealtimeLineageSnapshotDraft;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjn.sqlagent.realtime.repository.RealtimeSyncRepository;
import com.yjn.sqlagent.realtime.repository.RealtimeTableRepository;
import com.yjn.sqlagent.realtime.repository.RealtimeTaskDefinitionRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RealtimeTaskDefinitionServiceTest {
    private RealtimeTableRepository tables;
    private RealtimeSyncRepository sync;
    private RealtimeServerService servers;
    private RealtimePaimonCatalogService paimon;
    private ManagedFlinkPlannerService planner;
    private RealtimeTaskDefinitionRepository repository;
    private RealtimeTaskDefinitionService service;

    @BeforeEach
    void setUp() {
        tables = mock(RealtimeTableRepository.class);
        sync = mock(RealtimeSyncRepository.class);
        servers = mock(RealtimeServerService.class);
        paimon = mock(RealtimePaimonCatalogService.class);
        planner = mock(ManagedFlinkPlannerService.class);
        repository = mock(RealtimeTaskDefinitionRepository.class);
        service = new RealtimeTaskDefinitionService(repository, tables,
                sync, servers, planner, paimon, new ObjectMapper());
    }

    @Test
    void computeOnlyUsesAvailableManagedTablesAndResolvesInputOutputIds() {
        Map<String, Object> input = table(1L, "ods", "orders", null);
        Map<String, Object> output = table(2L, "dwd", "order_summary", null);
        when(tables.available()).thenReturn(List.of(input, output));
        when(tables.required(1L)).thenReturn(input);
        when(tables.required(2L)).thenReturn(output);
        when(paimon.describe("ods", "orders")).thenReturn(input);
        when(paimon.describe("dwd", "order_summary")).thenReturn(output);

        when(planner.validate("INSERT INTO dwd.order_summary SELECT * FROM ods.orders", "ods"))
                .thenReturn(analysis(List.of("paimon.ods.orders"), List.of("paimon.dwd.order_summary")));
        RealtimeTaskDefinitionService.References references = service.validate(compute(
                "INSERT INTO dwd.order_summary SELECT * FROM ods.orders"), null);

        assertEquals(List.of(1L), references.getInputs());
        assertEquals(List.of(2L), references.getOutputs());
    }

    @Test
    void computeRejectsUnregisteredAndAlreadyProducedOutputTables() {
        Map<String, Object> input = table(1L, "ods", "orders", null);
        Map<String, Object> output = table(2L, "dwd", "order_summary", 99L);
        when(tables.available()).thenReturn(List.of(input, output));
        when(tables.required(1L)).thenReturn(input);
        when(tables.required(2L)).thenReturn(output);
        when(paimon.describe("ods", "orders")).thenReturn(input);
        when(paimon.describe("dwd", "order_summary")).thenReturn(output);
        when(planner.validate("INSERT INTO dwd.order_summary SELECT * FROM ods.orders", "ods"))
                .thenReturn(analysis(List.of("paimon.ods.orders"), List.of("paimon.dwd.order_summary")));
        when(planner.validate("INSERT INTO dwd.missing SELECT * FROM ods.orders", "ods"))
                .thenReturn(analysis(List.of("paimon.ods.orders"), List.of("paimon.dwd.missing")));

        assertEquals("输出表已绑定其他生产任务：paimon.dwd.order_summary",
                assertThrows(IllegalStateException.class, () -> service.validate(compute(
                        "INSERT INTO dwd.order_summary SELECT * FROM ods.orders"), 7L)).getMessage());
        assertThrows(IllegalArgumentException.class, () -> service.validate(compute(
                "INSERT INTO dwd.missing SELECT * FROM ods.orders"), null));
    }

    @Test
    void computeSnapshotKeepsParserCompletenessAndDiagnostics() {
        Map<String, Object> input = table(1L, "ods", "orders", null);
        Map<String, Object> output = table(2L, "dwd", "order_summary", null);
        when(tables.available()).thenReturn(List.of(input, output));
        when(tables.required(1L)).thenReturn(input);
        when(tables.required(2L)).thenReturn(output);
        when(paimon.describe("ods", "orders")).thenReturn(input);
        when(paimon.describe("dwd", "order_summary")).thenReturn(output);
        Map<String, Object> diagnostic = Map.of("code", "METADATA_UNAVAILABLE", "severity", "WARNING");
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("complete", false);
        facts.put("diagnostics", List.of(diagnostic));
        when(planner.validate("INSERT INTO dwd.order_summary SELECT * FROM ods.orders", "ods"))
                .thenReturn(new ManagedFlinkPlannerService.Analysis(
                        List.of("paimon.ods.orders"), List.of("paimon.dwd.order_summary"),
                        1, 1, "plan", facts));

        service.create(compute("INSERT INTO dwd.order_summary SELECT * FROM ods.orders"), "tester");

        ArgumentCaptor<RealtimeLineageSnapshotDraft> snapshot =
                ArgumentCaptor.forClass(RealtimeLineageSnapshotDraft.class);
        verify(repository).create(org.mockito.ArgumentMatchers.any(), eq("tester"), eq(List.of(1L)),
                eq(List.of(2L)), snapshot.capture());
        assertFalse(snapshot.getValue().isComplete());
        assertEquals(List.of(diagnostic), snapshot.getValue().getDiagnostics());
        assertEquals(false, snapshot.getValue().getLineage().get("complete"));
    }

    @Test
    void exportRequiresExistingMysqlTableAndCompletePrimaryKeyMapping() {
        Map<String, Object> source = table(1L, "ods", "orders", null);
        when(tables.required(1L)).thenReturn(source);
        when(paimon.describe("ods", "orders")).thenReturn(source);
        when(sync.requiredServer(7L, false)).thenReturn(Map.of("databaseName", "sink_db"));
        when(servers.tables(7L)).thenReturn(List.of("orders_sink"));
        Map<String, Object> sinkSchema = Map.of(
                "primaryKeys", List.of("id"),
                "columns", List.of(
                        Map.of("name", "id", "type", "bigint", "nullable", false),
                        Map.of("name", "payload", "type", "varchar", "nullable", true)));
        when(servers.schemas(7L, List.of("orders_sink"))).thenReturn(Map.of("orders_sink", sinkSchema));

        UnifiedTaskRequest request = export(List.of(
                new LinkedHashMap<>(Map.of("realtimeTableId", 1L, "targetTable", "orders_sink",
                        "columnMappings", List.of(
                                Map.of("sourceColumn", "id", "targetColumn", "id"),
                                Map.of("sourceColumn", "payload", "targetColumn", "payload"))))));
        RealtimeTaskDefinitionService.References references = service.validate(request, null);
        assertEquals(List.of(1L), references.getInputs());
        @SuppressWarnings("unchecked") Map<String, Object> exportConfig = (Map<String, Object>) request.getTaskConfig().get("exportConfig");
        assertEquals(1, ((List<?>) exportConfig.get("schemaContracts")).size());
        verify(servers).schemas(7L, List.of("orders_sink"));

        UnifiedTaskRequest missingKey = export(List.of(new LinkedHashMap<>(Map.of(
                "realtimeTableId", 1L, "targetTable", "orders_sink",
                "columnMappings", List.of(Map.of("sourceColumn", "payload", "targetColumn", "payload"))))));
        assertEquals("MySQL 主键未完整映射：orders_sink.id",
                assertThrows(IllegalArgumentException.class, () -> service.validate(missingKey, null)).getMessage());
    }

    private UnifiedTaskRequest compute(String sql) {
        UnifiedTaskRequest request = common("compute");
        request.setTaskConfig(Map.of("computeConfig", Map.of("defaultDatabase", "ods", "sql", sql)));
        return request;
    }

    private ManagedFlinkPlannerService.Analysis analysis(List<String> inputs, List<String> outputs) {
        return new ManagedFlinkPlannerService.Analysis(inputs, outputs, 1, 1, "plan");
    }

    private UnifiedTaskRequest export(List<Map<String, Object>> mappings) {
        UnifiedTaskRequest request = common("export");
        request.setTaskConfig(Map.of("exportConfig", Map.of(
                "sourceDatabase", "ods", "targetServerId", 7L, "mappings", mappings)));
        return request;
    }

    private UnifiedTaskRequest common(String type) {
        UnifiedTaskRequest request = new UnifiedTaskRequest();
        request.setTaskType(type); request.setName("test"); request.setOwner("tester");
        request.setFlinkConf(Map.of("parallelism", 1));
        return request;
    }

    private Map<String, Object> table(long id, String database, String name, Long producerTaskId) {
        List<Map<String, Object>> columns = new ArrayList<>();
        columns.add(Map.of("name", "id", "dataType", "BIGINT", "nullable", false,
                "primaryKey", true, "partitionKey", false));
        columns.add(Map.of("name", "payload", "dataType", "STRING", "nullable", true,
                "primaryKey", false, "partitionKey", false));
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("id", id); table.put("databaseName", database); table.put("tableName", name);
        table.put("physicalStatus", "active"); table.put("producerTaskId", producerTaskId);
        table.put("columns", columns);
        return table;
    }
}
