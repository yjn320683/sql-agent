package com.yjn.sqlagent.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class PlatformHealthService {
    private final AgentProxyService agentProxyService;
    private final SchemaMigrationStatusService schemaMigrationStatusService;

    public PlatformHealthService(AgentProxyService agentProxyService,
                                 SchemaMigrationStatusService schemaMigrationStatusService) {
        this.agentProxyService = agentProxyService;
        this.schemaMigrationStatusService = schemaMigrationStatusService;
    }

    public Map<String, Object> health() {
        List<Map<String, Object>> dependencies = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> missingReasons = new ArrayList<>();
        boolean nestedComplete;

        long agentStarted = System.nanoTime();
        try {
            Map<String, Object> agentHealth = agentProxyService.getPlatformHealth();
            dependencies.add(dependency("agent", true, true, elapsedMs(agentStarted), null, null));
            Object nested = agentHealth == null ? null : agentHealth.get("dependencies");
            if (nested instanceof List<?>) {
                for (Object item : (List<?>) nested) {
                    if (item instanceof Map<?, ?>) dependencies.add(copyDependency((Map<?, ?>) item));
                }
            }
            appendStrings(warnings, agentHealth == null ? null : agentHealth.get("warnings"));
            appendStrings(missingReasons, agentHealth == null ? null : agentHealth.get("missingReasons"));
            nestedComplete = agentHealth != null && !Boolean.FALSE.equals(agentHealth.get("complete"));
        } catch (Exception error) {
            nestedComplete = false;
            dependencies.add(dependency("agent", true, false, elapsedMs(agentStarted),
                    "agent_unavailable", "SQL Agent 服务暂不可用"));
            missingReasons.add("agent_unavailable");
        }

        Map<String, Object> schema = schemaMigrationStatusService.status();
        boolean schemaCompatible = Boolean.TRUE.equals(schema.get("compatible"));
        dependencies.add(dependency("databaseSchema", true, schemaCompatible, 0,
                schemaCompatible ? null : "database_schema_incompatible", String.valueOf(schema.get("message")), schema));
        if (!schemaCompatible) missingReasons.add("database_schema_incompatible");

        boolean complete = nestedComplete && !dependencies.isEmpty()
                && dependencies.stream().allMatch(item -> Boolean.TRUE.equals(item.get("reachable")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("source", "sql-agent-backend");
        result.put("fetchedAt", Instant.now().toString());
        result.put("warnings", warnings);
        result.put("complete", complete);
        result.put("missingReasons", missingReasons);
        result.put("dependencies", dependencies);
        return result;
    }

    private Map<String, Object> copyDependency(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() != null) copy.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return copy;
    }

    private Map<String, Object> dependency(String name, boolean configured, boolean reachable,
                                           long latencyMs, String errorCode, String message) {
        return dependency(name, configured, reachable, latencyMs, errorCode, message, Collections.emptyMap());
    }

    private Map<String, Object> dependency(String name, boolean configured, boolean reachable,
                                           long latencyMs, String errorCode, String message,
                                           Map<String, Object> details) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("configured", configured);
        item.put("reachable", reachable);
        item.put("latencyMs", latencyMs);
        if (errorCode != null) item.put("errorCode", errorCode);
        if (message != null) item.put("message", message);
        item.put("details", details == null ? Collections.emptyMap() : details);
        return item;
    }

    private long elapsedMs(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    private void appendStrings(List<String> target, Object values) {
        if (!(values instanceof List<?>)) return;
        for (Object value : (List<?>) values) {
            if (value != null) target.add(String.valueOf(value));
        }
    }
}
