package com.yjn.sqlagent.realtime.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjn.sqlagent.realtime.config.RealtimeProperties;
import com.yjn.sqlagent.realtime.model.TaskActionRequest;
import com.yjn.sqlagent.realtime.model.SyncTaskRequest;
import com.yjn.sqlagent.realtime.repository.RealtimeSyncRepository;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RealtimeRuntimeServiceManagedInstanceTest {

    private RealtimeSyncRepository repository;
    private RealtimeRuntimeService service;

    @BeforeEach
    void setUp() {
        repository = mock(RealtimeSyncRepository.class);
        service = new RealtimeRuntimeService(repository, new RealtimeProperties(), new ObjectMapper());
    }

    @Test
    void importedInstanceCannotBeStoppedOrRefreshed() {
        when(repository.requiredInstance(8L, 9L)).thenReturn(Map.of(
                "id", 9L, "taskId", 8L, "managed", false, "status", "running"));

        assertThrows(IllegalStateException.class,
                () -> service.stop(8L, 9L, new TaskActionRequest(), "tester"));
        assertThrows(IllegalStateException.class, () -> service.refresh(8L, 9L));
        verify(repository, never()).updateInstanceRuntime(9L, "stopping", "", null);
    }

    @Test
    void productionSavepointStopNeverFallsBackToYarnKillWhenJobIdIsMissing() {
        when(repository.requiredInstance(8L, 30L)).thenReturn(Map.of(
                "id", 30L, "taskId", 8L, "managed", true, "status", "running",
                "executionMode", "PRODUCTION", "yarnApplicationId", "application_1_30"));
        TaskActionRequest request = new TaskActionRequest();
        request.setStopType("savepoint");

        assertEquals("正式实例缺少 Flink JobID，禁止降级为 YARN kill；请先刷新实例状态",
                assertThrows(IllegalStateException.class,
                        () -> service.stop(8L, 30L, request, "tester")).getMessage());
        verify(repository, never()).startOperation(anyLong(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void debugLifecycleDoesNotEnterTaskChangeLog() {
        assertFalse(RealtimeRuntimeService.isProductionLifecycleChange(true));
        assertTrue(RealtimeRuntimeService.isProductionLifecycleChange(false));
    }

    @Test
    void importedRunningInstanceStillSupportsReadOnlyObservation() throws Exception {
        RealtimeRuntimeService observed = spy(service);
        doAnswer(invocation -> {
            String path = invocation.getArgument(1);
            if ("/jobs/job-1".equals(path)) return "{\"state\":\"RUNNING\",\"duration\":5000,\"vertices\":[],\"plan\":{\"nodes\":[]}}";
            if (path.startsWith("/jobs/job-1/metrics")) return "[{\"id\":\"numRestarts\",\"value\":\"2\"}]";
            if (path.startsWith("/jobs/job-1/exceptions")) return "{\"all-exceptions\":[]}";
            if ("/taskmanagers".equals(path)) return "{\"taskmanagers\":[]}";
            if (path.startsWith("/jobmanager/metrics")) return "[]";
            if ("/jobs/job-1/checkpoints".equals(path)) return "{\"counts\":{\"completed\":1,\"failed\":0},\"latest\":{},\"history\":[]}";
            if ("/jobmanager/log".equals(path)) return "imported runtime log";
            return "{}";
        }).when(observed).fetchText(anyString(), anyString());
        Map<String, Object> imported = Map.of(
                "id", 9L, "taskId", 8L, "managed", false, "status", "running",
                "jobId", "job-1", "trackingUrl", "http://reference-flink/",
                "config", Map.of("taskConfig", Map.of("checkpointInterval", 60)));
        when(repository.requiredInstance(8L, 9L)).thenReturn(imported);

        Map<?, ?> runtime = (Map<?, ?>) observed.runtime(8L, 9L);
        Map<?, ?> resources = (Map<?, ?>) observed.resources(8L, 9L);
        Map<?, ?> checkpoints = (Map<?, ?>) observed.checkpoints(8L, 9L);
        List<?> components = (List<?>) observed.logComponents(8L, 9L);
        Map<?, ?> logs = (Map<?, ?>) observed.logs(8L, 9L, "jobmanager", null);

        assertEquals("RUNNING", runtime.get("status"));
        assertEquals(true, runtime.get("available"));
        assertEquals(9L, runtime.get("instanceId"));
        assertFalse(runtime.containsKey("instance"));
        assertFalse(runtime.containsKey("imported"));
        assertFalse(runtime.containsKey("versionId"));
        assertEquals(true, resources.get("available"));
        assertFalse(resources.containsKey("instance"));
        assertFalse(resources.containsKey("imported"));
        assertEquals(1, ((Map<?, ?>) checkpoints.get("counts")).get("completed"));
        assertFalse(checkpoints.containsKey("instance"));
        assertFalse(checkpoints.containsKey("imported"));
        assertEquals("all", ((Map<?, ?>) components.get(0)).get("value"));
        assertEquals("startup", ((Map<?, ?>) components.get(1)).get("value"));
        assertEquals("jobmanager", ((Map<?, ?>) components.get(2)).get("value"));
        assertEquals("imported runtime log", logs.get("runtimeLog"));
        assertEquals(true, logs.get("imported"));
    }

    @Test
    void unifiedLogEndpointReturnsStableCursorPage() {
        StringBuilder content = new StringBuilder();
        for (int index = 0; index < 25; index++) {
            if (index > 0) content.append('\n');
            content.append("line-").append(index);
        }
        when(repository.requiredInstance(8L, 29L)).thenReturn(Map.of(
                "id", 29L, "taskId", 8L, "managed", true, "status", "finished",
                "lastRuntimeLog", content.toString()));

        Map<String, Object> page = service.logPage(8L, 29L, "all", null, 2, 20);

        @SuppressWarnings("unchecked") List<String> lines = (List<String>) page.get("lines");
        assertEquals(20, lines.size());
        assertEquals("line-2", lines.get(0));
        assertEquals("line-21", lines.get(19));
        assertEquals(22, page.get("nextCursor"));
        assertEquals(true, page.get("truncated"));
        assertEquals("all", page.get("component"));
    }

    @Test
    void importedActiveJobBlocksProductionDoubleRun() {
        when(repository.requiredTask(8L)).thenReturn(Map.of("id", 8L, "name", "sync_orders"));
        when(repository.hasActiveManagedInstance(8L)).thenReturn(false);
        when(repository.hasActiveImportedInstance(8L)).thenReturn(true);

        assertThrows(IllegalStateException.class,
                () -> service.start(8L, new TaskActionRequest(), "tester", false));
        verify(repository, never()).startOperation(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void yarnNamePrefixPreflightBlocksUntrackedProductionJob() {
        RealtimeRuntimeService guarded = spy(service);
        when(repository.requiredTask(8L)).thenReturn(Map.of("id", 8L, "name", "sync_orders"));
        when(repository.hasActiveManagedInstance(8L)).thenReturn(false);
        when(repository.hasActiveImportedInstance(8L)).thenReturn(false);
        doAnswer(invocation -> List.of("application_1_9")).when(guarded).liveSyncApplications(8L);

        assertThrows(IllegalStateException.class,
                () -> guarded.start(8L, new TaskActionRequest(), "tester", false));
        verify(repository, never()).startOperation(anyLong(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void schedulerQueriesManagedInstancesOnlyAndReleasesExpiredLocks() {
        when(repository.reconcileManagedInstances()).thenReturn(Collections.emptyList());
        when(repository.expiredActiveOperations()).thenReturn(Collections.emptyList());
        service.reconcile();
        service.recoverTimedOutOperations();
        verify(repository).reconcileManagedInstances();
        verify(repository).expiredActiveOperations();
        verify(repository, never()).timeoutExpiredOperations();
    }

    @Test
    void flinkJobStateOverridesLiveYarnContainerButYarnTerminalWins() {
        assertEquals("restarting", RealtimeRuntimeService.resolveObservedStatus("running", "restarting"));
        assertEquals("failed", RealtimeRuntimeService.resolveObservedStatus("running", "failed"));
        assertEquals("canceled", RealtimeRuntimeService.resolveObservedStatus("canceled", "running"));
        assertEquals("finished", RealtimeRuntimeService.resolveObservedStatus("finished", "running"));
        assertEquals("submitting", RealtimeRuntimeService.resolveObservedStatus("running", "submitting"));
    }

    @Test
    void unknownStatusKeepsDatabaseStateAndAlertsAfterThreeFailures() {
        RealtimeRuntimeService unknown = spy(service);
        Map<String, Object> runningWithoutIdentifiers = Map.of(
                "id", 19L, "taskId", 8L, "managed", true,
                "status", "running", "executionMode", "PRODUCTION");
        when(repository.requiredInstance(8L, 19L)).thenReturn(runningWithoutIdentifiers);
        doAnswer(invocation -> { throw new IllegalStateException("YARN unavailable"); })
                .when(unknown).liveSyncApplications(8L);

        unknown.refresh(8L, 19L);
        unknown.refresh(8L, 19L);
        unknown.refresh(8L, 19L);

        verify(repository, never()).updateInstanceRuntime(anyLong(), anyString(), any(), any());
        verify(repository).addAlertIfOpenAbsent(8L, "warning", "同步任务状态检测异常",
                "连续 3 次无法从 YARN/Flink 确认实例 19 的运行状态，数据库状态保持不变");
    }

    @Test
    void productionInstanceWithoutIdentifiersBecomesCanceledWhenNoLiveApplicationExists() {
        RealtimeRuntimeService reconciler = spy(service);
        Map<String, Object> submitting = Map.of(
                "id", 20L, "taskId", 8L, "managed", true,
                "status", "submitting", "executionMode", "PRODUCTION");
        when(repository.requiredInstance(8L, 20L)).thenReturn(submitting);
        doAnswer(invocation -> Collections.emptyList()).when(reconciler).liveSyncApplications(8L);

        reconciler.refresh(8L, 20L);

        verify(repository).updateInstanceRuntime(20L, "canceled",
                "未发现任务名前缀对应的存活 YARN Application", null);
        verify(repository).changeTaskStatus(8L, "not_running");
    }

    @Test
    void debugBecomesQualifiedOnlyAfterMinimumRuntimeAndCompletedCheckpoint() {
        RealtimeRuntimeService reconciler = spy(service);
        long now = System.currentTimeMillis();
        long runningAt = now - 3 * 60_000L;
        Map<String, Object> running = Map.of(
                "id", 27L, "taskId", 8L, "managed", true,
                "status", "running", "executionMode", "DEBUG",
                "jobId", "debug-job", "yarnApplicationId", "application_1_27",
                "trackingUrl", "http://flink.example/");
        when(repository.requiredInstance(8L, 27L)).thenReturn(running);
        doAnswer(invocation -> new RealtimeRuntimeService.CommandResult(0,
                "State : RUNNING\nTracking-URL : http://flink.example/"))
                .when(reconciler).execute(any(), anyLong());
        doAnswer(invocation -> {
            String path = invocation.getArgument(1);
            if ("/jobs/debug-job".equals(path)) return "{\"state\":\"RUNNING\",\"now\":" + now
                    + ",\"timestamps\":{\"RUNNING\":" + runningAt + "}}";
            if ("/jobs/debug-job/checkpoints".equals(path)) return "{\"counts\":{\"completed\":1},"
                    + "\"latest\":{\"completed\":{\"latest_ack_timestamp\":" + (runningAt + 10_000L) + "}}}";
            return "{}";
        }).when(reconciler).fetchText(anyString(), anyString());

        reconciler.refresh(8L, 27L);

        verify(repository).updateInstanceRuntime(27L, "debug_success_running",
                "State : RUNNING\nTracking-URL : http://flink.example/", null);
        verify(repository, never()).changeTaskStatus(anyLong(), anyString());
    }

    @Test
    void debugWithoutCompletedCheckpointRemainsRunning() {
        RealtimeRuntimeService reconciler = spy(service);
        long now = System.currentTimeMillis();
        Map<String, Object> running = Map.of(
                "id", 28L, "taskId", 8L, "managed", true,
                "status", "running", "executionMode", "DEBUG",
                "jobId", "debug-job", "yarnApplicationId", "application_1_28",
                "trackingUrl", "http://flink.example/");
        when(repository.requiredInstance(8L, 28L)).thenReturn(running);
        doAnswer(invocation -> new RealtimeRuntimeService.CommandResult(0, "State : RUNNING"))
                .when(reconciler).execute(any(), anyLong());
        doAnswer(invocation -> {
            String path = invocation.getArgument(1);
            if ("/jobs/debug-job".equals(path)) return "{\"state\":\"RUNNING\",\"now\":" + now
                    + ",\"timestamps\":{\"RUNNING\":" + (now - 3 * 60_000L) + "}}";
            if ("/jobs/debug-job/checkpoints".equals(path)) return "{\"counts\":{\"completed\":0},\"latest\":{}}";
            return "{}";
        }).when(reconciler).fetchText(anyString(), anyString());

        reconciler.refresh(8L, 28L);

        verify(repository, never()).updateInstanceRuntime(anyLong(), anyString(), any(), any());
    }

    @Test
    void runningYarnDoesNotHideMissingFlinkJobAfterVisibilityGrace() {
        RealtimeRuntimeService reconciler = spy(service);
        Map<String, Object> staleRunning = Map.of(
                "id", 21L, "taskId", 8L, "managed", true,
                "status", "running", "executionMode", "PRODUCTION",
                "jobId", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "yarnApplicationId", "application_1_21",
                "trackingUrl", "http://flink.example/",
                "startedAt", "2026-08-27T10:00:00");
        when(repository.requiredInstance(8L, 21L)).thenReturn(staleRunning);
        doAnswer(invocation -> { throw new IllegalStateException("Flink REST job missing"); })
                .when(reconciler).fetchText(anyString(), anyString());
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked") List<String> command = invocation.getArgument(0);
            if (command.contains("-status")) {
                return new RealtimeRuntimeService.CommandResult(0,
                        "State : RUNNING\nTracking-URL : http://flink.example/");
            }
            if (command.contains("list")) {
                return new RealtimeRuntimeService.CommandResult(0, "No running jobs.");
            }
            return new RealtimeRuntimeService.CommandResult(1, "unexpected command");
        }).when(reconciler).execute(any(), anyLong());

        reconciler.refresh(8L, 21L);

        verify(repository).updateInstanceRuntime(21L, "canceled",
                "State : RUNNING\nTracking-URL : http://flink.example/\nFlink REST job missing\nNo running jobs.", null);
        verify(repository).changeTaskStatus(8L, "not_running");
    }

    @Test
    void terminalYarnStateIsRecheckedWhenFlinkListFails() {
        RealtimeRuntimeService reconciler = spy(service);
        Map<String, Object> staleRunning = Map.of(
                "id", 32L, "taskId", 8L, "managed", true,
                "status", "running", "executionMode", "PRODUCTION",
                "jobId", "cccccccccccccccccccccccccccccccc",
                "yarnApplicationId", "application_1_32",
                "trackingUrl", "http://flink.example/",
                "startedAt", "2026-08-27T10:00:00");
        when(repository.requiredInstance(8L, 32L)).thenReturn(staleRunning);
        doAnswer(invocation -> { throw new IllegalStateException("Flink REST unavailable"); })
                .when(reconciler).fetchText(anyString(), anyString());
        java.util.concurrent.atomic.AtomicInteger statusQueries = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked") List<String> command = invocation.getArgument(0);
            if (command.contains("-status")) {
                return statusQueries.getAndIncrement() == 0
                        ? new RealtimeRuntimeService.CommandResult(0, "State : RUNNING")
                        : new RealtimeRuntimeService.CommandResult(0,
                                "State : FINISHED\nFinal-State : SUCCEEDED");
            }
            if (command.contains("list")) {
                return new RealtimeRuntimeService.CommandResult(1, "Flink cluster is shutting down");
            }
            return new RealtimeRuntimeService.CommandResult(1, "unexpected command");
        }).when(reconciler).execute(any(), anyLong());

        reconciler.refresh(8L, 32L);

        verify(repository).updateInstanceRuntime(32L, "finished",
                "State : RUNNING\nFlink REST unavailable\nFlink cluster is shutting down\n"
                        + "State : FINISHED\nFinal-State : SUCCEEDED", null);
        verify(repository).changeTaskStatus(8L, "not_running");
    }

    @Test
    void newlyStartedJobKeepsRunningDuringFlinkVisibilityGrace() {
        RealtimeRuntimeService reconciler = spy(service);
        Map<String, Object> newlyStarted = Map.of(
                "id", 23L, "taskId", 8L, "managed", true,
                "status", "running", "executionMode", "PRODUCTION",
                "jobId", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "yarnApplicationId", "application_1_23",
                "trackingUrl", "http://flink.example/",
                "startedAt", java.time.LocalDateTime.now().minusMinutes(1).toString());
        when(repository.requiredInstance(8L, 23L)).thenReturn(newlyStarted);
        doAnswer(invocation -> { throw new IllegalStateException("Flink REST job not visible yet"); })
                .when(reconciler).fetchText(anyString(), anyString());
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked") List<String> command = invocation.getArgument(0);
            if (command.contains("-status")) {
                return new RealtimeRuntimeService.CommandResult(0,
                        "State : RUNNING\nTracking-URL : http://flink.example/");
            }
            if (command.contains("list")) {
                return new RealtimeRuntimeService.CommandResult(0, "No running jobs.");
            }
            return new RealtimeRuntimeService.CommandResult(1, "unexpected command");
        }).when(reconciler).execute(any(), anyLong());

        reconciler.refresh(8L, 23L);

        verify(repository, never()).updateInstanceRuntime(anyLong(), anyString(), any(), any());
        verify(repository, never()).changeTaskStatus(anyLong(), anyString());
    }

    @Test
    void discoveredYarnApplicationIsPersistedOnInstance() {
        RealtimeRuntimeService reconciler = spy(service);
        Map<String, Object> missingApplication = Map.of(
                "id", 24L, "taskId", 8L, "managed", true,
                "status", "submitting", "executionMode", "PRODUCTION");
        when(repository.requiredInstance(8L, 24L)).thenReturn(missingApplication);
        doAnswer(invocation -> List.of("application_1_24")).when(reconciler).liveSyncApplications(8L);
        doAnswer(invocation -> new RealtimeRuntimeService.CommandResult(0, "State : RUNNING"))
                .when(reconciler).execute(any(), anyLong());

        reconciler.refresh(8L, 24L);

        verify(repository).updateInstanceIdentifiers(24L, "", "application_1_24", "");
        verify(repository).updateInstanceRuntime(24L, "running", "State : RUNNING", null);
    }

    @Test
    void externalProductionFailureCreatesCriticalSyncAlert() {
        RealtimeRuntimeService reconciler = spy(service);
        Map<String, Object> running = Map.of(
                "id", 22L, "taskId", 8L, "managed", true,
                "status", "running", "executionMode", "PRODUCTION",
                "yarnApplicationId", "application_1_22");
        when(repository.requiredInstance(8L, 22L)).thenReturn(running);
        doAnswer(invocation -> new RealtimeRuntimeService.CommandResult(0,
                "State : FINISHED\nFinal-State : FAILED\nDiagnostics: container failed"))
                .when(reconciler).execute(any(), anyLong());

        reconciler.refresh(8L, 22L);

        verify(repository).updateInstanceRuntime(22L, "failed",
                "State : FINISHED\nFinal-State : FAILED\nDiagnostics: container failed",
                "State : FINISHED\nFinal-State : FAILED\nDiagnostics: container failed");
        verify(repository).changeTaskStatus(8L, "failed");
        verify(repository).addAlert(8L, "critical", "同步任务运行失败",
                "State : FINISHED\nFinal-State : FAILED\nDiagnostics: container failed");
    }

    @Test
    void pendingSavepointStopKeepsStoppingWhileExternalJobIsStillRunning() {
        RealtimeRuntimeService reconciler = spy(service);
        Map<String, Object> stopping = Map.of(
                "id", 29L, "taskId", 8L, "managed", true,
                "status", "stopping", "executionMode", "PRODUCTION",
                "yarnApplicationId", "application_1_29");
        when(repository.requiredInstance(8L, 29L)).thenReturn(stopping);
        when(repository.activeStopOperation(8L, 29L)).thenReturn(Map.of(
                "id", 41L, "operator", "tester"));
        doAnswer(invocation -> new RealtimeRuntimeService.CommandResult(0, "State : RUNNING"))
                .when(reconciler).execute(any(), anyLong());

        reconciler.refresh(8L, 29L);

        verify(repository, never()).updateInstanceRuntime(anyLong(), anyString(), any(), any());
        verify(repository, never()).completeOperation(anyLong(), anyString(), any(), any());
        verify(repository, never()).changeTaskStatus(anyLong(), anyString());
    }

    @Test
    void pendingSavepointStopCompletesOnlyAfterExternalTerminalState() {
        RealtimeRuntimeService reconciler = spy(service);
        Map<String, Object> stopping = Map.of(
                "id", 31L, "taskId", 8L, "managed", true,
                "status", "stopping", "executionMode", "PRODUCTION",
                "yarnApplicationId", "application_1_31");
        Map<String, Object> finished = Map.of(
                "id", 31L, "taskId", 8L, "managed", true,
                "status", "finished", "executionMode", "PRODUCTION",
                "savepointPath", "hdfs://savepoints/sp-31");
        when(repository.requiredInstance(8L, 31L)).thenReturn(stopping, finished, finished);
        when(repository.activeStopOperation(8L, 31L)).thenReturn(Map.of(
                "id", 42L, "operator", "tester"));
        doAnswer(invocation -> new RealtimeRuntimeService.CommandResult(0,
                "State : FINISHED\nFinal-State : SUCCEEDED"))
                .when(reconciler).execute(any(), anyLong());

        reconciler.refresh(8L, 31L);

        verify(repository).updateInstanceRuntime(31L, "finished",
                "State : FINISHED\nFinal-State : SUCCEEDED", null);
        verify(repository).changeTaskStatus(8L, "not_running");
        verify(repository).completeOperation(org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.eq("SUCCESS"), anyString(),
                org.mockito.ArgumentMatchers.isNull());
        verify(repository).addChange(8L, 42L, null, 31L, "tester",
                "STOP", "停止类型：savepoint，savepoint：hdfs://savepoints/sp-31");
    }

    @Test
    void timedOutOperationWithoutInstanceRemainsLockedAndAlerts() {
        when(repository.expiredActiveOperations()).thenReturn(java.util.List.of(Map.of(
                "id", 31L, "taskId", 8L, "operationType", "START")));

        service.recoverTimedOutOperations();

        verify(repository, never()).timeoutOperation(anyLong(), anyString());
        verify(repository).addAlertIfOpenAbsent(8L, "warning", "同步任务操作超时待人工确认",
                "操作超过截止时间但未关联运行实例，无法确认外部作业状态，任务锁未自动释放");
    }

    @Test
    void commandPreviewDoesNotExposeServerPassword() throws Exception {
        RealtimeProperties properties = new RealtimeProperties();
        properties.setPaimonActionJarPath("/data/action.jar");
        properties.setPaimonWarehouse("hdfs:///warehouse");
        properties.setTargetDatabase("ods");
        RealtimeRuntimeService previewService = new RealtimeRuntimeService(repository, properties, new ObjectMapper());
        when(repository.requiredTask(8L)).thenReturn(Map.of(
                "id", 8L, "name", "中文同步任务", "sourceServerId", 3L, "targetDatabase", "ods",
                "taskConfig", Map.of("sourceServerId", 3L, "cdcConfig", Map.of(
                        "targetDatabase", "ods", "domainPrefix", "trade", "selectedTables", java.util.List.of("orders")))));
        when(repository.requiredServer(3L, true)).thenReturn(Map.of(
                "id", 3L, "name", "mysql", "address", "mysql:3306", "databaseName", "sales",
                "databasePrefix", "sale", "account", "cdc", "password", "secret-value"));

        String json = new ObjectMapper().writeValueAsString(
                previewService.previewSaved(8L, new TaskActionRequest(), false));
        assertFalse(json.contains("secret-value"));
        assertFalse(json.contains("\"servers\""));
        assertFalse(json.contains("inst-null"));
        assertTrue(json.contains("flink run -t yarn-application"));
        assertFalse(json.contains("run-application"));
    }

    @Test
    void debugPreviewUsesRuntimeOverridesFromDialog() throws Exception {
        RealtimeProperties properties = new RealtimeProperties();
        properties.setPaimonActionJarPath("/data/action.jar");
        properties.setPaimonWarehouse("hdfs:///warehouse");
        properties.setPaimonDebugWarehouse("hdfs:///debug");
        RealtimeRuntimeService previewService = new RealtimeRuntimeService(repository, properties, new ObjectMapper());
        when(repository.requiredTask(8L)).thenReturn(Map.of(
                "id", 8L, "name", "debug_sync", "sourceServerId", 3L, "targetDatabase", "ods",
                "taskConfig", Map.of("sourceServerId", 3L, "parallelism", 1,
                        "checkpointInterval", 60, "taskManagerMemory", "2GB", "jobManagerMemory", "1GB",
                        "cdcConfig", Map.of("targetDatabase", "ods", "domainPrefix", "trade",
                                "selectedTables", java.util.List.of("orders")))));
        when(repository.requiredServer(3L, true)).thenReturn(Map.of(
                "id", 3L, "name", "mysql", "address", "mysql:3306", "databaseName", "sales",
                "databasePrefix", "sale", "account", "cdc", "password", "secret"));
        TaskActionRequest action = new TaskActionRequest();
        action.setParallelism(6); action.setCheckpointInterval(90);
        action.setTaskManagerMemory("4GB"); action.setJobManagerMemory("3GB");

        Map<String, Object> preview = previewService.previewSaved(8L, action, true);
        String command = String.valueOf(preview.get("command"));
        String arguments = String.valueOf(preview.get("arguments"));

        assertTrue(command.contains("-Dparallelism.default=6"));
        assertTrue(command.contains("-Dexecution.checkpointing.interval=90s"));
        assertTrue(command.contains("-Dtaskmanager.memory.process.size=4GB"));
        assertTrue(command.contains("-Djobmanager.memory.process.size=3GB"));
        assertTrue(arguments.contains("paimon_debug"));
        assertTrue(arguments.contains("paimon_debug_sale_sales_trade_"));
        assertFalse(arguments.contains("--table_suffix, _debug"));
    }

    @Test
    void editDraftPreviewUsesCurrentTaskIdInsteadOfZero() {
        RealtimeProperties properties = new RealtimeProperties();
        properties.setPaimonActionJarPath("/data/action.jar");
        properties.setPaimonWarehouse("hdfs:///warehouse");
        properties.setTargetDatabase("ods");
        RealtimeRuntimeService previewService = new RealtimeRuntimeService(repository, properties, new ObjectMapper());
        SyncTaskRequest request = new SyncTaskRequest();
        request.setName("edit_sync"); request.setSourceServerId(3L); request.setTargetDatabase("ods");
        request.setTaskConfig(Map.of("sourceServerId", 3L, "parallelism", 1, "checkpointInterval", 60,
                "taskManagerMemory", "2GB", "jobManagerMemory", "1GB",
                "cdcConfig", Map.of("targetDatabase", "ods", "domainPrefix", "trade",
                        "selectedTables", java.util.List.of("orders"))));
        when(repository.validatePreview(request, 18L)).thenReturn(request.getTaskConfig());
        when(repository.requiredServer(3L, true)).thenReturn(Map.of(
                "id", 3L, "name", "mysql", "address", "mysql:3306", "databaseName", "sales",
                "databasePrefix", "sale", "account", "cdc", "password", "secret"));

        String command = String.valueOf(previewService.previewRequest(request, 18L).get("command"));

        assertTrue(command.contains("sync-task-18-inst-<task-instance-id>-edit_sync"));
        assertTrue(command.contains("/tasks/18/instances/<task-instance-id>/job-config.json"));
        assertFalse(command.contains("sync-task-0-"));
        assertFalse(command.contains("/tasks/<task-id>/"));
    }

    @Test
    void productionStartReturnsSubmittingBeforeFlinkCliRuns() {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        RealtimeRuntimeService asyncService = spy(new RealtimeRuntimeService(repository,
                new RealtimeProperties(), new ObjectMapper(),
                new RealtimeTaskOperationExecutor(queued::set)));
        Map<String, Object> task = Map.of(
                "id", 8L, "name", "sync_orders", "sourceServerId", 3L, "targetDatabase", "ods",
                "status", "not_running", "taskConfig", Map.of("cdcConfig", Map.of()));
        Map<String, Object> submitting = Map.of(
                "id", 19L, "taskId", 8L, "managed", true,
                "status", "submitting", "executionMode", "PRODUCTION");
        when(repository.requiredTask(8L)).thenReturn(task);
        when(repository.hasActiveManagedInstance(8L)).thenReturn(false);
        when(repository.hasActiveImportedInstance(8L)).thenReturn(false);
        doAnswer(invocation -> Collections.emptyList()).when(asyncService).liveSyncApplications(8L);
        when(repository.latestVersionId(8L)).thenReturn(4L);
        when(repository.startOperation(anyLong(), any(), anyString(), anyString(), anyString())).thenReturn(11L);
        when(repository.insertInstance(anyLong(), any(), anyString(), anyString())).thenReturn(19L);
        when(repository.requiredInstance(8L, 19L)).thenReturn(submitting);

        Map<String, Object> result = asyncService.start(8L, new TaskActionRequest(), "tester", false);

        assertEquals("submitting", result.get("status"));
        assertNotNull(queued.get());
        verify(repository).attachOperationInstance(11L, 19L);
        verify(repository).changeTaskStatus(8L, "submitting");
        verify(repository, never()).updateInstanceSubmission(anyLong(), anyString(), any(), any(), any(), any(), any());
        verify(repository, never()).completeOperation(anyLong(), anyString(), any(), any());
    }

    @Test
    void productionSavepointStopReturnsStoppingBeforeFlinkCliRuns() {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        RealtimeRuntimeService asyncService = new RealtimeRuntimeService(repository,
                new RealtimeProperties(), new ObjectMapper(),
                new RealtimeTaskOperationExecutor(queued::set));
        Map<String, Object> running = Map.of(
                "id", 19L, "taskId", 8L, "managed", true, "status", "running",
                "executionMode", "PRODUCTION", "jobId", "0123456789abcdef0123456789abcdef",
                "yarnApplicationId", "application_1_2", "trackingUrl", "http://flink/",
                "lastRuntimeLog", "last", "startupLog", "start");
        Map<String, Object> stopping = Map.of(
                "id", 19L, "taskId", 8L, "managed", true,
                "status", "stopping", "executionMode", "PRODUCTION");
        when(repository.requiredInstance(8L, 19L)).thenReturn(running, stopping);
        when(repository.startOperation(anyLong(), any(), anyString(), anyString(), anyString())).thenReturn(12L);

        TaskActionRequest request = new TaskActionRequest();
        request.setStopType("savepoint");
        Map<String, Object> result = asyncService.stop(8L, 19L, request, "tester");

        assertEquals("stopping", result.get("status"));
        assertNotNull(queued.get());
        verify(repository).updateInstanceRuntime(19L, "stopping", "last", null);
        verify(repository).changeTaskStatus(8L, "stopping");
        verify(repository, never()).completeOperation(anyLong(), anyString(), any(), any());
    }

    @Test
    void savepointStopRejectsRestartingInstanceBeforeCreatingOperation() {
        when(repository.requiredInstance(8L, 19L)).thenReturn(Map.of(
                "id", 19L, "taskId", 8L, "managed", true,
                "status", "restarting", "executionMode", "PRODUCTION"));
        TaskActionRequest request = new TaskActionRequest();
        request.setStopType("savepoint");

        assertThrows(IllegalStateException.class,
                () -> service.stop(8L, 19L, request, "tester"));

        verify(repository, never()).startOperation(anyLong(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void debugDirectStopFallsBackToYarnKillWhenFlinkCancelThrows() {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        RealtimeRuntimeService asyncService = spy(new RealtimeRuntimeService(repository,
                new RealtimeProperties(), new ObjectMapper(),
                new RealtimeTaskOperationExecutor(queued::set)));
        Map<String, Object> running = Map.of(
                "id", 19L, "taskId", 8L, "managed", true, "status", "running",
                "executionMode", "DEBUG", "jobId", "0123456789abcdef0123456789abcdef",
                "yarnApplicationId", "application_1_2", "trackingUrl", "http://flink/",
                "lastRuntimeLog", "last", "startupLog", "start");
        Map<String, Object> stopping = Map.of(
                "id", 19L, "taskId", 8L, "managed", true,
                "status", "stopping", "executionMode", "DEBUG");
        when(repository.requiredInstance(8L, 19L)).thenReturn(running, stopping);
        when(repository.startOperation(anyLong(), any(), anyString(), anyString(), anyString())).thenReturn(12L);
        doAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            if (command.contains("cancel")) throw new IllegalStateException("flink cancel 异常");
            return new RealtimeRuntimeService.CommandResult(0, "Killed application application_1_2");
        }).when(asyncService).execute(any(), anyLong());

        Map<String, Object> result = asyncService.stop(8L, 19L, new TaskActionRequest(), "tester");
        assertEquals("stopping", result.get("status"));
        queued.get().run();

        verify(repository).updateInstanceSubmission(19L, "canceled",
                "0123456789abcdef0123456789abcdef", "application_1_2", "http://flink/",
                "start\nflink cancel 异常\nKilled application application_1_2", null);
        verify(repository).updateSavepointPath(19L, "");
        ArgumentCaptor<String> operationResult = ArgumentCaptor.forClass(String.class);
        verify(repository).completeOperation(org.mockito.ArgumentMatchers.eq(12L),
                org.mockito.ArgumentMatchers.eq("SUCCESS"), operationResult.capture(),
                org.mockito.ArgumentMatchers.isNull());
        assertTrue(operationResult.getValue().contains("\"actualMethod\":\"yarn_kill\""));
        assertTrue(operationResult.getValue().contains("\"fallbackToYarnKill\":true"));
        verify(repository, never()).changeTaskStatus(anyLong(), anyString());
    }
}
