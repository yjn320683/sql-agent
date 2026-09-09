package com.yjn.sqlagent.realtime.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yjn.sqlagent.realtime.common.RealtimeActorProvider;
import com.yjn.sqlagent.realtime.model.TaskActionRequest;
import com.yjn.sqlagent.realtime.repository.RealtimeSyncRepository;
import com.yjn.sqlagent.realtime.service.RealtimeRuntimeService;
import com.yjn.sqlagent.realtime.service.RealtimeSyncConfigValidator;
import com.yjn.sqlagent.realtime.service.RealtimeSyncTargetValidationService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RealtimeUnifiedTaskControllerTest {
    private RealtimeSyncRepository repository;
    private RealtimeUnifiedTaskController controller;

    @BeforeEach
    void setUp() {
        repository = mock(RealtimeSyncRepository.class);
        RealtimeActorProvider actors = mock(RealtimeActorProvider.class);
        when(actors.requireActor()).thenReturn("tester");
        controller = new RealtimeUnifiedTaskController(repository, mock(RealtimeRuntimeService.class),
                mock(RealtimeSyncConfigValidator.class), mock(RealtimeSyncTargetValidationService.class), actors);
    }

    @Test
    void pageExposesReferenceLatestInstanceAliasesAndMappings() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 8L); row.put("runtimeStatus", "running"); row.put("executionMode", "PRODUCTION");
        when(repository.taskPage(org.mockito.ArgumentMatchers.any())).thenReturn(Map.of(
                "items", List.of(row), "total", 1L, "page", 1, "pageSize", 20));
        when(repository.mappings(8L)).thenReturn(List.of(Map.of("id", 3L)));

        Map<String, Object> page = controller.page(Map.of(
                "taskType", "sync", "pageNo", 1, "pageSize", 20)).getData();
        @SuppressWarnings("unchecked") Map<String, Object> result = ((List<Map<String, Object>>) page.get("records")).get(0);
        assertEquals("running", result.get("latestInstanceStatus"));
        assertEquals("PRODUCTION", result.get("latestInstanceExecutionMode"));
        assertEquals(1, ((List<?>) result.get("mapping")).size());
    }

    @Test
    void detailSeparatesCommonAlarmAndFlinkConfiguration() {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("id", 8L); task.put("sourceServerId", 7L); task.put("sourceType", "mysql-cdc");
        task.put("taskConfig", new LinkedHashMap<>(Map.of(
                "alarmType", "task-failed", "alarmGroup", "group-a", "parallelism", 2,
                "checkpointInterval", 30, "taskManagerMemory", "3GB", "jobManagerMemory", "1GB",
                "flinkConfOverrides", Map.of("execution.checkpointing.mode", "EXACTLY_ONCE"),
                "cdcConfig", Map.of("selectedTables", List.of("orders")))));
        when(repository.requiredTask(8L)).thenReturn(task);

        Map<String, Object> detail = controller.detail(8L).getData();
        @SuppressWarnings("unchecked") Map<String, Object> config = (Map<String, Object>) detail.get("taskConfig");
        assertEquals(Map.of("alarmType", "task-failed", "alarmGroup", "group-a"), detail.get("alarmConfig"));
        assertEquals(2, ((Map<?, ?>) detail.get("flinkConf")).get("parallelism"));
        assertFalse(config.containsKey("alarmType"));
        assertFalse(config.containsKey("parallelism"));
        assertEquals(7L, config.get("sourceServerId"));
    }

    @Test
    void productionStopRejectsDirectCancellation() {
        TaskActionRequest request = new TaskActionRequest();
        request.setStopType("direct");
        assertEquals("正式实例仅支持 savepoint 停止",
                assertThrows(IllegalArgumentException.class,
                        () -> controller.stop(8L, request)).getMessage());
    }

    @Test
    void pageUsesReferenceSearchFieldsAndLengthLimit() {
        when(repository.taskPage(org.mockito.ArgumentMatchers.any())).thenReturn(Map.of(
                "items", List.of(), "total", 0L, "page", 1, "pageSize", 20));

        controller.page(Map.of(
                "taskType", "sync",
                "pageNo", 3,
                "pageSize", 50,
                "keyword", "order sync",
                "status", "running",
                "owner", "owner-a",
                "lastOperator", "operator-b",
                "paimonTableKeyword", "orders",
                "sortField", "name",
                "sortOrder", "asc"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> query = ArgumentCaptor.forClass(Map.class);
        verify(repository).taskPage(query.capture());
        Map<String, String> value = query.getValue();
        assertEquals("3", value.get("page"));
        assertEquals("50", value.get("pageSize"));
        assertEquals("order sync", value.get("keyword"));
        assertEquals("running", value.get("status"));
        assertEquals("owner-a", value.get("owner"));
        assertEquals("operator-b", value.get("lastOperator"));
        assertEquals("orders", value.get("targetKeyword"));
        assertEquals("name", value.get("sort"));
        assertEquals("asc", value.get("order"));
        assertEquals("来源关键字长度不能超过 200 个字符",
                assertThrows(IllegalArgumentException.class, () -> controller.page(Map.of(
                        "taskType", "sync", "sourceKeyword", "x".repeat(201)))).getMessage());
    }
}
