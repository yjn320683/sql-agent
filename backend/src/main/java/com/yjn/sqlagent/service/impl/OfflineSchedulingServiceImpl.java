package com.yjn.sqlagent.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjn.sqlagent.common.ErrorCode;
import com.yjn.sqlagent.exception.BusinessException;
import com.yjn.sqlagent.mapper.SqlTaskBackfillBatchMapper;
import com.yjn.sqlagent.mapper.SqlTaskDependencyMapper;
import com.yjn.sqlagent.mapper.SqlTaskScheduleMapper;
import com.yjn.sqlagent.mapper.SqlTaskScheduleRunMapper;
import com.yjn.sqlagent.model.dto.SqlTaskBackfillCreateDTO;
import com.yjn.sqlagent.model.dto.SqlTaskDependencySaveDTO;
import com.yjn.sqlagent.model.dto.SqlTaskScheduleSaveDTO;
import com.yjn.sqlagent.model.dto.TaskExecutionCreateDTO;
import com.yjn.sqlagent.model.entity.SqlTask;
import com.yjn.sqlagent.model.entity.SqlTaskBackfillBatch;
import com.yjn.sqlagent.model.entity.SqlTaskDependency;
import com.yjn.sqlagent.model.entity.SqlTaskSchedule;
import com.yjn.sqlagent.model.entity.SqlTaskScheduleRun;
import com.yjn.sqlagent.model.vo.SqlTaskScheduleVO;
import com.yjn.sqlagent.model.vo.TaskExecutionVO;
import com.yjn.sqlagent.service.OfflineSchedulingService;
import com.yjn.sqlagent.service.SqlTaskService;
import com.yjn.sqlagent.service.TaskExecutionService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OfflineSchedulingServiceImpl implements OfflineSchedulingService {
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<Map<String, Object>>() { };
    private static final String SYSTEM_OPERATOR = "scheduler";

    private final SqlTaskScheduleMapper scheduleMapper;
    private final SqlTaskDependencyMapper dependencyMapper;
    private final SqlTaskScheduleRunMapper runMapper;
    private final SqlTaskBackfillBatchMapper backfillMapper;
    private final SqlTaskService taskService;
    private final TaskExecutionService executionService;
    private final ObjectMapper objectMapper;

    public OfflineSchedulingServiceImpl(SqlTaskScheduleMapper scheduleMapper,
                                        SqlTaskDependencyMapper dependencyMapper,
                                        SqlTaskScheduleRunMapper runMapper,
                                        SqlTaskBackfillBatchMapper backfillMapper,
                                        SqlTaskService taskService,
                                        TaskExecutionService executionService,
                                        ObjectMapper objectMapper) {
        this.scheduleMapper = scheduleMapper;
        this.dependencyMapper = dependencyMapper;
        this.runMapper = runMapper;
        this.backfillMapper = backfillMapper;
        this.taskService = taskService;
        this.executionService = executionService;
        this.objectMapper = objectMapper;
    }

    @Override
    public SqlTaskScheduleVO getSchedule(long taskId) {
        taskService.require(taskId);
        SqlTaskSchedule schedule = scheduleMapper.selectByTaskId(taskId);
        return schedule == null ? defaultSchedule(taskId) : toVO(schedule);
    }

    @Override
    @Transactional
    public SqlTaskScheduleVO saveSchedule(String operator, long taskId, SqlTaskScheduleSaveDTO request) {
        SqlTask task = taskService.require(taskId);
        if (Boolean.TRUE.equals(task.getArchived())) throw badRequest("归档任务不能配置调度");
        validateSchedule(request);
        SqlTaskSchedule current = scheduleMapper.selectByTaskIdForUpdate(taskId);
        SqlTaskSchedule next = toEntity(taskId, request, operator);
        next.setNextTriggerTime(nextTrigger(next, LocalDateTime.now()));
        if (current == null) {
            next.setRevision(1L);
            next.setCreatedBy(operator);
            scheduleMapper.insert(next);
        } else {
            long expectedRevision = request.getRevision() == null ? current.getRevision() : request.getRevision();
            if (scheduleMapper.updateOptimistically(next, expectedRevision) != 1) {
                throw new BusinessException(ErrorCode.CONFLICT.getCode(), "调度配置已更新，请刷新后重试");
            }
        }
        return getSchedule(taskId);
    }

    @Override
    public List<SqlTaskDependency> getDependencies(long taskId) {
        taskService.require(taskId);
        return dependencyMapper.listByTask(taskId);
    }

    @Override
    @Transactional
    public List<SqlTaskDependency> saveDependencies(String operator, long taskId,
                                                    SqlTaskDependencySaveDTO request) {
        taskService.require(taskId);
        Set<Long> unique = new HashSet<>();
        for (SqlTaskDependencySaveDTO.Item item : request.getItems()) {
            if (item.getUpstreamTaskId() == taskId) throw badRequest("任务不能依赖自身");
            taskService.require(item.getUpstreamTaskId());
            if (!unique.add(item.getUpstreamTaskId())) throw badRequest("上游任务不能重复");
            if (!"SUCCESS".equals(item.getDependencyType()) && !"COMPLETED".equals(item.getDependencyType())) {
                throw badRequest("dependencyType 仅支持 SUCCESS 或 COMPLETED");
            }
        }
        List<SqlTaskDependency> proposed = new ArrayList<>(dependencyMapper.listAll());
        proposed.removeIf(row -> row.getTaskId() == taskId);
        for (SqlTaskDependencySaveDTO.Item item : request.getItems()) {
            SqlTaskDependency row = new SqlTaskDependency();
            row.setTaskId(taskId); row.setUpstreamTaskId(item.getUpstreamTaskId());
            row.setDependencyType(item.getDependencyType()); row.setCreatedBy(operator);
            proposed.add(row);
        }
        ensureAcyclic(proposed);
        dependencyMapper.deleteByTask(taskId);
        for (SqlTaskDependency row : proposed) if (row.getTaskId() == taskId) dependencyMapper.insert(row);
        return dependencyMapper.listByTask(taskId);
    }

    @Override
    public Map<String, Object> dag() {
        List<SqlTaskDependency> edges = dependencyMapper.listAll();
        Set<Long> nodeIds = new HashSet<>();
        for (SqlTaskDependency edge : edges) { nodeIds.add(edge.getTaskId()); nodeIds.add(edge.getUpstreamTaskId()); }
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Long id : nodeIds) {
            SqlTask task = taskService.require(id);
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", id); node.put("name", task.getName()); node.put("enabled", task.getEnabled());
            nodes.add(node);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", nodes); result.put("edges", edges);
        return result;
    }

    @Override
    @Transactional
    public SqlTaskBackfillBatch createBackfill(String operator, long taskId,
                                               SqlTaskBackfillCreateDTO request) {
        SqlTask task = taskService.require(taskId);
        if (Boolean.TRUE.equals(task.getArchived()) || !Boolean.TRUE.equals(task.getEnabled())) {
            throw badRequest("只有启用中的非归档任务可以补数");
        }
        if (request.getEndDate().isBefore(request.getStartDate())) throw badRequest("结束日期不能早于开始日期");
        long days = ChronoUnit.DAYS.between(request.getStartDate(), request.getEndDate()) + 1;
        if (days > 366) throw badRequest("单次补数最多 366 个业务日期");
        SqlTaskBackfillBatch batch = new SqlTaskBackfillBatch();
        batch.setTaskId(taskId); batch.setStartDate(request.getStartDate()); batch.setEndDate(request.getEndDate());
        batch.setStatus("PENDING"); batch.setTotalCount((int) days); batch.setSubmittedCount(0);
        batch.setSucceededCount(0); batch.setFailedCount(0); batch.setParameterValues(writeJson(request.getParameters()));
        batch.setRequestedBy(operator); backfillMapper.insert(batch);
        LocalDate date = request.getStartDate();
        while (!date.isAfter(request.getEndDate())) {
            launch(task, null, "BACKFILL", LocalDateTime.now(), date, request.getParameters(),
                    1, batch.getId(), operator);
            date = date.plusDays(1);
        }
        backfillMapper.updateProgress(batch.getId());
        return backfillMapper.selectById(batch.getId());
    }

    @Override
    public Map<String, Object> listRuns(long taskId, int page, int pageSize) {
        taskService.require(taskId);
        if (page < 1 || pageSize < 1 || pageSize > 100) throw badRequest("分页参数非法");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", runMapper.listByTask(taskId, (long) (page - 1) * pageSize, pageSize));
        result.put("page", page); result.put("pageSize", pageSize); result.put("total", runMapper.countByTask(taskId));
        return result;
    }

    @Override
    public Map<String, Object> listBackfills(long taskId, int page, int pageSize) {
        taskService.require(taskId);
        if (page < 1 || pageSize < 1 || pageSize > 100) throw badRequest("分页参数非法");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", backfillMapper.listByTask(
                taskId, (long) (page - 1) * pageSize, pageSize));
        result.put("page", page);
        result.put("pageSize", pageSize);
        result.put("total", backfillMapper.countByTask(taskId));
        return result;
    }

    @Override
    public void processDueSchedules() {
        LocalDateTime now = LocalDateTime.now();
        for (SqlTaskSchedule schedule : scheduleMapper.listDue(now, 20)) {
            LocalDateTime due = schedule.getNextTriggerTime();
            LocalDateTime next = nextTrigger(schedule, due.plusSeconds(1));
            if (scheduleMapper.claim(schedule.getId(), due, next) != 1) continue;
            SqlTask task = taskService.require(schedule.getTaskId());
            if (!dependenciesSatisfied(schedule.getTaskId())) {
                recordSkipped(schedule, due, "上游依赖尚未满足"); continue;
            }
            if ("FORBID".equals(schedule.getConcurrencyPolicy())
                    && runMapper.countActiveExecutions(schedule.getTaskId()) > 0) {
                recordSkipped(schedule, due, "已有运行中的实例，并发策略为禁止并行"); continue;
            }
            launch(task, schedule, "CRON", due, businessDate(due, schedule.getTimezone()),
                    readMap(schedule.getParameterValues()), 1, null, SYSTEM_OPERATOR);
        }
    }

    @Override
    public void reconcileAndRetry() {
        runMapper.reconcileExecutionStatuses();
        runMapper.syncScheduleLastRunStatuses();
        runMapper.recoverStaleRetryClaims(LocalDateTime.now().minusMinutes(10));
        for (SqlTaskBackfillBatch batch : backfillMapper.listActive()) backfillMapper.updateProgress(batch.getId());
        for (SqlTaskScheduleRun failed : runMapper.listRetryable(LocalDateTime.now(), 20)) {
            if (runMapper.claimRetry(failed.getId()) != 1) continue;
            SqlTaskSchedule schedule = scheduleMapper.selectById(failed.getScheduleId());
            if (schedule == null) continue;
            launch(taskService.require(failed.getTaskId()), schedule, "RETRY", failed.getScheduledTime(),
                    failed.getBusinessDate(), readMap(failed.getParameterValues()), failed.getAttemptNo() + 1,
                    failed.getBackfillBatchId(), SYSTEM_OPERATOR);
            runMapper.markRetried(failed.getId());
        }
    }

    private void launch(SqlTask task, SqlTaskSchedule schedule, String triggerType,
                        LocalDateTime scheduledTime, LocalDate businessDate, Map<String, Object> parameters,
                        int attempt, Long backfillId, String operator) {
        SqlTaskScheduleRun run = new SqlTaskScheduleRun();
        run.setScheduleId(schedule == null ? null : schedule.getId()); run.setTaskId(task.getId());
        run.setTriggerType(triggerType); run.setScheduledTime(scheduledTime); run.setBusinessDate(businessDate);
        run.setStatus("WAITING"); run.setAttemptNo(attempt); run.setBackfillBatchId(backfillId);
        run.setParameterValues(writeJson(parameters)); run.setCreatedBy(operator); runMapper.insert(run);
        try {
            TaskExecutionCreateDTO body = new TaskExecutionCreateDTO();
            body.setRevision(task.getRevision()); body.setBusinessDate(businessDate); body.setParameters(parameters);
            TaskExecutionVO execution = executionService.create(operator, task.getId(), body);
            run.setExecutionId(execution.getId()); run.setStatus("SUBMITTED"); run.setMessage("已提交执行实例");
            runMapper.updateById(run);
            if (schedule != null) scheduleMapper.updateLastRunStatus(schedule.getId(), "SUBMITTED");
        } catch (RuntimeException ex) {
            run.setStatus("FAILED"); run.setMessage(safeMessage(ex)); runMapper.updateById(run);
            if (schedule != null) scheduleMapper.updateLastRunStatus(schedule.getId(), "FAILED");
        }
    }

    private void recordSkipped(SqlTaskSchedule schedule, LocalDateTime due, String message) {
        SqlTaskScheduleRun run = new SqlTaskScheduleRun();
        run.setScheduleId(schedule.getId()); run.setTaskId(schedule.getTaskId()); run.setTriggerType("CRON");
        run.setScheduledTime(due); run.setBusinessDate(businessDate(due, schedule.getTimezone()));
        run.setStatus("SKIPPED"); run.setAttemptNo(1); run.setParameterValues(schedule.getParameterValues());
        run.setMessage(message); run.setCreatedBy(SYSTEM_OPERATOR); runMapper.insert(run);
        scheduleMapper.updateLastRunStatus(schedule.getId(), "SKIPPED");
    }

    private boolean dependenciesSatisfied(long taskId) {
        for (SqlTaskDependency edge : dependencyMapper.listByTask(taskId)) {
            String status = dependencyMapper.selectLatestExecutionStatus(edge.getUpstreamTaskId());
            if (status == null) return false;
            if ("SUCCESS".equals(edge.getDependencyType()) && !"SUCCEEDED".equals(status)) return false;
            if ("COMPLETED".equals(edge.getDependencyType())
                    && !java.util.Arrays.asList("SUCCEEDED", "FAILED", "CANCELLED").contains(status)) return false;
        }
        return true;
    }

    private void validateSchedule(SqlTaskScheduleSaveDTO request) {
        if (!"MANUAL".equals(request.getScheduleType()) && !"CRON".equals(request.getScheduleType())) {
            throw badRequest("scheduleType 仅支持 MANUAL 或 CRON");
        }
        if (!"FORBID".equals(request.getConcurrencyPolicy()) && !"ALLOW".equals(request.getConcurrencyPolicy())) {
            throw badRequest("concurrencyPolicy 仅支持 FORBID 或 ALLOW");
        }
        try { ZoneId.of(request.getTimezone()); }
        catch (Exception ex) { throw badRequest("timezone 非法"); }
        if ("CRON".equals(request.getScheduleType())) {
            if (request.getCronExpression() == null || request.getCronExpression().trim().isEmpty()) {
                throw badRequest("Cron 调度必须填写 cronExpression");
            }
            try { CronExpression.parse(request.getCronExpression().trim()); }
            catch (IllegalArgumentException ex) { throw badRequest("cronExpression 非法：" + ex.getMessage()); }
        }
        if (Boolean.TRUE.equals(request.getEnabled()) && !"CRON".equals(request.getScheduleType())) {
            throw badRequest("只有 Cron 调度可以启用自动触发");
        }
    }

    private LocalDateTime nextTrigger(SqlTaskSchedule schedule, LocalDateTime reference) {
        if (!Boolean.TRUE.equals(schedule.getEnabled()) || !"CRON".equals(schedule.getScheduleType())) return null;
        ZoneId zone = ZoneId.of(schedule.getTimezone());
        ZoneId systemZone = ZoneId.systemDefault();
        ZonedDateTime scheduleReference = reference.atZone(systemZone).withZoneSameInstant(zone);
        ZonedDateTime next = CronExpression.parse(schedule.getCronExpression()).next(scheduleReference);
        return next == null ? null : next.withZoneSameInstant(systemZone).toLocalDateTime();
    }

    private LocalDate businessDate(LocalDateTime scheduled, String timezone) {
        return scheduled.atZone(ZoneId.systemDefault())
                .withZoneSameInstant(ZoneId.of(timezone)).toLocalDate();
    }

    private void ensureAcyclic(List<SqlTaskDependency> edges) {
        Map<Long, List<Long>> graph = new HashMap<>();
        for (SqlTaskDependency edge : edges) graph.computeIfAbsent(edge.getUpstreamTaskId(), key -> new ArrayList<>()).add(edge.getTaskId());
        Set<Long> visiting = new HashSet<>(); Set<Long> visited = new HashSet<>();
        for (Long node : graph.keySet()) if (cycle(node, graph, visiting, visited)) throw badRequest("任务依赖存在循环");
    }

    private boolean cycle(Long node, Map<Long, List<Long>> graph, Set<Long> visiting, Set<Long> visited) {
        if (visiting.contains(node)) return true;
        if (!visited.add(node)) return false;
        visiting.add(node);
        for (Long next : graph.getOrDefault(node, Collections.emptyList())) if (cycle(next, graph, visiting, visited)) return true;
        visiting.remove(node); return false;
    }

    private SqlTaskSchedule toEntity(long taskId, SqlTaskScheduleSaveDTO request, String operator) {
        SqlTaskSchedule row = new SqlTaskSchedule(); row.setTaskId(taskId); row.setScheduleType(request.getScheduleType());
        row.setCronExpression("CRON".equals(request.getScheduleType()) ? request.getCronExpression().trim() : null);
        row.setTimezone(request.getTimezone()); row.setEnabled(request.getEnabled()); row.setConcurrencyPolicy(request.getConcurrencyPolicy());
        row.setMaxRetries(request.getMaxRetries()); row.setRetryIntervalSeconds(request.getRetryIntervalSeconds());
        row.setParameterValues(writeJson(request.getParameters())); row.setUpdatedBy(operator); return row;
    }

    private SqlTaskScheduleVO defaultSchedule(long taskId) {
        SqlTaskScheduleVO vo = new SqlTaskScheduleVO(); vo.setTaskId(taskId); vo.setScheduleType("MANUAL");
        vo.setTimezone("Asia/Shanghai"); vo.setEnabled(false); vo.setConcurrencyPolicy("FORBID");
        vo.setMaxRetries(0); vo.setRetryIntervalSeconds(60); vo.setRevision(0L); return vo;
    }

    private SqlTaskScheduleVO toVO(SqlTaskSchedule row) {
        SqlTaskScheduleVO vo = new SqlTaskScheduleVO(); vo.setId(row.getId()); vo.setTaskId(row.getTaskId());
        vo.setScheduleType(row.getScheduleType()); vo.setCronExpression(row.getCronExpression()); vo.setTimezone(row.getTimezone());
        vo.setEnabled(row.getEnabled()); vo.setConcurrencyPolicy(row.getConcurrencyPolicy()); vo.setMaxRetries(row.getMaxRetries());
        vo.setRetryIntervalSeconds(row.getRetryIntervalSeconds()); vo.setParameters(readMap(row.getParameterValues()));
        vo.setNextTriggerTime(row.getNextTriggerTime()); vo.setLastTriggerTime(row.getLastTriggerTime());
        vo.setLastRunStatus(row.getLastRunStatus()); vo.setRevision(row.getRevision()); return vo;
    }

    private Map<String, Object> readMap(String value) {
        if (value == null || value.trim().isEmpty()) return new LinkedHashMap<>();
        try { return objectMapper.readValue(value, OBJECT_MAP); } catch (Exception ignored) { return new LinkedHashMap<>(); }
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value == null ? Collections.emptyMap() : value); }
        catch (Exception ex) { throw badRequest("运行参数无法序列化"); }
    }

    private String safeMessage(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.trim().isEmpty() ? "提交执行失败" : message.substring(0, Math.min(1000, message.length()));
    }

    private BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST.getCode(), message);
    }
}
