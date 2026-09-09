package com.yjn.sqlagent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjn.sqlagent.exception.BusinessException;
import com.yjn.sqlagent.model.dto.AiContextDTO;
import com.yjn.sqlagent.model.dto.ChatRequestDTO;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class AiContextServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;
    private AiContextService service;

    @BeforeEach
    void setUp() {
        service = new AiContextService(jdbcTemplate, new ObjectMapper());
    }

    @Test
    void normalizesIntentAndRecursivelyRemovesSensitiveDraftFields() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("password", "real-secret");
        nested.put("sql", "select 1");
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("token", "real-token");
        draft.put("config", nested);
        draft.put("headers", List.of("Bearer actual.token.value"));
        ChatRequestDTO request = request(" realtime_table ", null, null, draft);
        request.setIntent(" optimize ");

        AiContextDTO result = service.normalizeAndValidate(request);

        assertEquals("REALTIME_TABLE", result.getContextType());
        assertEquals("OPTIMIZE", request.getIntent());
        assertFalse(result.getDraft().containsKey("token"));
        @SuppressWarnings("unchecked")
        Map<String, Object> sanitized = (Map<String, Object>) result.getDraft().get("config");
        assertFalse(sanitized.containsKey("password"));
        assertEquals("select 1", sanitized.get("sql"));
        assertEquals("Bearer [REDACTED]", ((List<?>) result.getDraft().get("headers")).get(0));
    }

    @Test
    void legacyRequestBecomesOfflineTaskContext() {
        when(jdbcTemplate.queryForObject(
                eq("SELECT COUNT(*) FROM sql_task WHERE id=?"), eq(Integer.class), any(Long.class)))
                .thenReturn(1);
        ChatRequestDTO request = new ChatRequestDTO();
        request.setTaskId(42L);

        AiContextDTO result = service.normalizeAndValidate(request);

        assertEquals("OFFLINE_TASK", result.getContextType());
        assertEquals("42", result.getEntityId());
    }

    @Test
    void offlineVersionMustMatchTaskVersionAndParent() {
        ChatRequestDTO request = request("OFFLINE_VERSION", "4", "12", Map.of());
        request.getContext().setVersionNo(3);

        assertThrows(BusinessException.class, () -> service.normalizeAndValidate(request));
    }

    @Test
    void dataCompareContextUsesNumericJobId() {
        when(jdbcTemplate.queryForObject(
                eq("SELECT COUNT(*) FROM data_compare_job_detail WHERE id=?"), eq(Integer.class), any(Long.class)))
                .thenReturn(1);
        ChatRequestDTO request = request("DATA_COMPARE", "12", null, Map.of());

        AiContextDTO result = service.normalizeAndValidate(request);

        assertEquals("12", result.getEntityId());
    }

    @Test
    void rejectsUnknownContextType() {
        ChatRequestDTO request = request("UNSUPPORTED", null, null, Map.of());

        assertThrows(BusinessException.class, () -> service.normalizeAndValidate(request));
    }

    private ChatRequestDTO request(String type, String entityId, String parentId, Map<String, Object> draft) {
        AiContextDTO context = new AiContextDTO();
        context.setContextType(type);
        context.setEntityId(entityId);
        context.setParentId(parentId);
        context.setDraft(draft);
        ChatRequestDTO request = new ChatRequestDTO();
        request.setContext(context);
        return request;
    }
}
