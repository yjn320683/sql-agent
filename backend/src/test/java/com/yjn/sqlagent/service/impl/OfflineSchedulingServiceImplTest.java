package com.yjn.sqlagent.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjn.sqlagent.exception.BusinessException;
import com.yjn.sqlagent.mapper.SqlTaskBackfillBatchMapper;
import com.yjn.sqlagent.mapper.SqlTaskBackfillItemMapper;
import com.yjn.sqlagent.mapper.SqlTaskMapper;
import com.yjn.sqlagent.mapper.SqlTaskDependencyMapper;
import com.yjn.sqlagent.mapper.SqlTaskScheduleMapper;
import com.yjn.sqlagent.mapper.SqlTaskScheduleRunMapper;
import com.yjn.sqlagent.model.dto.SqlTaskDependencySaveDTO;
import com.yjn.sqlagent.model.dto.SqlTaskBackfillCreateDTO;
import com.yjn.sqlagent.model.dto.SqlTaskScheduleSaveDTO;
import com.yjn.sqlagent.model.entity.SqlTask;
import com.yjn.sqlagent.model.entity.SqlTaskBackfillBatch;
import com.yjn.sqlagent.model.entity.SqlTaskDependency;
import com.yjn.sqlagent.model.entity.SqlTaskSchedule;
import com.yjn.sqlagent.model.entity.SqlTaskScheduleRun;
import com.yjn.sqlagent.model.dto.TaskExecutionCreateDTO;
import com.yjn.sqlagent.model.vo.SqlTaskScheduleVO;
import com.yjn.sqlagent.model.vo.TaskExecutionVO;
import com.yjn.sqlagent.service.SqlTaskService;
import com.yjn.sqlagent.service.TaskExecutionService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OfflineSchedulingServiceImplTest {
    private final SqlTaskScheduleMapper schedules = mock(SqlTaskScheduleMapper.class);
    private final SqlTaskDependencyMapper dependencies = mock(SqlTaskDependencyMapper.class);
    private final SqlTaskScheduleRunMapper runs = mock(SqlTaskScheduleRunMapper.class);
    private final SqlTaskBackfillBatchMapper backfills = mock(SqlTaskBackfillBatchMapper.class);
    private final SqlTaskBackfillItemMapper backfillItems = mock(SqlTaskBackfillItemMapper.class);
    private final SqlTaskMapper taskMapper = mock(SqlTaskMapper.class);
    private final SqlTaskService tasks = mock(SqlTaskService.class);
    private final TaskExecutionService executions = mock(TaskExecutionService.class);
    private OfflineSchedulingServiceImpl service;

    @BeforeEach
    void setUp() {
        SqlTask task = new SqlTask(); task.setId(1L); task.setArchived(false); task.setEnabled(true); task.setRevision(5L);
        when(tasks.require(anyLong())).thenReturn(task);
        service = new OfflineSchedulingServiceImpl(schedules, dependencies, runs, backfills, backfillItems, tasks, executions, new ObjectMapper(), taskMapper);
    }

    @Test
    void rejectsInvalidCronBeforePersistingIt() {
        SqlTaskScheduleSaveDTO request = new SqlTaskScheduleSaveDTO(); request.setScheduleType("CRON");
        request.setCronExpression("not-a-cron"); request.setTimezone("Asia/Shanghai"); request.setEnabled(true);

        assertThrows(BusinessException.class, () -> service.saveSchedule("admin", 1L, request));
    }

    @Test
    void returnsManualDefaultsWhenScheduleHasNotBeenConfigured() {
        when(schedules.selectByTaskId(1L)).thenReturn(null);

        SqlTaskScheduleVO result = service.getSchedule(1L);

        assertEquals("MANUAL", result.getScheduleType());
        assertEquals("Asia/Shanghai", result.getTimezone());
        assertEquals("FORBID", result.getConcurrencyPolicy());
        assertFalse(result.getEnabled());
        assertEquals(0L, result.getRevision());
    }

    @Test
    void savesValidCronWithNextTriggerAndOptimisticRevision() {
        SqlTaskSchedule current = schedule(12L, 1L);
        current.setRevision(3L);
        when(schedules.selectByTaskIdForUpdate(1L)).thenReturn(current);
        when(schedules.updateOptimistically(any(), eq(3L))).thenReturn(1);
        when(schedules.selectByTaskId(1L)).thenReturn(current);
        SqlTaskScheduleSaveDTO request = cronRequest();
        request.setRevision(3L);
        request.setParameters(Map.of("region", "cn"));

        service.saveSchedule("admin", 1L, request);

        ArgumentCaptor<SqlTaskSchedule> captor = ArgumentCaptor.forClass(SqlTaskSchedule.class);
        verify(schedules).updateOptimistically(captor.capture(), eq(3L));
        assertEquals("0 0 7 * * *", captor.getValue().getCronExpression());
        assertEquals("{\"region\":\"cn\"}", captor.getValue().getParameterValues());
        assertNotNull(captor.getValue().getNextTriggerTime());
    }

    @Test
    void rejectsDependencyCycles() {
        SqlTaskDependency existing = new SqlTaskDependency(); existing.setTaskId(2L); existing.setUpstreamTaskId(1L); existing.setDependencyType("SUCCESS");
        when(dependencies.listAll()).thenReturn(List.of(existing));
        SqlTaskDependencySaveDTO.Item item = new SqlTaskDependencySaveDTO.Item(); item.setUpstreamTaskId(2L); item.setDependencyType("SUCCESS");
        SqlTaskDependencySaveDTO request = new SqlTaskDependencySaveDTO(); request.setItems(List.of(item));

        assertThrows(BusinessException.class, () -> service.saveDependencies("admin", 1L, request));
    }

    @Test
    void savesUniqueAcyclicDependenciesWithTheirCompletionRules() {
        when(dependencies.listAll()).thenReturn(List.of());
        SqlTaskDependencySaveDTO.Item success = dependency(2L, "SUCCESS");
        SqlTaskDependencySaveDTO.Item completed = dependency(3L, "COMPLETED");
        SqlTaskDependencySaveDTO request = new SqlTaskDependencySaveDTO();
        request.setItems(List.of(success, completed));
        when(dependencies.listByTask(1L)).thenReturn(List.of());

        service.saveDependencies("admin", 1L, request);

        verify(dependencies).deleteByTask(1L);
        ArgumentCaptor<SqlTaskDependency> captor = ArgumentCaptor.forClass(SqlTaskDependency.class);
        verify(dependencies, times(2)).insert(captor.capture());
        assertEquals(List.of("SUCCESS", "COMPLETED"), captor.getAllValues().stream()
                .map(SqlTaskDependency::getDependencyType).collect(java.util.stream.Collectors.toList()));
    }

    @Test
    void dueScheduleIsSkippedWhenRequiredUpstreamHasNotSucceeded() {
        SqlTaskSchedule due = schedule(12L, 1L);
        LocalDateTime trigger = LocalDateTime.now().minusMinutes(1);
        due.setNextTriggerTime(trigger);
        when(schedules.listDue(any(), eq(20))).thenReturn(List.of(due));
        when(schedules.claim(eq(12L), eq(trigger), any())).thenReturn(1);
        when(dependencies.listByTask(1L)).thenReturn(List.of(edge(1L, 2L, "SUCCESS")));
        when(dependencies.selectLatestExecutionStatus(2L)).thenReturn("FAILED");

        service.processDueSchedules();

        ArgumentCaptor<SqlTaskScheduleRun> captor = ArgumentCaptor.forClass(SqlTaskScheduleRun.class);
        verify(runs).insert(captor.capture());
        assertEquals("SKIPPED", captor.getValue().getStatus());
        assertEquals("上游依赖尚未满足", captor.getValue().getMessage());
        verify(executions, never()).create(any(), anyLong(), any());
    }

    @Test
    void dueScheduleSubmitsCurrentTaskRevisionAndBusinessDate() {
        SqlTaskSchedule due = schedule(12L, 1L);
        LocalDateTime trigger = LocalDateTime.of(2026, 9, 7, 7, 0);
        due.setNextTriggerTime(trigger);
        due.setParameterValues("{\"region\":\"cn\"}");
        when(schedules.listDue(any(), eq(20))).thenReturn(List.of(due));
        when(schedules.claim(eq(12L), eq(trigger), any())).thenReturn(1);
        when(dependencies.listByTask(1L)).thenReturn(List.of());
        TaskExecutionVO execution = new TaskExecutionVO();
        execution.setId(88L);
        when(executions.create(eq("scheduler"), eq(1L), any())).thenReturn(execution);

        service.processDueSchedules();

        ArgumentCaptor<TaskExecutionCreateDTO> request = ArgumentCaptor.forClass(TaskExecutionCreateDTO.class);
        verify(executions).create(eq("scheduler"), eq(1L), request.capture());
        assertEquals(5L, request.getValue().getRevision());
        assertEquals(LocalDate.of(2026, 9, 7), request.getValue().getBusinessDate());
        assertEquals("cn", request.getValue().getParameters().get("region"));
        ArgumentCaptor<SqlTaskScheduleRun> updated = ArgumentCaptor.forClass(SqlTaskScheduleRun.class);
        verify(runs).updateById(updated.capture());
        assertEquals("SUBMITTED", updated.getValue().getStatus());
        assertEquals(88L, updated.getValue().getExecutionId());
    }

    @Test
    void backfillCreatesOneUniquePendingItemForEveryInclusiveBusinessDate() {
        SqlTaskBackfillCreateDTO request = new SqlTaskBackfillCreateDTO();
        request.setStartDate(LocalDate.of(2026, 9, 5));
        request.setEndDate(LocalDate.of(2026, 9, 7));
        request.setParameters(new LinkedHashMap<>(Map.of("region", "cn")));
        doAnswer(invocation -> {
            SqlTaskBackfillBatch batch = invocation.getArgument(0);
            batch.setId(41L);
            return 1;
        }).when(backfills).insert(org.mockito.ArgumentMatchers.<SqlTaskBackfillBatch>any());
        SqlTaskBackfillBatch stored = new SqlTaskBackfillBatch();
        stored.setId(41L);
        stored.setTotalCount(3);
        when(backfills.selectById(41L)).thenReturn(stored);

        SqlTaskBackfillBatch result = service.createBackfill("admin", 1L, request);

        assertEquals(3, result.getTotalCount());
        ArgumentCaptor<com.yjn.sqlagent.model.entity.SqlTaskBackfillItem> items = ArgumentCaptor.forClass(com.yjn.sqlagent.model.entity.SqlTaskBackfillItem.class);
        verify(backfillItems, times(3)).insert(items.capture());
        assertEquals(List.of(LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 6), LocalDate.of(2026, 9, 7)),
                items.getAllValues().stream().map(com.yjn.sqlagent.model.entity.SqlTaskBackfillItem::getBusinessDate)
                        .collect(java.util.stream.Collectors.toList()));
        verify(backfills).updateProgress(41L);
    }

    @Test
    void retryableFailureCreatesNextAttemptAndMarksOriginalRetried() {
        SqlTaskScheduleRun failed = new SqlTaskScheduleRun();
        failed.setId(55L); failed.setScheduleId(12L); failed.setTaskId(1L);
        failed.setScheduledTime(LocalDateTime.of(2026, 9, 7, 7, 0));
        failed.setBusinessDate(LocalDate.of(2026, 9, 7)); failed.setAttemptNo(1);
        failed.setParameterValues("{}");
        when(backfills.listActive()).thenReturn(List.of());
        when(runs.listRetryable(any(), eq(20))).thenReturn(List.of(failed));
        when(runs.claimRetry(55L)).thenReturn(1);
        when(schedules.selectById(12L)).thenReturn(schedule(12L, 1L));
        TaskExecutionVO execution = new TaskExecutionVO(); execution.setId(99L);
        when(executions.create(eq("scheduler"), eq(1L), any())).thenReturn(execution);

        service.reconcileAndRetry();

        ArgumentCaptor<SqlTaskScheduleRun> inserted = ArgumentCaptor.forClass(SqlTaskScheduleRun.class);
        verify(runs).insert(inserted.capture());
        assertEquals("RETRY", inserted.getValue().getTriggerType());
        assertEquals(2, inserted.getValue().getAttemptNo());
        verify(runs).markRetried(55L);
    }

    @Test
    void reconciliationRefreshesTerminalStatusAndRecoversStaleRetryClaims() {
        when(backfills.listActive()).thenReturn(List.of());
        when(runs.listRetryable(any(), anyInt())).thenReturn(List.of());

        service.reconcileAndRetry();

        verify(runs).reconcileExecutionStatuses();
        verify(runs).syncScheduleLastRunStatuses();
        verify(runs).recoverStaleRetryClaims(any());
    }

    @Test
    void dagUsesBatchedQueriesAndMarksTheEstimatedCriticalPath() {
        Map<String, Object> extract = new LinkedHashMap<>();
        extract.put("id", 1L); extract.put("name", "extract");
        Map<String, Object> transform = new LinkedHashMap<>();
        transform.put("id", 2L); transform.put("name", "transform");
        Map<String, Object> isolated = new LinkedHashMap<>();
        isolated.put("id", 3L); isolated.put("name", "isolated");
        when(taskMapper.listScheduleDagNodes()).thenReturn(List.of(extract, transform, isolated));
        when(taskMapper.listRecentSuccessfulDurations()).thenReturn(List.of(
                duration(1L, 100L), duration(1L, 300L), duration(2L, 500L)));
        when(dependencies.listAll()).thenReturn(List.of(edge(2L, 1L, "SUCCESS")));

        Map<String, Object> result = service.dag();

        assertEquals(List.of(1L, 2L), new java.util.ArrayList<>((Set<Long>) result.get("criticalPathTaskIds")));
        assertEquals(200L, extract.get("medianDurationMs"));
        assertEquals(2, extract.get("durationSampleCount"));
        assertEquals(1, extract.get("downstreamCount"));
        assertEquals(1, transform.get("upstreamCount"));
        assertEquals(null, isolated.get("medianDurationMs"));
        verify(taskMapper, times(1)).listScheduleDagNodes();
        verify(taskMapper, times(1)).listRecentSuccessfulDurations();
    }

    @Test
    void dagDoesNotInventCriticalPathWithoutDurationSamples() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", 1L); node.put("name", "no-sample");
        when(taskMapper.listScheduleDagNodes()).thenReturn(List.of(node));
        when(taskMapper.listRecentSuccessfulDurations()).thenReturn(List.of());
        when(dependencies.listAll()).thenReturn(List.of());

        Map<String, Object> result = service.dag();

        assertEquals(Set.of(), result.get("criticalPathTaskIds"));
        assertEquals(false, node.get("criticalPath"));
    }

    @Test
    void pausingBackfillDoesNotCancelAlreadyRunningExecutions() {
        SqlTaskBackfillBatch running = backfill(41L, "RUNNING", 3);
        SqlTaskBackfillBatch paused = backfill(41L, "PAUSED", 3);
        when(backfills.selectById(41L)).thenReturn(running, paused);
        when(backfills.changeStatus(41L, "RUNNING", "PAUSED")).thenReturn(1);

        SqlTaskBackfillBatch result = service.pauseBackfill(1L, 41L);

        assertEquals("PAUSED", result.getStatus());
        verify(executions, never()).cancel(anyLong());
        verify(backfillItems, never()).retryFailed(anyLong());
    }

    @Test
    void deadlinePolicyOnlyCancelsWhenExplicitlyConfigured() {
        when(backfills.listActive()).thenReturn(List.of());
        when(runs.listRetryable(any(), anyInt())).thenReturn(List.of());
        when(runs.listDeadlineBreaches(any(), eq(100))).thenReturn(List.of(
                breach(51L, 901L, "ALERT_ONLY", 180L),
                breach(52L, 902L, "CANCEL", 240L)));
        when(runs.markBreach(anyLong(), any())).thenReturn(1);

        service.reconcileAndRetry();

        verify(executions, never()).cancel(901L);
        verify(executions).cancel(902L);
        verify(runs, times(2)).markBreach(anyLong(), org.mockito.ArgumentMatchers.contains("[DEADLINE]"));
    }

    private SqlTaskScheduleSaveDTO cronRequest() {
        SqlTaskScheduleSaveDTO request = new SqlTaskScheduleSaveDTO();
        request.setScheduleType("CRON"); request.setCronExpression("0 0 7 * * *");
        request.setTimezone("Asia/Shanghai"); request.setEnabled(true);
        request.setConcurrencyPolicy("FORBID"); request.setMaxRetries(2);
        request.setRetryIntervalSeconds(60); return request;
    }

    private SqlTaskSchedule schedule(long id, long taskId) {
        SqlTaskSchedule value = new SqlTaskSchedule(); value.setId(id); value.setTaskId(taskId);
        value.setScheduleType("CRON"); value.setCronExpression("0 0 7 * * *");
        value.setTimezone("Asia/Shanghai"); value.setEnabled(true);
        value.setConcurrencyPolicy("FORBID"); value.setMaxRetries(2);
        value.setRetryIntervalSeconds(60); value.setParameterValues("{}"); return value;
    }

    private SqlTaskDependencySaveDTO.Item dependency(long upstreamTaskId, String type) {
        SqlTaskDependencySaveDTO.Item value = new SqlTaskDependencySaveDTO.Item();
        value.setUpstreamTaskId(upstreamTaskId); value.setDependencyType(type); return value;
    }

    private SqlTaskDependency edge(long taskId, long upstreamTaskId, String type) {
        SqlTaskDependency value = new SqlTaskDependency(); value.setTaskId(taskId);
        value.setUpstreamTaskId(upstreamTaskId); value.setDependencyType(type); return value;
    }

    private Map<String, Object> duration(long taskId, long durationMs) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("task_id", taskId); value.put("duration_ms", durationMs); return value;
    }

    private Map<String, Object> breach(long id, long executionId, String policy, long elapsedSeconds) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", id); value.put("execution_id", executionId);
        value.put("timeout_policy", policy); value.put("elapsed_seconds", elapsedSeconds); return value;
    }

    private SqlTaskBackfillBatch backfill(long id, String status, int maxConcurrency) {
        SqlTaskBackfillBatch value = new SqlTaskBackfillBatch();
        value.setId(id); value.setTaskId(1L); value.setStatus(status); value.setMaxConcurrency(maxConcurrency);
        value.setParameterValues("{}"); value.setRequestedBy("admin"); return value;
    }
}
