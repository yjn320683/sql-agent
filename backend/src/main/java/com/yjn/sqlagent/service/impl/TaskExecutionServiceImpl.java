package com.yjn.sqlagent.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjn.sqlagent.common.ErrorCode;
import com.yjn.sqlagent.exception.BusinessException;
import com.yjn.sqlagent.mapper.SqlTaskExecutionMapper;
import com.yjn.sqlagent.mapper.SqlTaskExecutionStepMapper;
import com.yjn.sqlagent.model.dto.ExecutionCenterQueryDTO;
import com.yjn.sqlagent.model.dto.ExecutionQueryDTO;
import com.yjn.sqlagent.model.dto.TaskExecutionCreateDTO;
import com.yjn.sqlagent.model.entity.SqlTask;
import com.yjn.sqlagent.model.entity.SqlTaskExecution;
import com.yjn.sqlagent.model.entity.SqlTaskExecutionStep;
import com.yjn.sqlagent.model.vo.ExecutionSummaryVO;
import com.yjn.sqlagent.model.vo.SqlTaskStepVO;
import com.yjn.sqlagent.model.vo.SqlTaskVersionVO;
import com.yjn.sqlagent.model.vo.TaskExecutionPageVO;
import com.yjn.sqlagent.model.vo.TaskExecutionVO;
import com.yjn.sqlagent.service.AgentProxyService;
import com.yjn.sqlagent.service.SqlTaskService;
import com.yjn.sqlagent.service.SqlTaskVersionService;
import com.yjn.sqlagent.service.TaskExecutionService;
import com.yjn.sqlagent.service.TaskSqlStructureService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TaskExecutionServiceImpl implements TaskExecutionService {
    private static final Logger log = LoggerFactory.getLogger(TaskExecutionServiceImpl.class);
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<List<String>>() { };
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<Map<String, Object>>() { };

    private final SqlTaskExecutionMapper mapper;
    private final SqlTaskExecutionStepMapper stepMapper;
    private final SqlTaskService taskService;
    private final SqlTaskVersionService versionService;
    private final AgentProxyService agentProxyService;
    private final TaskSqlStructureService structureService;
    private final ObjectMapper objectMapper;

    public TaskExecutionServiceImpl(SqlTaskExecutionMapper mapper,
                                    SqlTaskExecutionStepMapper stepMapper,
                                    SqlTaskService taskService,
                                    SqlTaskVersionService versionService,
                                    AgentProxyService agentProxyService,
                                    TaskSqlStructureService structureService,
                                    ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.stepMapper = stepMapper;
        this.taskService = taskService;
        this.versionService = versionService;
        this.agentProxyService = agentProxyService;
        this.structureService = structureService;
        this.objectMapper = objectMapper;
    }

    @Override
    public TaskExecutionVO create(String obId, long taskId, TaskExecutionCreateDTO request) {
        TaskExecutionCreateDTO body = request == null ? new TaskExecutionCreateDTO() : request;
        SqlTask task = taskService.require(taskId);
        if (Boolean.TRUE.equals(task.getArchived())) throw badRequest("归档任务不能调试");
        if (!Boolean.TRUE.equals(task.getEnabled())) throw badRequest("停用任务不能调试，请先启用");

        SqlTaskExecution execution = new SqlTaskExecution();
        execution.setTaskId(taskId);
        execution.setRequestedBy(obId);
        execution.setStatus("PENDING");
        execution.setSubmittedAt(LocalDateTime.now());
        execution.setBusinessDate(body.getBusinessDate());
        execution.setParameterValues(writeJson(body.getParameters() == null
                ? Collections.emptyMap() : body.getParameters()));

        if (body.getVersionNo() == null) {
            if (body.getRevision() == null || !Objects.equals(body.getRevision(), task.getRevision())) {
                throw new BusinessException(ErrorCode.CONFLICT.getCode(),
                        "生效代码已更新，请刷新后重新执行，当前 revision=" + task.getRevision());
            }
            execution.setTaskNameSnapshot(task.getName());
            execution.setSqlSnapshot(task.getSqlContent());
            execution.setParameterSchemaSnapshot(task.getParameterSchema());
            execution.setSourceType("EFFECTIVE");
            execution.setTaskVersionNo(task.getEffectiveVersionNo());
            execution.setTaskRevision(task.getRevision());
        } else {
            SqlTaskVersionVO version = versionService.get(taskId, body.getVersionNo());
            execution.setTaskNameSnapshot(version.getName());
            execution.setSqlSnapshot(version.getSql());
            execution.setParameterSchemaSnapshot(writeJson(version.getParameters()));
            execution.setSourceType("VERSION");
            execution.setTaskVersionNo(body.getVersionNo());
        }
        execution.setTotalSteps(structureService.parseVersionSteps(taskId, 0, execution.getSqlSnapshot()).size());
        execution.setSucceededSteps(0);
        mapper.insert(execution);
        try {
            agentProxyService.startTaskExecution(execution.getId());
        } catch (RuntimeException ex) {
            mapper.failPending(execution.getId(), "Agent 未接受执行请求");
            log.error("启动任务实例失败 executionId={}", execution.getId(), ex);
        }
        return get(execution.getId());
    }

    @Override
    public TaskExecutionPageVO list(long taskId, ExecutionQueryDTO query) {
        taskService.require(taskId);
        validateQuery(query);
        String keyword = normalize(query.getKeyword());
        long total = mapper.countByTask(taskId, query.getStatus(), keyword);
        if (total == 0) return new TaskExecutionPageVO(Collections.emptyList(), query.getPage(), query.getPageSize(), 0);
        long offset = (long) (query.getPage() - 1) * query.getPageSize();
        List<TaskExecutionVO> items = mapper.listByTask(taskId, query.getStatus(), keyword, offset, query.getPageSize())
                .stream().map(row -> toVO(row, false)).collect(Collectors.toList());
        return new TaskExecutionPageVO(items, query.getPage(), query.getPageSize(), total);
    }

    @Override
    public TaskExecutionPageVO listAll(ExecutionCenterQueryDTO query) {
        validateCenterQuery(query);
        String keyword = normalize(query.getKeyword());
        long total = mapper.countAll(query.getStatus(), keyword);
        if (total == 0) return new TaskExecutionPageVO(Collections.emptyList(), query.getPage(), query.getPageSize(), 0);
        long offset = (long) (query.getPage() - 1) * query.getPageSize();
        List<TaskExecutionVO> items = mapper.listAll(query.getStatus(), keyword, offset, query.getPageSize())
                .stream().map(row -> toVO(row, false)).collect(Collectors.toList());
        return new TaskExecutionPageVO(items, query.getPage(), query.getPageSize(), total);
    }

    @Override
    public ExecutionSummaryVO summary() {
        ExecutionSummaryVO summary = mapper.summarize();
        return summary == null ? new ExecutionSummaryVO() : summary;
    }

    @Override
    public TaskExecutionVO get(long executionId) {
        SqlTaskExecution execution = mapper.selectById(executionId);
        if (execution == null) throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "执行实例不存在");
        return toVO(execution, true);
    }

    @Override
    public TaskExecutionVO requireBelongsToTask(long executionId, long taskId) {
        TaskExecutionVO execution = get(executionId);
        if (!Long.valueOf(taskId).equals(execution.getTaskId())) throw badRequest("执行实例不属于当前任务");
        return execution;
    }

    @Override
    public void cancel(long executionId) {
        TaskExecutionVO execution = get(executionId);
        if (!"QUEUED".equals(execution.getStatus()) && !"RUNNING".equals(execution.getStatus())) {
            throw badRequest("当前状态不允许取消");
        }
        agentProxyService.cancelTaskExecution(executionId);
    }

    private TaskExecutionVO toVO(SqlTaskExecution row, boolean includeSteps) {
        TaskExecutionVO vo = new TaskExecutionVO();
        vo.setId(row.getId());
        vo.setTaskId(row.getTaskId());
        vo.setTaskName(row.getTaskNameSnapshot());
        vo.setSourceType(row.getSourceType() == null ? "EFFECTIVE" : row.getSourceType());
        vo.setTaskVersionNo(row.getTaskVersionNo());
        vo.setTaskRevision(row.getTaskRevision());
        vo.setStatus(row.getStatus());
        vo.setCurrentStepNo(row.getCurrentStepNo());
        vo.setTotalSteps(row.getTotalSteps());
        vo.setSucceededSteps(row.getSucceededSteps());
        vo.setFailedStepNo(row.getFailedStepNo());
        vo.setBusinessDate(row.getBusinessDate());
        vo.setParameters(parseMap(row.getParameterValues()));
        vo.setRequestedBy(row.getRequestedBy());
        vo.setQueryId(row.getQueryId());
        vo.setApplicationIds(parseList(row.getApplicationIds()));
        vo.setJobIds(parseList(row.getJobIds()));
        vo.setErrorMessage(row.getErrorMessage());
        vo.setSubmittedAt(row.getSubmittedAt());
        vo.setStartedAt(row.getStartedAt());
        vo.setFinishedAt(row.getFinishedAt());
        if (row.getStartedAt() != null && row.getFinishedAt() != null) {
            vo.setDurationMs(Duration.between(row.getStartedAt(), row.getFinishedAt()).toMillis());
        }
        if (includeSteps) vo.setSteps(stepMapper.listByExecution(row.getId()).stream()
                .map(this::stepVO).collect(Collectors.toList()));
        return vo;
    }

    private SqlTaskStepVO stepVO(SqlTaskExecutionStep row) {
        SqlTaskStepVO vo = new SqlTaskStepVO();
        vo.setId(row.getId()); vo.setStepNo(row.getStepNo()); vo.setStepOrder(row.getStepOrder());
        vo.setStepName(row.getStepName()); vo.setSql(row.getSourceSqlSnapshot()); vo.setStatus(row.getStatus());
        vo.setQueryId(row.getQueryId()); vo.setApplicationIds(parseList(row.getApplicationIds()));
        vo.setJobIds(parseList(row.getJobIds())); vo.setErrorMessage(row.getErrorMessage());
        vo.setLogFile(row.getLogFile()); vo.setStartedAt(row.getStartedAt()); vo.setFinishedAt(row.getFinishedAt());
        if (row.getStartedAt() != null && row.getFinishedAt() != null) {
            vo.setDurationMs(Duration.between(row.getStartedAt(), row.getFinishedAt()).toMillis());
        }
        return vo;
    }

    private void validateQuery(ExecutionQueryDTO query) {
        if (query == null || query.getPage() == null || query.getPage() < 1) throw badRequest("page 必须大于0");
        if (query.getPageSize() == null || query.getPageSize() < 1 || query.getPageSize() > 100) {
            throw badRequest("pageSize 必须在1到100之间");
        }
        validateStatus(query.getStatus(), false);
        if (query.getKeyword() != null && query.getKeyword().length() > 256) throw badRequest("keyword 不能超过256个字符");
    }

    private void validateCenterQuery(ExecutionCenterQueryDTO query) {
        if (query == null || query.getPage() == null || query.getPage() < 1) throw badRequest("page 必须大于0");
        if (query.getPageSize() == null || query.getPageSize() < 1 || query.getPageSize() > 100) {
            throw badRequest("pageSize 必须在1到100之间");
        }
        validateStatus(query.getStatus(), true);
        if (query.getKeyword() != null && query.getKeyword().length() > 256) throw badRequest("keyword 不能超过256个字符");
    }

    private void validateStatus(String status, boolean grouped) {
        boolean valid = "all".equals(status) || "PENDING".equals(status) || "QUEUED".equals(status)
                || "RUNNING".equals(status) || "SUCCEEDED".equals(status) || "FAILED".equals(status)
                || "CANCELLING".equals(status) || "CANCELLED".equals(status)
                || (grouped && ("active".equals(status) || "cancelled".equals(status)));
        if (!valid) throw badRequest("status 非法");
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value == null ? Collections.emptyMap() : value); }
        catch (Exception exc) { throw badRequest("执行参数无法序列化"); }
    }

    private List<String> parseList(String value) {
        if (value == null || value.trim().isEmpty()) return Collections.emptyList();
        try { return objectMapper.readValue(value, STRING_LIST); } catch (Exception ignored) { return Collections.emptyList(); }
    }

    private Map<String, Object> parseMap(String value) {
        if (value == null || value.trim().isEmpty()) return Collections.emptyMap();
        try { return objectMapper.readValue(value, OBJECT_MAP); } catch (Exception ignored) { return new LinkedHashMap<>(); }
    }

    private String normalize(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST.getCode(), message);
    }
}
