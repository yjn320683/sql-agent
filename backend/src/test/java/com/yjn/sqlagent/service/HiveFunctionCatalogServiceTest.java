package com.yjn.sqlagent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HiveFunctionCatalogServiceTest {
    @Test
    void cachesAndNormalizesRealHiveFactsWithoutInventingTypes() {
        AgentProxyService agent = mock(AgentProxyService.class);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("name", "date_add");
        detail.put("properties", Map.of("usage", "date_add(start_date, num_days) - Returns date"));
        when(agent.getHiveFunction("date_add", "dw")).thenReturn(detail);
        HiveFunctionCatalogService service = new HiveFunctionCatalogService(agent);

        Map<String, Object> first = service.detail("date_add", "dw");
        Map<String, Object> second = service.detail("date_add", "dw");

        assertEquals("未提供", first.get("functionType"));
        assertEquals("date_add(start_date, num_days)", first.get("invocationTemplate"));
        assertEquals(2, ((List<?>) first.get("arguments")).size());
        assertTrue((Boolean) second.get("cacheHit"));
        verify(agent, times(1)).getHiveFunction("date_add", "dw");
    }
}
