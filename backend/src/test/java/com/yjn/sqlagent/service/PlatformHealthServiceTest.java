package com.yjn.sqlagent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PlatformHealthServiceTest {
    @Mock
    private AgentProxyService agentProxyService;
    @Mock
    private SchemaMigrationStatusService schemaMigrationStatusService;

    @Test
    void reportsAgentUnavailableWithoutASeparateDataCompareDependency() {
        when(agentProxyService.getPlatformHealth()).thenThrow(new IllegalStateException("offline"));

        Map<String, Object> result = service().health();

        List<Map<String, Object>> dependencies = dependencies(result);
        assertFalse((Boolean) result.get("complete"));
        assertEquals(2, dependencies.size());
        assertFalse((Boolean) dependencies.get(0).get("reachable"));
        assertEquals("agent", dependencies.get(0).get("name"));
    }

    @Test
    void preservesHadoopResultsReturnedByAgent() {
        Map<String, Object> hive = new LinkedHashMap<>();
        hive.put("name", "hiveServer2");
        hive.put("configured", true);
        hive.put("reachable", true);
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("dependencies", Arrays.asList(hive));
        agent.put("warnings", new ArrayList<String>());
        agent.put("missingReasons", new ArrayList<String>());
        when(agentProxyService.getPlatformHealth()).thenReturn(agent);

        Map<String, Object> result = service().health();

        List<Map<String, Object>> dependencies = dependencies(result);
        assertTrue((Boolean) result.get("complete"));
        assertEquals(3, dependencies.size());
        assertTrue((Boolean) dependencies.get(0).get("reachable"));
        assertEquals("hiveServer2", dependencies.get(1).get("name"));
    }

    @Test
    void keepsAgentIncompleteFlagEvenWhenReturnedRowsAreReachable() {
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("complete", false);
        agent.put("dependencies", new ArrayList<Map<String, Object>>());
        agent.put("missingReasons", Arrays.asList("hiveServer2_unavailable"));
        when(agentProxyService.getPlatformHealth()).thenReturn(agent);

        Map<String, Object> result = service().health();

        assertFalse((Boolean) result.get("complete"));
        assertTrue(((List<?>) result.get("missingReasons")).contains("hiveServer2_unavailable"));
    }

    private PlatformHealthService service() {
        when(schemaMigrationStatusService.status()).thenReturn(Map.of(
                "compatible", true,
                "currentVersion", SchemaMigrationStatusService.REQUIRED_VERSION,
                "requiredVersion", SchemaMigrationStatusService.REQUIRED_VERSION,
                "pendingMigrations", 0,
                "tracked", true,
                "message", "数据库结构版本兼容"));
        return new PlatformHealthService(agentProxyService, schemaMigrationStatusService);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> dependencies(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("dependencies");
    }

}
