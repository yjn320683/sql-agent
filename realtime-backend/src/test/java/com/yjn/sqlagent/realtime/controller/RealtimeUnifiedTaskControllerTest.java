package com.yjn.sqlagent.realtime.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yjn.sqlagent.realtime.common.RealtimeActorProvider;
import com.yjn.sqlagent.realtime.model.TaskActionRequest;
import com.yjn.sqlagent.realtime.repository.RealtimeSyncRepository;
import com.yjn.sqlagent.realtime.service.RealtimeRuntimeService;
import com.yjn.sqlagent.realtime.service.RealtimeSyncConfigValidator;
import com.yjn.sqlagent.realtime.service.RealtimeSyncTargetValidationService;
import com.yjn.sqlagent.realtime.service.RealtimeTaskDefinitionService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RealtimeUnifiedTaskControllerTest {
    private RealtimeSyncRepository repository;
    private RealtimeRuntimeService runtime;
    private RealtimeUnifiedTaskController controller;
    private RealtimeTaskDefinitionService definitions;

    @BeforeEach
    void setUp() {
        repository = mock(RealtimeSyncRepository.class);
        RealtimeActorProvider actors = mock(RealtimeActorProvider.class);
        when(actors.requireActor()).thenReturn("tester");
        runtime = mock(RealtimeRuntimeService.class);
        controller = new RealtimeUnifiedTaskController(repository, runtime,
                mock(RealtimeSyncConfigValidator.class), mock(RealtimeSyncTargetValidationService.class), actors);
        definitions = mock(RealtimeTaskDefinitionService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "definitions", definitions);
    }

    @Test
    void validateIsNonPersistingAndReturnsResolvedReferences() {
        com.yjn.sqlagent.realtime.model.UnifiedTaskRequest request =
                new com.yjn.sqlagent.realtime.model.UnifiedTaskRequest();
        request.setTaskType("export"); request.setTaskId(8L); request.setName("export"); request.setOwner("owner");
        request.setFlinkConf(new LinkedHashMap<>(Map.of("parallelism", 1)));
        request.setTaskConfig(new LinkedHashMap<>());
        when(definitions.validate(request, 8L)).thenReturn(
                new RealtimeTaskDefinitionService.References(List.of(11L, 12L), List.of()));

        Map<String, Object> result = controller.validate(request).getData();

        assertTrue(Boolean.TRUE.equals(result.get("valid")));
        assertEquals("export", result.get("taskType"));
        assertEquals(List.of(11L, 12L), result.get("inputTableIds"));
        verify(definitions).validate(request, 8L);
        verify(repository, never()).createTask(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
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
        when(repository.taskType(8L)).thenReturn("sync");
        when(repository.requiredSyncTask(8L)).thenReturn(task);

        Map<String, Object> detail = controller.detail(8L).getData();
        @SuppressWarnings("unchecked") Map<String, Object> config = (Map<String, Object>) detail.get("taskConfig");
        assertEquals(Map.of("alarmType", "task-failed", "alarmGroup", "group-a"), detail.get("alarmConfig"));
        assertEquals(2, ((Map<?, ?>) detail.get("flinkConf")).get("parallelism"));
        assertFalse(config.containsKey("alarmType"));
        assertFalse(config.containsKey("parallelism"));
        assertEquals(7L, config.get("sourceServerId"));
        verify(repository).requiredSyncTask(8L);
        verify(repository, never()).requiredTask(8L);
    }

    @Test
    void productionStopAcceptsDirectCancellation() {
        TaskActionRequest request = new TaskActionRequest();
        request.setStopType("direct");
        when(repository.instances(8L)).thenReturn(List.of(Map.of(
                "id", 30L, "managed", true, "executionMode", "PRODUCTION", "status", "restarting")));
        when(runtime.stop(8L, 30L, request, "tester")).thenReturn(Map.of("operationId", 99L));

        assertEquals(99L, controller.stop(8L, request).getData().get("operationId"));
        verify(runtime).stop(8L, 30L, request, "tester");
    }

    @Test
    void productionStopDefaultsToSavepoint() {
        when(repository.instances(8L)).thenReturn(List.of(Map.of(
                "id", 30L, "managed", true, "executionMode", "PRODUCTION", "status", "running")));
        when(runtime.stop(org.mockito.ArgumentMatchers.eq(8L), org.mockito.ArgumentMatchers.eq(30L),
                org.mockito.ArgumentMatchers.any(TaskActionRequest.class), org.mockito.ArgumentMatchers.eq("tester")))
                .thenReturn(Map.of("operationId", 100L));

        controller.stop(8L, null);

        ArgumentCaptor<TaskActionRequest> action = ArgumentCaptor.forClass(TaskActionRequest.class);
        verify(runtime).stop(org.mockito.ArgumentMatchers.eq(8L), org.mockito.ArgumentMatchers.eq(30L),
                action.capture(), org.mockito.ArgumentMatchers.eq("tester"));
        assertEquals("savepoint", action.getValue().getStopType());
    }

    @Test
    void canEnableReturnsReferenceStartPolicyWithoutLoadingFullTaskDetail() {
        when(repository.taskType(8L)).thenReturn("sync");
        when(repository.latestVersionId(8L)).thenReturn(12L);
        when(repository.instances(8L)).thenReturn(List.of(Map.of(
                "managed", true,
                "executionMode", "DEBUG",
                "versionId", 12L,
                "status", "killed_success")));
        when(repository.editPolicy(8L)).thenReturn(Map.of(
                "productionLocked", true,
                "syncTableSetChanged", false,
                "requiredStartType", "savepoint",
                "requiredStatePath", "hdfs://savepoints/task-8/savepoint-1"));

        Map<String, Object> result = controller.canEnable(8L).getData();

        assertTrue(Boolean.TRUE.equals(result.get("canEnable")));
        @SuppressWarnings("unchecked")
        Map<String, Object> policy = (Map<String, Object>) result.get("startPolicy");
        assertEquals("savepoint", policy.get("requiredStartType"));
        assertEquals("hdfs://savepoints/task-8/savepoint-1", policy.get("requiredStatePath"));
        assertFalse(Boolean.TRUE.equals(policy.get("canResetConsumptionPoint")));
        verify(repository, never()).requiredTask(8L);
        verify(repository, never()).requiredSyncTask(8L);
    }

    @Test
    void productionLockedSyncTaskCanExplicitlyResetConsumptionPointByTimestamp() {
        when(repository.taskType(8L)).thenReturn("sync");
        when(repository.latestVersionId(8L)).thenReturn(12L);
        when(repository.instances(8L)).thenReturn(List.of(Map.of(
                "managed", true, "executionMode", "DEBUG", "versionId", 12L,
                "status", "killed_success")));
        when(repository.editPolicy(8L)).thenReturn(Map.of(
                "productionLocked", true,
                "syncTableSetChanged", true,
                "requiredStartType", "savepoint",
                "requiredStatePath", "hdfs://savepoints/task-8/savepoint-1"));
        TaskActionRequest request = new TaskActionRequest();
        request.setStartType("direct");
        request.setSourceStartupTimestampMillis(1_700_000_000_000L);

        controller.enable(8L, request);

        verify(runtime).start(8L, request, "tester", false);
    }

    @Test
    void productionLockedSyncTaskCanRecoverFromAnyOwnedProductionCheckpoint() {
        when(repository.taskType(8L)).thenReturn("sync");
        when(repository.latestVersionId(8L)).thenReturn(12L);
        when(repository.instances(8L)).thenReturn(List.of(
                Map.of("managed", true, "executionMode", "DEBUG", "versionId", 12L,
                        "status", "killed_success"),
                Map.of("managed", true, "executionMode", "PRODUCTION", "versionId", 11L,
                        "status", "canceled", "jobId", "0123456789abcdef0123456789abcdef")));
        when(repository.editPolicy(8L)).thenReturn(Map.of(
                "productionLocked", true, "syncTableSetChanged", false));
        TaskActionRequest request = new TaskActionRequest();
        request.setStartType("checkpoint");
        request.setStatePath("hdfs://checkpoints/task-8/0123456789abcdef0123456789abcdef/chk-42");

        controller.enable(8L, request);

        verify(runtime).start(8L, request, "tester", false);
    }

    @Test
    void productionLockedSyncTaskCannotRepeatInitialFullStart() {
        when(repository.taskType(8L)).thenReturn("sync");
        when(repository.latestVersionId(8L)).thenReturn(12L);
        when(repository.instances(8L)).thenReturn(List.of(Map.of(
                "managed", true, "executionMode", "DEBUG", "versionId", 12L,
                "status", "killed_success")));
        when(repository.editPolicy(8L)).thenReturn(Map.of(
                "productionLocked", true, "syncTableSetChanged", false));
        TaskActionRequest request = new TaskActionRequest();

        assertEquals("任务已存在正式实例，不能再次首次全量同步；请从 Savepoint、Checkpoint 或指定时间戳启动",
                assertThrows(IllegalArgumentException.class,
                        () -> controller.enable(8L, request)).getMessage());
        verify(runtime, never()).start(8L, request, "tester", false);
    }

    @Test
    void firstProductionStartCannotResetConsumptionPoint() {
        when(repository.taskType(8L)).thenReturn("sync");
        when(repository.latestVersionId(8L)).thenReturn(12L);
        when(repository.instances(8L)).thenReturn(List.of(Map.of(
                "managed", true, "executionMode", "DEBUG", "versionId", 12L,
                "status", "killed_success")));
        when(repository.editPolicy(8L)).thenReturn(Map.of(
                "productionLocked", false,
                "syncTableSetChanged", false));
        TaskActionRequest request = new TaskActionRequest();
        request.setSourceStartupTimestampMillis(1_700_000_000_000L);

        assertEquals("首次正式启动必须执行 initial 全量快照，不能按时间戳重置消费点",
                assertThrows(IllegalArgumentException.class,
                        () -> controller.enable(8L, request)).getMessage());
        verify(runtime, never()).start(8L, request, "tester", false);
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
