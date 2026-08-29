package com.yjn.sqlagent.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjn.sqlagent.common.ErrorCode;
import com.yjn.sqlagent.exception.BusinessException;
import com.yjn.sqlagent.mapper.SqlTaskMapper;
import com.yjn.sqlagent.mapper.SqlTaskVersionMapper;
import com.yjn.sqlagent.mapper.SqlTaskVersionStepMapper;
import com.yjn.sqlagent.model.dto.SqlTaskVersionActivateDTO;
import com.yjn.sqlagent.model.dto.SqlTaskVersionSaveDTO;
import com.yjn.sqlagent.model.entity.SqlTask;
import com.yjn.sqlagent.model.entity.SqlTaskVersion;
import com.yjn.sqlagent.model.entity.SqlTaskVersionStep;
import com.yjn.sqlagent.model.vo.SqlTaskStepVO;
import com.yjn.sqlagent.model.vo.SqlTaskVersionPageVO;
import com.yjn.sqlagent.model.vo.SqlTaskVersionVO;
import com.yjn.sqlagent.service.SqlTaskVersionService;
import com.yjn.sqlagent.service.TaskSqlStructureService;
import com.yjn.sqlagent.service.TaskVersionUnionService;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SqlTaskVersionServiceImpl implements SqlTaskVersionService {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<List<String>>() { };
    private static final String DRAFT = "DRAFT";

    private final SqlTaskMapper taskMapper;
    private final SqlTaskVersionMapper versionMapper;
    private final SqlTaskVersionStepMapper stepMapper;
    private final TaskSqlStructureService structureService;
    private final ObjectMapper objectMapper;
    private final TaskVersionUnionService unionService;

    public SqlTaskVersionServiceImpl(SqlTaskMapper taskMapper,
                                     SqlTaskVersionMapper versionMapper,
                                     SqlTaskVersionStepMapper stepMapper,
                                     TaskSqlStructureService structureService,
                                     ObjectMapper objectMapper,
                                     TaskVersionUnionService unionService) {
        this.taskMapper = taskMapper;
        this.versionMapper = versionMapper;
        this.stepMapper = stepMapper;
        this.structureService = structureService;
        this.objectMapper = objectMapper;
        this.unionService = unionService;
    }

    @Override
    @Transactional
    public SqlTaskVersionVO create(String operatorObId, long taskId, String note, long taskRevision) {
        SqlTask task = requireTaskForUpdate(taskId);
        requireActiveTask(task);
        if (!Objects.equals(task.getRevision(), taskRevision)) throw taskConflict(task);

        String normalizedNote = normalizeNote(note, true);
        int versionNo = versionMapper.nextVersionNo(taskId);
        SqlTaskVersion version = new SqlTaskVersion();
        version.setTaskId(taskId);
        version.setVersionNo(versionNo);
        version.setBaseEffectiveVersionNo(task.getEffectiveVersionNo());
        version.setBaseEffectiveChecksum(task.getSqlChecksum());
        version.setName(task.getName());
        version.setDescription(task.getDescription());
        version.setSqlContent(task.getSqlContent());
        version.setDdlContent(task.getDdlContent());
        version.setParameterSchema(defaultParameters(task.getParameterSchema()));
        version.setSqlChecksum(task.getSqlChecksum());
        version.setVersionNote(normalizedNote);
        version.setStatus(DRAFT);
        version.setRevision(1L);
        version.setCreatedBy(operatorObId);
        version.setUpdatedBy(operatorObId);
        LocalDateTime now = LocalDateTime.now();
        version.setCreateTime(now);
        version.setUpdateTime(now);
        versionMapper.insert(version);
        replaceSteps(version);
        return toVO(version, stepMapper.listByVersion(taskId, versionNo), task);
    }

    @Override
    @Transactional
    public SqlTaskVersionVO save(String obId, long taskId, int versionNo, SqlTaskVersionSaveDTO request) {
        SqlTask task = requireTaskForUpdate(taskId);
        requireActiveTask(task);
        SqlTaskVersion current = requireVersionForUpdate(taskId, versionNo);
        requireEditableBaseline(task, current);
        if (!Objects.equals(current.getRevision(), request.getRevision())) throw versionConflict(current);

        String parameterSchema = structureService.serializeParameters(request.getParameters());
        String sql = request.getSql().trim();
        SqlTaskVersion next = new SqlTaskVersion();
        next.setTaskId(taskId);
        next.setVersionNo(versionNo);
        next.setName(request.getName().trim());
        next.setDescription(normalize(request.getDescription()));
        next.setSqlContent(sql);
        next.setDdlContent(normalize(request.getDdl()));
        next.setParameterSchema(parameterSchema);
        next.setSqlChecksum(contentChecksum(sql, next.getDdlContent(), parameterSchema));
        next.setVersionNote(request.getNote() == null
                ? current.getVersionNote() : normalizeNote(request.getNote(), false));
        next.setUpdatedBy(obId);

        if (!hasContentChange(current, next)) {
            return toVO(current, stepMapper.listByVersion(taskId, versionNo), task);
        }
        List<SqlTaskVersionStep> steps = structureService.parseVersionSteps(taskId, versionNo, sql);
        if (versionMapper.updateDraftOptimistically(next, request.getRevision()) != 1) {
            throw versionConflict(requireVersion(taskId, versionNo));
        }
        stepMapper.deleteByVersion(taskId, versionNo);
        for (SqlTaskVersionStep step : steps) stepMapper.insert(step);
        unionService.invalidateMemberCompare(taskId, versionNo);
        return get(taskId, versionNo);
    }

    @Override
    @Transactional
    public SqlTaskVersionVO activate(String obId, long taskId, int versionNo,
                                     SqlTaskVersionActivateDTO request) {
        SqlTask task = requireTaskForUpdate(taskId);
        requireActiveTask(task);
        if (!Objects.equals(task.getRevision(), request.getTaskRevision())) throw taskConflict(task);
        SqlTaskVersion version = requireVersionForUpdate(taskId, versionNo);
        requireEditableBaseline(task, version);
        if (unionService.isActiveMember(taskId, versionNo)) {
            throw badRequest("该版本已加入联合版本，只能从联合版本页统一发布");
        }
        if (!Objects.equals(version.getRevision(), request.getVersionRevision())) throw versionConflict(version);

        // 生效前再次完整解析，保证写入任务的是已校验版本。
        List<SqlTaskVersionStep> steps = structureService.parseVersionSteps(taskId, versionNo, version.getSqlContent());
        SqlTask effective = new SqlTask();
        effective.setId(taskId);
        effective.setName(version.getName());
        effective.setDescription(version.getDescription());
        effective.setSqlContent(version.getSqlContent());
        effective.setDdlContent(version.getDdlContent());
        effective.setParameterSchema(version.getParameterSchema());
        effective.setSqlChecksum(version.getSqlChecksum());
        effective.setEffectiveVersionNo(versionNo);
        effective.setUpdatedBy(obId);
        if (taskMapper.activateVersionOptimistically(effective, request.getTaskRevision()) != 1) {
            throw taskConflict(requireTask(taskId));
        }
        versionMapper.markPreviousEffectiveHistorical(taskId, versionNo, obId);
        versionMapper.markOtherDraftsStale(taskId, versionNo, obId);
        if (versionMapper.markEffective(taskId, versionNo, request.getVersionRevision(), obId) != 1) {
            throw versionConflict(requireVersion(taskId, versionNo));
        }
        stepMapper.deleteByVersion(taskId, versionNo);
        for (SqlTaskVersionStep step : steps) stepMapper.insert(step);
        return get(taskId, versionNo);
    }

    @Override
    public SqlTaskVersionPageVO list(long taskId, int page, int pageSize, String keyword) {
        SqlTask task = requireTask(taskId);
        validatePage(page, pageSize);
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        long total = versionMapper.countVersions(taskId, normalizedKeyword);
        if (total == 0) return new SqlTaskVersionPageVO(Collections.emptyList(), page, pageSize, 0);
        long offset = (long) (page - 1) * pageSize;
        List<SqlTaskVersionVO> items = versionMapper.listVersions(taskId, normalizedKeyword, offset, pageSize)
                .stream().map(version -> toVO(version, Collections.emptyList(), task)).collect(Collectors.toList());
        return new SqlTaskVersionPageVO(items, page, pageSize, total);
    }

    @Override
    public SqlTaskVersionVO get(long taskId, int versionNo) {
        SqlTask task = requireTask(taskId);
        SqlTaskVersion version = requireVersion(taskId, versionNo);
        return toVO(version, stepMapper.listByVersion(taskId, versionNo), task);
    }

    private void replaceSteps(SqlTaskVersion version) {
        List<SqlTaskVersionStep> steps = structureService.parseVersionSteps(
                version.getTaskId(), version.getVersionNo(), version.getSqlContent());
        for (SqlTaskVersionStep step : steps) stepMapper.insert(step);
    }

    private boolean hasContentChange(SqlTaskVersion current, SqlTaskVersion next) {
        return !Objects.equals(current.getName(), next.getName())
                || !Objects.equals(current.getDescription(), next.getDescription())
                || !Objects.equals(current.getSqlContent(), next.getSqlContent())
                || !Objects.equals(current.getDdlContent(), next.getDdlContent())
                || !Objects.equals(defaultParameters(current.getParameterSchema()), next.getParameterSchema())
                || !Objects.equals(current.getVersionNote(), next.getVersionNote());
    }

    private void requireEditableBaseline(SqlTask task, SqlTaskVersion version) {
        if (!DRAFT.equals(version.getStatus())) {
            throw badRequest("只有开发中状态的版本可以修改或生效");
        }
        if (!Objects.equals(version.getBaseEffectiveVersionNo(), task.getEffectiveVersionNo())
                || !Objects.equals(version.getBaseEffectiveChecksum(), task.getSqlChecksum())) {
            throw new BusinessException(ErrorCode.CONFLICT.getCode(),
                    "版本基线已过期，请基于最新生效代码创建新版本");
        }
    }

    private void requireActiveTask(SqlTask task) {
        if (Boolean.TRUE.equals(task.getArchived())) throw badRequest("归档任务不能维护版本");
    }

    private SqlTask requireTask(long taskId) {
        SqlTask task = taskMapper.selectById(taskId);
        if (task == null) throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "任务不存在");
        return task;
    }

    private SqlTask requireTaskForUpdate(long taskId) {
        SqlTask task = taskMapper.selectByIdForUpdate(taskId);
        if (task == null) throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "任务不存在");
        return task;
    }

    private SqlTaskVersion requireVersion(long taskId, int versionNo) {
        if (versionNo < 1) throw badRequest("versionNo 必须大于0");
        SqlTaskVersion version = versionMapper.selectVersion(taskId, versionNo);
        if (version == null) throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "任务版本不存在");
        return version;
    }

    private SqlTaskVersion requireVersionForUpdate(long taskId, int versionNo) {
        if (versionNo < 1) throw badRequest("versionNo 必须大于0");
        SqlTaskVersion version = versionMapper.selectVersionForUpdate(taskId, versionNo);
        if (version == null) throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "任务版本不存在");
        return version;
    }

    private void validatePage(int page, int pageSize) {
        if (page < 1) throw badRequest("page 必须大于0");
        if (pageSize < 1 || pageSize > 100) throw badRequest("pageSize 必须在1到100之间");
    }

    private SqlTaskVersionVO toVO(SqlTaskVersion version, List<SqlTaskVersionStep> steps, SqlTask task) {
        SqlTaskVersionVO vo = new SqlTaskVersionVO();
        vo.setId(version.getId());
        vo.setTaskId(version.getTaskId());
        vo.setVersionNo(version.getVersionNo());
        vo.setBaseEffectiveVersionNo(version.getBaseEffectiveVersionNo());
        vo.setBaseEffectiveChecksum(version.getBaseEffectiveChecksum());
        vo.setName(version.getName());
        vo.setDescription(version.getDescription());
        vo.setSql(version.getSqlContent());
        vo.setDdl(version.getDdlContent());
        vo.setParameters(structureService.deserializeParameters(version.getParameterSchema()));
        vo.setSqlChecksum(version.getSqlChecksum());
        vo.setVersionNote(version.getVersionNote());
        vo.setStatus(version.getStatus());
        vo.setRevision(version.getRevision());
        vo.setCanEdit(DRAFT.equals(version.getStatus())
                && !Boolean.TRUE.equals(task.getArchived())
                && Objects.equals(version.getBaseEffectiveVersionNo(), task.getEffectiveVersionNo())
                && Objects.equals(version.getBaseEffectiveChecksum(), task.getSqlChecksum()));
        boolean inUnion = unionService.isActiveMember(version.getTaskId(), version.getVersionNo());
        vo.setInUnion(inUnion);
        vo.setCanActivate(Boolean.TRUE.equals(vo.getCanEdit()) && !inUnion);
        vo.setCreatedBy(version.getCreatedBy());
        vo.setUpdatedBy(version.getUpdatedBy());
        vo.setEffectiveBy(version.getEffectiveBy());
        vo.setEffectiveAt(version.getEffectiveTime());
        vo.setCreatedAt(version.getCreateTime());
        vo.setUpdatedAt(version.getUpdateTime());
        vo.setSteps(steps.stream().map(this::stepVO).collect(Collectors.toList()));
        return vo;
    }

    private SqlTaskStepVO stepVO(SqlTaskVersionStep row) {
        SqlTaskStepVO vo = new SqlTaskStepVO();
        vo.setId(row.getId());
        vo.setStepNo(row.getStepNo());
        vo.setStepOrder(row.getStepOrder());
        vo.setStepName(row.getStepName());
        vo.setSql(row.getStepSql());
        vo.setStatementType(row.getStatementType());
        vo.setInputTables(parseList(row.getInputTables()));
        vo.setOutputTables(parseList(row.getOutputTables()));
        return vo;
    }

    private List<String> parseList(String value) {
        if (value == null || value.trim().isEmpty()) return Collections.emptyList();
        try { return objectMapper.readValue(value, STRING_LIST); }
        catch (Exception ignored) { return Collections.emptyList(); }
    }

    private String normalizeNote(String value, boolean required) {
        String note = normalize(value);
        if (required && note == null) throw badRequest("版本说明不能为空");
        if (note != null && note.length() > 512) throw badRequest("版本说明不能超过512个字符");
        return note;
    }

    private String normalize(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String contentChecksum(String sql, String ddl, String parameterSchema) {
        return structureService.checksum(sql + "\n-- DDL --\n" + (ddl == null ? "" : ddl), parameterSchema);
    }

    private String defaultParameters(String value) {
        return value == null || value.trim().isEmpty() ? "[]" : value;
    }

    private BusinessException taskConflict(SqlTask task) {
        return new BusinessException(ErrorCode.CONFLICT.getCode(),
                "任务生效代码已更新，当前最新 revision=" + task.getRevision());
    }

    private BusinessException versionConflict(SqlTaskVersion version) {
        return new BusinessException(ErrorCode.CONFLICT.getCode(),
                "版本已被其他用户更新，当前最新 revision=" + version.getRevision());
    }

    private BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST.getCode(), message);
    }
}
