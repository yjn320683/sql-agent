package com.yjn.sqlagent.realtime.common;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 按源表时间字段严格大于起始值过滤，保留微秒精度。 */
public final class TimeFilterConfig {
    public static final String OPTION = "--multiple_table_time_filter";
    private TimeFilterConfig() {}

    public static Map<String, Object> parse(Object raw) {
        if (raw == null) return Map.of();
        if (!(raw instanceof Map<?, ?>)) throw new IllegalArgumentException("时间过滤必须是对象");
        Map<?, ?> config = (Map<?, ?>) raw;
        if (!config.keySet().equals(Set.of("version", "field", "sourceType", "start", "timeZone"))
                || !(config.get("version") instanceof Number)
                || !"1".equals(config.get("version").toString())) {
            throw new IllegalArgumentException("时间过滤仅支持 version=1、field、sourceType、start、timeZone");
        }
        for (String key : Set.of("field", "sourceType", "start", "timeZone")) {
            if (!(config.get(key) instanceof String) || ((String) config.get(key)).isBlank()) {
                throw new IllegalArgumentException("时间过滤缺少 " + key);
            }
        }
        String type = (String) config.get("sourceType");
        String start = (String) config.get("start");
        ZoneId zone = ZoneId.of((String) config.get("timeZone"));
        if (type.equals("date")) {
            if (!start.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                throw new IllegalArgumentException("过滤开始时间应为 yyyy-MM-dd");
            }
            if (LocalDate.parse(start).getYear() < 1000) {
                throw new IllegalArgumentException("过滤年份必须为 1000 到 9999");
            }
        } else {
            if (!type.matches("(datetime|timestamp)(\\([0-6]\\))?")) {
                throw new IllegalArgumentException("时间过滤仅支持 DATE、DATETIME、TIMESTAMP");
            }
            int precision = type.contains("(") ? type.charAt(type.indexOf('(') + 1) - '0' : 0;
            if (!start.matches("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,6})?")) {
                throw new IllegalArgumentException("过滤开始时间格式错误");
            }
            if (start.contains(".") && start.length() - start.indexOf('.') - 1 > precision) {
                throw new IllegalArgumentException("过滤开始时间精度超过源字段精度");
            }
            LocalDateTime value = LocalDateTime.parse(start.replace(' ', 'T'));
            if (value.getYear() < 1000) throw new IllegalArgumentException("过滤年份必须为 1000 到 9999");
            if (type.startsWith("timestamp") && zone.getRules().getValidOffsets(value).size() != 1) {
                throw new IllegalArgumentException("过滤开始时间在所选时区不存在或存在歧义");
            }
        }
        return Map.of("version", 1, "field", config.get("field"), "sourceType", type,
                "start", start, "timeZone", config.get("timeZone"));
    }

    public static String sourceType(String type) {
        return type == null ? "" : type.trim().toLowerCase(Locale.ROOT).replace("(0)", "");
    }

    public static String argument(String table, Map<String, Object> config) {
        StringBuilder json = new StringBuilder("{\"table\":").append(PartitionDefaultConfig.quote(table))
                .append(",\"version\":1");
        for (String key : new String[] {"field", "sourceType", "start", "timeZone"}) {
            json.append(',').append(PartitionDefaultConfig.quote(key)).append(':')
                    .append(PartitionDefaultConfig.quote((String) config.get(key)));
        }
        return json.append('}').toString();
    }
}
