package com.yjn.sqlagent.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** HiveServer2 函数事实的有界短缓存和统一展示模型。 */
@Service
public class HiveFunctionCatalogService {
    private static final int MAX_ENTRIES = 512;
    private final AgentProxyService agent;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public HiveFunctionCatalogService(AgentProxyService agent) { this.agent = agent; }

    public Map<String, Object> search(String keyword, int limit, int offset, String defaultDb) {
        String key = "search|" + normalize(defaultDb) + '|' + normalize(keyword) + '|' + limit + '|' + offset;
        return cached(key, Duration.ofMinutes(5), () -> agent.searchHiveFunctions(keyword, limit, offset, defaultDb));
    }

    public Map<String, Object> detail(String name, String defaultDb) {
        String key = "detail|" + normalize(defaultDb) + '|' + normalize(name);
        return cached(key, Duration.ofMinutes(10), () -> normalizeDetail(agent.getHiveFunction(name, defaultDb)));
    }

    private Map<String, Object> normalizeDetail(Map<String, Object> raw) {
        Map<String, Object> result = new LinkedHashMap<>(raw);
        Map<?, ?> properties = raw.get("properties") instanceof Map<?, ?> ? (Map<?, ?>) raw.get("properties") : Map.of();
        String usage = text(properties.get("usage"));
        String type = text(properties.get("type"));
        result.put("functionType", type.isEmpty() ? "未提供" : type);
        result.put("signatures", usage.isEmpty() ? List.of() : List.of(usage));
        result.put("arguments", parseArguments(usage));
        result.put("returnType", firstNonBlank(text(properties.get("return type")), text(properties.get("return_type")), "未提供"));
        result.put("invocationTemplate", invocationTemplate(text(raw.get("name")), usage));
        result.put("examples", examples(properties));
        return result;
    }

    private List<Map<String, String>> parseArguments(String usage) {
        int left = usage.indexOf('('), right = usage.indexOf(')', left + 1);
        if (left < 0 || right < 0) return List.of();
        List<Map<String, String>> arguments = new ArrayList<>();
        for (String value : usage.substring(left + 1, right).split(",")) {
            String name = value.trim();
            if (!name.isEmpty()) arguments.add(Map.of("name", name, "type", "未提供", "description", "未提供"));
        }
        return arguments;
    }

    private String invocationTemplate(String name, String usage) {
        if (!usage.isEmpty()) {
            int separator = usage.indexOf(" - ");
            return separator > 0 ? usage.substring(0, separator).trim() : usage.trim();
        }
        return name.isEmpty() ? "" : name + "()";
    }

    private List<String> examples(Map<?, ?> properties) {
        String example = firstNonBlank(text(properties.get("example")), text(properties.get("examples")), "");
        return example.isEmpty() ? List.of() : List.of(example);
    }

    private Map<String, Object> cached(String key, Duration ttl, Supplier supplier) {
        long now = System.currentTimeMillis();
        CacheEntry current = cache.get(key);
        if (current != null && current.expiresAt > now) return withCache(current.value, true);
        Map<String, Object> loaded = supplier.get();
        if (cache.size() >= MAX_ENTRIES) cache.entrySet().stream().min(Map.Entry.comparingByValue())
                .ifPresent(entry -> cache.remove(entry.getKey(), entry.getValue()));
        cache.put(key, new CacheEntry(new LinkedHashMap<>(loaded), now + ttl.toMillis(), now));
        return withCache(loaded, false);
    }

    private Map<String, Object> withCache(Map<String, Object> value, boolean hit) {
        Map<String, Object> result = new LinkedHashMap<>(value);
        result.put("cacheHit", hit);
        return result;
    }

    private String normalize(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private String firstNonBlank(String... values) { for (String value : values) if (!value.isEmpty()) return value; return ""; }

    private interface Supplier { Map<String, Object> get(); }
    private static final class CacheEntry implements Comparable<CacheEntry> {
        private final Map<String, Object> value; private final long expiresAt; private final long createdAt;
        private CacheEntry(Map<String, Object> value, long expiresAt, long createdAt) { this.value = value; this.expiresAt = expiresAt; this.createdAt = createdAt; }
        @Override public int compareTo(CacheEntry other) { return Long.compare(createdAt, other.createdAt); }
    }
}
