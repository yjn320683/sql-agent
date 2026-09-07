package com.yjn.sqlagent.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjn.sqlagent.mapper.SqlTaskVersionCheckMapper;
import com.yjn.sqlagent.model.entity.SqlTaskVersionCheck;
import com.yjn.sqlagent.model.vo.SqlTaskVersionVO;
import com.yjn.sqlagent.model.vo.TaskVersionCheckSummaryVO;
import com.yjn.sqlagent.service.SqlTaskVersionService;
import com.yjn.sqlagent.service.TaskVersionCheckService;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class TaskVersionCheckServiceImpl implements TaskVersionCheckService {
    private static final List<String> REQUIRED_CHECKS = java.util.Arrays.asList("VALIDATE", "QUALITY");

    private final SqlTaskVersionCheckMapper mapper;
    private final SqlTaskVersionService versionService;
    private final ObjectMapper objectMapper;

    public TaskVersionCheckServiceImpl(SqlTaskVersionCheckMapper mapper,
                                       SqlTaskVersionService versionService,
                                       ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.versionService = versionService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void record(long taskId, Integer versionNo, String type,
                       Map<String, Object> result, String operator) {
        if (versionNo == null) return;
        SqlTaskVersionVO version = versionService.get(taskId, versionNo);
        Map<String, Object> safeResult = result == null ? Collections.emptyMap() : result;
        SqlTaskVersionCheck check = new SqlTaskVersionCheck();
        check.setTaskId(taskId);
        check.setVersionNo(versionNo);
        check.setVersionRevision(version.getRevision());
        check.setVersionChecksum(version.getSqlChecksum());
        check.setCheckType(type);
        check.setComplete(booleanValue(safeResult.get("complete"), true));
        check.setPassed(resolvePassed(type, safeResult));
        check.setStatus(resolveStatus(type, safeResult, check.getPassed(), check.getComplete()));
        check.setErrorCount(resolveCount(type, safeResult, "error"));
        check.setWarningCount(resolveCount(type, safeResult, "warning"));
        check.setDurationMs(longValue(safeResult.get("compilationMs")));
        check.setResultSummary(resolveSummary(type, safeResult, check));
        check.setResultPayload(writeJson(safeResult));
        check.setCheckedBy(operator);
        check.setCheckedAt(LocalDateTime.now());
        mapper.upsert(check);
    }

    @Override
    public TaskVersionCheckSummaryVO summary(long taskId, int versionNo) {
        SqlTaskVersionVO version = versionService.get(taskId, versionNo);
        TaskVersionCheckSummaryVO summary = new TaskVersionCheckSummaryVO();
        summary.setTaskId(taskId);
        summary.setVersionNo(versionNo);
        for (SqlTaskVersionCheck row : mapper.listLatest(taskId, versionNo)) {
            if (!java.util.Objects.equals(version.getRevision(), row.getVersionRevision())
                    || !java.util.Objects.equals(version.getSqlChecksum(), row.getVersionChecksum())) continue;
            summary.getChecks().put(row.getCheckType(), toItem(row));
        }
        String compareStatus = mapper.selectLatestCompareStatus(taskId, versionNo, version.getSqlChecksum());
        if (compareStatus != null) summary.getChecks().put("COMPARE", compareItem(compareStatus));

        boolean requiredComplete = REQUIRED_CHECKS.stream().allMatch(summary.getChecks()::containsKey);
        boolean requiredPassed = requiredComplete && REQUIRED_CHECKS.stream()
                .allMatch(type -> Boolean.TRUE.equals(summary.getChecks().get(type).getPassed()));
        summary.setAllPassed(requiredPassed);
        boolean editable = Boolean.TRUE.equals(version.getCanActivate());
        summary.setReadyToActivate(editable && requiredPassed);
        if (!editable) summary.setBlockingReason("版本不是基于最新生效代码的可发布草稿");
        else if (!requiredComplete) summary.setBlockingReason("请先完成编译校验和质量检查");
        else if (!requiredPassed) summary.setBlockingReason("存在未通过的版本检查");
        return summary;
    }

    private TaskVersionCheckSummaryVO.CheckItemVO toItem(SqlTaskVersionCheck row) {
        TaskVersionCheckSummaryVO.CheckItemVO item = new TaskVersionCheckSummaryVO.CheckItemVO();
        item.setType(row.getCheckType()); item.setStatus(row.getStatus()); item.setPassed(row.getPassed());
        item.setComplete(row.getComplete()); item.setErrorCount(row.getErrorCount());
        item.setWarningCount(row.getWarningCount()); item.setDurationMs(row.getDurationMs());
        item.setSummary(row.getResultSummary()); item.setCheckedBy(row.getCheckedBy());
        item.setCheckedAt(row.getCheckedAt());
        return item;
    }

    private TaskVersionCheckSummaryVO.CheckItemVO compareItem(String status) {
        TaskVersionCheckSummaryVO.CheckItemVO item = new TaskVersionCheckSummaryVO.CheckItemVO();
        item.setType("COMPARE"); item.setStatus(status);
        item.setPassed("PASSED".equals(status) || "FORCE_PASSED".equals(status));
        item.setComplete(!"PENDING".equals(status) && !"RUNNING".equals(status));
        item.setSummary("最近一次版本验数：" + status);
        return item;
    }

    private Boolean resolvePassed(String type, Map<String, Object> result) {
        if ("VALIDATE".equals(type)) return booleanValue(result.get("valid"), false);
        if ("QUALITY".equals(type)) return booleanValue(result.get("passed"), false);
        return booleanValue(result.get("ok"), false);
    }

    private String resolveStatus(String type, Map<String, Object> result, Boolean passed, Boolean complete) {
        Object explicit = result.get("status");
        if (explicit != null) return String.valueOf(explicit);
        if (!Boolean.TRUE.equals(complete)) return "INCOMPLETE";
        if ("EXPLAIN".equals(type)) return Boolean.TRUE.equals(result.get("ok")) ? "PASSED" : "FAILED";
        return Boolean.TRUE.equals(passed) ? "PASSED" : "FAILED";
    }

    @SuppressWarnings("unchecked")
    private int resolveCount(String type, Map<String, Object> result, String level) {
        if ("QUALITY".equals(type) && result.get("summary") instanceof Map) {
            return intValue(((Map<String, Object>) result.get("summary")).get(level));
        }
        if ("VALIDATE".equals(type) && "error".equals(level) && result.get("errors") instanceof List) {
            return ((List<?>) result.get("errors")).size();
        }
        if ("warning".equals(level) && result.get("warnings") instanceof List) {
            return ((List<?>) result.get("warnings")).size();
        }
        return 0;
    }

    private String resolveSummary(String type, Map<String, Object> result, SqlTaskVersionCheck check) {
        if ("VALIDATE".equals(type)) return Boolean.TRUE.equals(check.getPassed())
                ? "编译校验通过" : "编译校验未通过，共 " + check.getErrorCount() + " 个错误";
        if ("QUALITY".equals(type)) return "质量检查：" + check.getStatus() + "，"
                + check.getErrorCount() + " 个错误，" + check.getWarningCount() + " 个警告";
        return Boolean.TRUE.equals(check.getPassed()) ? "执行计划生成成功" : "执行计划生成失败";
    }

    private boolean booleanValue(Object value, boolean fallback) {
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    private int intValue(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private Long longValue(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : null;
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception ignored) { return "{}"; }
    }
}
