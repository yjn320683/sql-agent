package com.yjn.sqlagent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class OverviewServiceTest {
    @Test
    void returnsEverySectionWhenSourcesAreHealthy() {
        JdbcTemplate jdbc = healthyJdbc();
        Map<String, Object> result = new OverviewService(jdbc).overview("tester");
        Map<?, ?> sections = (Map<?, ?>) result.get("sections");

        assertTrue((Boolean) ((Map<?, ?>) sections.get("offline")).get("available"));
        assertTrue((Boolean) ((Map<?, ?>) sections.get("realtime")).get("available"));
        assertEquals(Collections.emptyList(), result.get("attention"));
        assertEquals(Collections.emptyList(), result.get("recentTasks"));
    }

    @Test
    void degradesOnlyFailedSection() {
        JdbcTemplate jdbc = healthyJdbc();
        when(jdbc.queryForMap(anyString())).thenThrow(new IllegalStateException("offline unavailable"));

        Map<String, Object> result = new OverviewService(jdbc).overview("tester");
        Map<?, ?> sections = (Map<?, ?>) result.get("sections");

        assertFalse((Boolean) ((Map<?, ?>) sections.get("offline")).get("available"));
        assertTrue((Boolean) ((Map<?, ?>) sections.get("realtime")).get("available"));
    }

    @SuppressWarnings("unchecked")
    private JdbcTemplate healthyJdbc() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForMap(anyString())).thenReturn(new LinkedHashMap<>(Map.of(
                "offlineActive", 0L, "offlineFailed24h", 0L)));
        when(jdbc.queryForObject(anyString(), eq(Number.class))).thenReturn(0L);
        when(jdbc.queryForList(anyString())).thenReturn(Collections.emptyList());
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(Collections.emptyList());
        return jdbc;
    }
}
