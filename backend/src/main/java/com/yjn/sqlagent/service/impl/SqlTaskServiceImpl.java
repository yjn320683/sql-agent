package com.yjn.sqlagent.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjn.sqlagent.common.ErrorCode;
import com.yjn.sqlagent.exception.BusinessException;
import com.yjn.sqlagent.mapper.SqlTaskMapper;
import com.yjn.sqlagent.model.dto.SqlTaskQueryDTO;
import com.yjn.sqlagent.model.dto.SqlTaskSaveDTO;
import com.yjn.sqlagent.model.entity.SqlTask;
import com.yjn.sqlagent.model.vo.SqlTaskPageVO;
import com.yjn.sqlagent.model.vo.SqlTaskVO;
import com.yjn.sqlagent.service.SqlTaskService;
import com.yjn.sqlagent.service.TaskSqlStructureService;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SqlTaskServiceImpl implements SqlTaskService {

    private static final String DEFAULT_TASK_TYPE = "RUN_HIVE";
    private static final String DEFAULT_EXECUTION_FREQUENCY = "手动执行";

    private final SqlTaskMapper mapper;
    private final TaskSqlStructureService structureService;

    @Autowired
    public SqlTaskServiceImpl(SqlTaskMapper mapper, TaskSqlStructureService structureService) {
        this.mapper = mapper;
        this.structureService = structureService;
    }

    SqlTaskServiceImpl(SqlTaskMapper mapper) {
        this(mapper, new TaskSqlStructureService(new ObjectMapper()));
    }

    @Override
    public SqlTaskPageVO list(SqlTaskQueryDTO query) {
        validateQuery(query);
        String keyword = normalize(query.getKeyword());
        String updatedBy = normalize(query.getUpdatedBy());
        long total = mapper.countTasks(keyword, query.getStatus(), updatedBy);
        if (total == 0) return new SqlTaskPageVO(Collections.emptyList(), query.getPage(), query.getPageSize(), 0);
        long offset = (long) (query.getPage() - 1) * query.getPageSize();
        List<SqlTaskVO> items = mapper.listTasks(keyword, query.getStatus(), updatedBy, offset, query.getPageSize())
                .stream().map(this::toVO).collect(Collectors.toList());
        return new SqlTaskPageVO(items, query.getPage(), query.getPageSize(), total);
    }

    @Override
    public SqlTaskVO get(long taskId) {
        return toVO(require(taskId));
    }

    @Override
    public SqlTask require(long taskId) {
        SqlTask task = mapper.selectById(taskId);
        if (task == null) throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "任务不存在");
        return task;
    }

    @Override
    @Transactional
    public SqlTaskVO create(String obId, SqlTaskSaveDTO request) {
        SqlTask task = new SqlTask();
        apply(task, obId, request, null);
        LocalDateTime now = LocalDateTime.now();
        task.setRevision(1L);
        task.setArchived(Boolean.FALSE);
        task.setEnabled(Boolean.TRUE);
        task.setCreatedBy(obId);
        task.setCreateTime(now);
        task.setUpdateTime(now);
        mapper.insert(task);
        return toVO(task);
    }

    @Override
    @Transactional
    public SqlTaskVO update(String obId, long taskId, SqlTaskSaveDTO request) {
        if (request.getRevision() == null || request.getRevision() < 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "revision不能为空");
        }
        SqlTask existing = require(taskId);
        if (Boolean.TRUE.equals(existing.getArchived())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "归档任务不能编辑，请先恢复");
        }
        if (!Objects.equals(existing.getRevision(), request.getRevision())) throw conflict(existing);

        SqlTask next = new SqlTask();
        next.setId(taskId);
        apply(next, obId, request, existing);
        if (!Objects.equals(existing.getName(), next.getName())
                || !Objects.equals(existing.getDescription(), next.getDescription())
                || !Objects.equals(existing.getSqlContent(), next.getSqlContent())
                || !Objects.equals(existing.getDdlContent(), next.getDdlContent())
                || !Objects.equals(existing.getParameterSchema(), next.getParameterSchema())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(),
                    "生效代码不能直接修改，请创建或编辑版本后执行生效操作");
        }
        if (Objects.equals(existing.getTaskType(), next.getTaskType())
                && Objects.equals(existing.getExecutionFrequency(), next.getExecutionFrequency())
                && Objects.equals(existing.getOwner(), next.getOwner())) {
            return toVO(existing);
        }
        int updated = mapper.updateMetadataOptimistically(next, request.getRevision());
        if (updated != 1) throw conflict(require(taskId));
        return get(taskId);
    }

    @Override
    @Transactional
    public SqlTaskVO setEnabled(String obId, long taskId, long revision, boolean enabled) {
        SqlTask task = require(taskId);
        if (Boolean.TRUE.equals(task.getArchived())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "归档任务不能变更启停状态，请先恢复");
        }
        if (!Objects.equals(task.getRevision(), revision)) throw conflict(task);
        if (Boolean.TRUE.equals(task.getEnabled()) == enabled) return toVO(task);
        if (mapper.updateEnabledOptimistically(taskId, revision, enabled, obId) != 1) {
            throw conflict(require(taskId));
        }
        return get(taskId);
    }

    @Override
    @Transactional
    public SqlTaskVO setArchived(String obId, long taskId, long revision, boolean archived) {
        SqlTask task = require(taskId);
        if (!Objects.equals(task.getRevision(), revision)) throw conflict(task);
        if (Boolean.TRUE.equals(task.getArchived()) == archived) return toVO(task);
        if (mapper.updateArchiveOptimistically(taskId, revision, archived, obId) != 1) {
            throw conflict(require(taskId));
        }
        return get(taskId);
    }

    @Override
    @Transactional
    public SqlTaskVO cloneTask(String obId, long taskId) {
        SqlTask source = require(taskId);
        SqlTask copy = new SqlTask();
        String suffix = " 副本";
        String name = source.getName() + suffix;
        copy.setName(name.length() > 128 ? name.substring(0, 128) : name);
        copy.setDescription(source.getDescription());
        copy.setTaskType(resolveText(null, source.getTaskType(), DEFAULT_TASK_TYPE));
        copy.setExecutionFrequency(resolveText(null, source.getExecutionFrequency(), DEFAULT_EXECUTION_FREQUENCY));
        copy.setOwner(resolveText(null, source.getOwner(), obId));
        copy.setEnabled(source.getEnabled() == null || source.getEnabled());
        copy.setSqlContent(source.getSqlContent());
        copy.setDdlContent(source.getDdlContent());
        copy.setParameterSchema(source.getParameterSchema());
        copy.setSqlChecksum(source.getSqlChecksum());
        copy.setEffectiveVersionNo(null);
        copy.setRevision(1L);
        copy.setArchived(Boolean.FALSE);
        copy.setCreatedBy(obId);
        copy.setUpdatedBy(obId);
        copy.setCreateTime(LocalDateTime.now());
        copy.setUpdateTime(copy.getCreateTime());
        mapper.insert(copy);
        return toVO(copy);
    }

    private void apply(SqlTask task, String obId, SqlTaskSaveDTO request, SqlTask existing) {
        String parameterSchema = structureService.serializeParameters(request.getParameters());
        task.setName(request.getName().trim());
        task.setDescription(normalize(request.getDescription()));
        task.setTaskType(resolveText(request.getTaskType(), existing == null ? null : existing.getTaskType(), DEFAULT_TASK_TYPE));
        task.setExecutionFrequency(resolveText(request.getExecutionFrequency(),
                existing == null ? null : existing.getExecutionFrequency(), DEFAULT_EXECUTION_FREQUENCY));
        task.setOwner(resolveText(request.getOwner(), existing == null ? null : existing.getOwner(), obId));
        task.setEnabled(existing == null || existing.getEnabled() == null || existing.getEnabled());
        task.setSqlContent(request.getSql().trim());
        task.setDdlContent(normalize(request.getDdl()));
        task.setParameterSchema(parameterSchema);
        task.setSqlChecksum(contentChecksum(task.getSqlContent(), task.getDdlContent(), parameterSchema));
        task.setUpdatedBy(obId);
    }

    private void validateQuery(SqlTaskQueryDTO query) {
        if (query == null || query.getPage() == null || query.getPage() < 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "page 必须大于0");
        }
        if (query.getPageSize() == null || query.getPageSize() < 1 || query.getPageSize() > 100) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "pageSize 必须在1到100之间");
        }
        if (query.getKeyword() != null && query.getKeyword().length() > 128) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "keyword 不能超过128个字符");
        }
        if (!"active".equals(query.getStatus()) && !"archived".equals(query.getStatus())
                && !"all".equals(query.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "status 非法");
        }
        if (query.getUpdatedBy() != null && query.getUpdatedBy().length() > 20) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "updatedBy 非法");
        }
    }

    private BusinessException conflict(SqlTask latest) {
        return new BusinessException(ErrorCode.CONFLICT.getCode(),
                "任务已被其他用户更新，当前最新 revision=" + latest.getRevision());
    }

    private String normalize(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String contentChecksum(String sql, String ddl, String parameterSchema) {
        return structureService.checksum(sql + "\n-- DDL --\n" + (ddl == null ? "" : ddl), parameterSchema);
    }

    private String resolveText(String requested, String existing, String fallback) {
        String value = normalize(requested);
        if (value != null) return value;
        value = normalize(existing);
        return value == null ? fallback : value;
    }

    private SqlTaskVO toVO(SqlTask task) {
        SqlTaskVO vo = new SqlTaskVO();
        vo.setId(task.getId());
        vo.setName(task.getName());
        vo.setDescription(task.getDescription());
        vo.setTaskType(task.getTaskType());
        vo.setExecutionFrequency(task.getExecutionFrequency());
        vo.setOwner(task.getOwner());
        vo.setEnabled(task.getEnabled() == null || task.getEnabled());
        vo.setSql(task.getSqlContent());
        vo.setDdl(task.getDdlContent());
        vo.setParameters(structureService.deserializeParameters(task.getParameterSchema()));
        vo.setSqlChecksum(task.getSqlChecksum());
        vo.setRevision(task.getRevision());
        vo.setEffectiveVersionNo(task.getEffectiveVersionNo());
        vo.setArchived(Boolean.TRUE.equals(task.getArchived()));
        vo.setArchivedBy(task.getArchivedBy());
        vo.setArchivedAt(task.getArchivedTime());
        vo.setCreatedBy(task.getCreatedBy());
        vo.setUpdatedBy(task.getUpdatedBy());
        vo.setCreatedAt(task.getCreateTime());
        vo.setUpdatedAt(task.getUpdateTime());
        vo.setLastExecutionStatus(task.getLastExecutionStatus());
        vo.setLastExecutionAt(task.getLastExecutionAt());
        vo.setLatestVersionNo(task.getLatestVersionNo());
        vo.setStepCount(task.getStepCount());
        return vo;
    }
}
