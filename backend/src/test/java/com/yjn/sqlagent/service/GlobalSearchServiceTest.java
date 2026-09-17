package com.yjn.sqlagent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class GlobalSearchServiceTest {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void returnsLocalGroupsWhenHiveSearchFails() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AgentProxyService agent = mock(AgentProxyService.class);
        when(jdbc.queryForObject(anyString(), any(Object[].class), eq(Number.class))).thenReturn(0L);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(Collections.emptyList());
        when(agent.searchHiveTables(anyString(), any(), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("hive unavailable"));

        Map<String, Object> result = new GlobalSearchService(jdbc, agent, executor)
                .search("task", null, 1, 5, "tester");

        List<?> groups = (List<?>) result.get("groups");
        assertEquals(8, groups.size());
        assertEquals(List.of("HIVE_TABLE"), result.get("partialFailures"));
    }

    @Test
    void mapsHiveTablesToCatalogRoute() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AgentProxyService agent = mock(AgentProxyService.class);
        when(agent.searchHiveTables("orders", null, 10, 0)).thenReturn(Map.of(
                "total", 1,
                "items", List.of(Map.of("db", "dw", "table", "orders", "tableType", "MANAGED_TABLE"))));

        Map<String, Object> result = new GlobalSearchService(jdbc, agent, executor)
                .search("orders", "HIVE_TABLE", 1, 10, "tester");

        Map<?, ?> group = (Map<?, ?>) ((List<?>) result.get("groups")).get(0);
        Map<?, ?> item = (Map<?, ?>) ((List<?>) group.get("items")).get(0);
        assertEquals("/catalog?db=dw&table=orders", item.get("route"));
        assertTrue(((List<?>) result.get("partialFailures")).isEmpty());
    }
}
