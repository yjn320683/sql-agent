package com.yjn.sqlagent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjn.sqlagent.common.ErrorCode;
import com.yjn.sqlagent.exception.BusinessException;
import com.yjn.sqlagent.model.dto.AiContextDTO;
import com.yjn.sqlagent.model.dto.ChatRequestDTO;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 规范化、校验并脱敏页面内 AI 上下文。 */
@Service
public class AiContextService {

    private static final Set<String> TYPES = Set.of(
            "OFFLINE_TASK", "OFFLINE_VERSION", "OFFLINE_EXECUTION", "OFFLINE_SCHEDULE",
            "DATA_COMPARE", "CATALOG_TABLE", "REALTIME_SYNC_TASK", "REALTIME_INSTANCE",
            "REALTIME_COMPUTE_TASK", "REALTIME_EXPORT_TASK", "REALTIME_TABLE",
            "REALTIME_SERVER", "REALTIME_ALERT", "PLATFORM_STATUS");
    private static final Set<String> INTENTS = Set.of(
            "GENERATE", "OPTIMIZE", "FIX", "EXPLAIN", "REVIEW", "DIAGNOSE",
            "RECOMMEND", "COMPARE", "SEARCH", "SUMMARIZE");
    private static final int MAX_DRAFT_JSON_LENGTH = 200_000;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AiContextService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public AiContextDTO normalizeAndValidate(ChatRequestDTO request) {
        AiContextDTO context = request.getContext();
        if (context == null) {
            context = new AiContextDTO();
            context.setContextType(request.getExecutionId() == null ? "OFFLINE_TASK" : "OFFLINE_EXECUTION");
            context.setEntityId(String.valueOf(request.getExecutionId() == null
                    ? request.getTaskId() : request.getExecutionId()));
            context.setParentId(request.getExecutionId() == null ? null : String.valueOf(request.getTaskId()));
            context.setVersionNo(request.getVersionNo());
        }
        String type = normalize(context.getContextType());
        if (!TYPES.contains(type)) {
            throw badRequest("不支持的 AI contextType：" + context.getContextType());
        }
        context.setContextType(type);
        context.setEntityId(trim(context.getEntityId()));
        context.setParentId(trim(context.getParentId()));
        context.setTitle(trim(context.getTitle()));
        context.setDraft(sanitizeMap(context.getDraft()));
        validateDraftSize(context.getDraft());
        validateIntent(request);
        validateLegacyConsistency(request, context);
        validateEntity(context);
        return context;
    }

    private void validateIntent(ChatRequestDTO request) {
        if (request.getIntent() == null || request.getIntent().trim().isEmpty()) return;
        String intent = normalize(request.getIntent());
        if (!INTENTS.contains(intent)) throw badRequest("不支持的 AI intent：" + request.getIntent());
        request.setIntent(intent);
    }

    private void validateLegacyConsistency(ChatRequestDTO request, AiContextDTO context) {
        if (request.getTaskId() == null || context.getParentId() == null) return;
        if (("OFFLINE_EXECUTION".equals(context.getContextType())
                || "OFFLINE_VERSION".equals(context.getContextType()))
                && !String.valueOf(request.getTaskId()).equals(context.getParentId())) {
            throw badRequest("AI 上下文与 taskId 不一致");
        }
    }

    private void validateEntity(AiContextDTO context) {
        String id = context.getEntityId();
        if (id == null || id.isEmpty()) return;
        String table;
        String column = "id";
        switch (context.getContextType()) {
            case "OFFLINE_TASK":
            case "OFFLINE_SCHEDULE": table = "sql_task"; break;
            case "OFFLINE_VERSION":
                validateOfflineVersion(context, id);
                return;
            case "OFFLINE_EXECUTION": table = "sql_task_execution"; break;
            case "DATA_COMPARE":
                table = "data_compare_job_detail";
                break;
            case "REALTIME_SYNC_TASK":
            case "REALTIME_COMPUTE_TASK":
            case "REALTIME_EXPORT_TASK": table = "rt_task"; break;
            case "REALTIME_INSTANCE": table = "rt_task_instance"; break;
            case "REALTIME_TABLE": table = "rt_realtime_table"; break;
            case "REALTIME_SERVER": table = "rt_server"; break;
            case "REALTIME_ALERT": table = "rt_alert"; break;
            default: return;
        }
        long numericId;
        try {
            numericId = Long.parseLong(id);
        } catch (NumberFormatException error) {
            throw badRequest("AI 上下文实体 ID 必须为数字");
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + "=?", Integer.class, numericId);
        if (count == null || count == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "AI 上下文实体不存在");
        }
    }

    private void validateOfflineVersion(AiContextDTO context, String entityId) {
        String taskId = context.getParentId();
        Integer versionNo = context.getVersionNo();
        if (taskId == null || versionNo == null || !entityId.equals(String.valueOf(versionNo))) {
            throw badRequest("离线版本 AI 上下文必须包含一致的 parentId、entityId 和 versionNo");
        }
        long numericTaskId;
        try {
            numericTaskId = Long.parseLong(taskId);
        } catch (NumberFormatException error) {
            throw badRequest("AI 上下文实体 ID 必须为数字");
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sql_task_version WHERE task_id=? AND version_no=?",
                Integer.class, numericTaskId, versionNo);
        if (count == null || count == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "AI 上下文实体不存在");
        }
    }

    private Map<String, Object> sanitizeMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) return source;
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (isSensitiveKey(entry.getKey())) continue;
            result.put(entry.getKey(), sanitizeValue(entry.getValue()));
        }
        return result;
    }

    private Object sanitizeValue(Object value) {
        if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) value;
            return sanitizeMap(map);
        }
        if (value instanceof List) {
            List<?> values = (List<?>) value;
            List<Object> result = new ArrayList<>();
            for (Object item : values) result.add(sanitizeValue(item));
            return result;
        }
        if (value instanceof String) {
            String text = (String) value;
            return text.replaceAll("(?i)(bearer\\s+)[a-z0-9._~+/-]+=*", "$1[REDACTED]")
                    .replaceAll("(?i)([a-z][a-z0-9+.-]*://)[^/@\\s:]+:[^/@\\s]+@", "$1[REDACTED]@");
        }
        return value;
    }

    private boolean isSensitiveKey(String key) {
        String value = key == null ? "" : key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return value.contains("password") || value.contains("token") || value.contains("secret")
                || value.contains("credential") || value.contains("connectionstring")
                || value.contains("jdbcurl") || value.equals("uri") || value.equals("url");
    }

    private void validateDraftSize(Map<String, Object> draft) {
        if (draft == null) return;
        try {
            if (objectMapper.writeValueAsString(draft).length() > MAX_DRAFT_JSON_LENGTH) {
                throw badRequest("AI draft 不能超过 200000 个字符");
            }
        } catch (JsonProcessingException error) {
            throw badRequest("AI draft 不是有效 JSON");
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String trim(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST.getCode(), message);
    }
}
