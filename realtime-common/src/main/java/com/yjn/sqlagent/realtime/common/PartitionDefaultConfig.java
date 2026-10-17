package com.yjn.sqlagent.realtime.common;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 默认分区配置契约：保留业务原值，不裁剪空白以外的字符。 */
public final class PartitionDefaultConfig {
    private PartitionDefaultConfig() {}

    public static Map<String, String> values(Object raw, List<String> partitions) {
        if (raw == null) return Map.of();
        if (!(raw instanceof Map<?, ?>)) throw new IllegalArgumentException("默认分区配置必须是对象");
        Map<?, ?> config = (Map<?, ?>) raw;
        if (!config.keySet().equals(Set.of("version", "values"))
                || !(config.get("version") instanceof Number)
                || !"1".equals(config.get("version").toString())
                || !(config.get("values") instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("默认分区仅支持 version=1 和 values 字段");
        }
        Map<?, ?> entries = (Map<?, ?>) config.get("values");
        if (partitions.isEmpty() || !entries.keySet().equals(Set.copyOf(partitions))) {
            throw new IllegalArgumentException("默认分区必须且只能覆盖全部分区字段");
        }
        Map<String, String> values = new TreeMap<>();
        entries.forEach((key, value) -> {
            if (!(key instanceof String) || !(value instanceof String)
                    || ((String) value).codePoints().allMatch(
                            c -> Character.isWhitespace(c) || Character.isSpaceChar(c))) {
                throw new IllegalArgumentException("默认分区值必须是非空字符串：" + key);
            }
            values.put((String) key, (String) value);
        });
        return values;
    }

    public static void validateOptions(Map<String, ?> options) {
        if (options == null) return;
        Object engine = options.get("merge-engine");
        if (engine != null && !"deduplicate".equals(engine.toString())) {
            throw new IllegalArgumentException("默认分区仅支持 deduplicate 合并引擎");
        }
        for (String key : List.of("ignore-delete", "deduplicate.ignore-delete",
                "first-row.ignore-delete", "partial-update.ignore-delete")) {
            if (Boolean.parseBoolean(String.valueOf(options.get(key)))) {
                throw new IllegalArgumentException("默认分区不允许忽略 DELETE：" + key);
            }
        }
        for (String key : List.of("rowkind.field", "rowkind.filter")) {
            if (options.containsKey(key)) throw new IllegalArgumentException("默认分区不支持：" + key);
        }
    }

    public static String argument(String table, Map<String, String> values) {
        StringBuilder json = new StringBuilder("{\"table\":").append(quote(table))
                .append(",\"version\":1,\"values\":{");
        boolean first = true;
        for (Map.Entry<String, String> entry : new TreeMap<>(values).entrySet()) {
            if (!first) json.append(',');
            first = false;
            json.append(quote(entry.getKey())).append(':').append(quote(entry.getValue()));
        }
        return json.append("}}").toString();
    }

    static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char ch : value.toCharArray()) {
            if (ch == '\\' || ch == '"') out.append('\\').append(ch);
            else if (ch < 32) out.append(String.format("\\u%04x", (int) ch));
            else out.append(ch);
        }
        return out.append('"').toString();
    }
}
