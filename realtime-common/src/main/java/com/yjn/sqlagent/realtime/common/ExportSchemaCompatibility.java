package com.yjn.sqlagent.realtime.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 出仓保存、调试与启动共用的 Schema 兼容规则。 */
public final class ExportSchemaCompatibility {
    private ExportSchemaCompatibility() { }

    public static void validate(List<Map<String, Object>> sourceColumns,
            List<Map<String, Object>> targetColumns, List<Map<String, Object>> mappings,
            List<String> primaryKeys, String targetTable) {
        Map<String, Map<String, Object>> source = byName(sourceColumns);
        Map<String, Map<String, Object>> target = byName(targetColumns);
        Set<String> mappedTargets = new LinkedHashSet<>();
        for (Map<String, Object> mapping : mappings) {
            String from = text(mapping.get("sourceColumn")).toLowerCase(Locale.ROOT);
            String to = text(mapping.get("targetColumn")).toLowerCase(Locale.ROOT);
            Map<String, Object> sourceColumn = source.get(from);
            Map<String, Object> targetColumn = target.get(to);
            if (sourceColumn == null) throw new IllegalArgumentException("实时表字段不存在：" + from);
            if (targetColumn == null) throw new IllegalArgumentException("MySQL 字段不存在：" + targetTable + "." + to);
            if (!mappedTargets.add(to)) throw new IllegalArgumentException("MySQL 字段重复映射：" + to);
            if (bool(sourceColumn.get("nullable"), true) && !bool(targetColumn.get("nullable"), true)) {
                throw new IllegalArgumentException("可空源字段不能写入非空 MySQL 字段：" + targetTable + "." + to);
            }
            String incompatibility = incompatibility(sourceColumn, targetColumn);
            if (!incompatibility.isEmpty()) {
                throw new IllegalArgumentException("字段类型不兼容：" + from + "(" + sourceType(sourceColumn)
                        + ") → " + targetTable + "." + to + "(" + targetType(targetColumn) + ")；" + incompatibility);
            }
        }
        for (String key : primaryKeys) {
            if (!mappedTargets.contains(text(key).toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("MySQL 主键未完整映射：" + targetTable + "." + key);
            }
        }
    }

    public static String fingerprint(List<Map<String, Object>> columns, List<String> primaryKeys) {
        StringBuilder canonical = new StringBuilder();
        Set<String> keys = new LinkedHashSet<>();
        for (String key : primaryKeys) keys.add(text(key).toLowerCase(Locale.ROOT));
        for (Map<String, Object> column : columns) {
            String name = text(column.get("name")).toLowerCase(Locale.ROOT);
            canonical.append(name).append('|').append(normalizedType(column)).append('|')
                    .append(bool(column.get("nullable"), true)).append('|')
                    .append(number(column.get("characterMaximumLength"))).append('|')
                    .append(number(column.get("numericPrecision"))).append('|')
                    .append(number(column.get("numericScale"))).append('|')
                    .append(number(column.get("datetimePrecision"))).append('|')
                    .append(bool(column.get("unsigned"), false)).append('|')
                    .append(keys.contains(name)).append('\n');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("生成 Schema 指纹失败", ex);
        }
    }

    /** 从实时读取的 Paimon 列定义提取当前主键，避免用历史版本主键生成“当前”指纹。 */
    public static List<String> primaryKeys(List<Map<String, Object>> columns) {
        List<String> result = new ArrayList<>();
        for (Map<String, Object> column : columns) {
            if (bool(column.get("primaryKey"), false)) result.add(text(column.get("name")));
        }
        return result;
    }

    public static Map<String, Object> snapshot(String database, String table,
            List<Map<String, Object>> columns, List<String> primaryKeys) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("database", database); result.put("table", table);
        result.put("columns", new ArrayList<>(columns)); result.put("primaryKeys", new ArrayList<>(primaryKeys));
        result.put("fingerprint", fingerprint(columns, primaryKeys));
        return result;
    }

    private static String incompatibility(Map<String, Object> source, Map<String, Object> target) {
        Type sourceType = Type.parse(sourceType(source));
        Type targetType = Type.parse(targetType(target));
        if (targetType.unsigned && sourceType.numeric && !sourceType.unsigned && !"BOOLEAN".equals(sourceType.base)) {
            return "有符号源字段不能安全写入 unsigned 目标字段";
        }
        if (sourceType.string) {
            if (!targetType.string) return "目标字段不是字符类型";
            Long sourceLength = sourceType.length;
            Long targetLength = firstNumber(target.get("characterMaximumLength"), targetType.length);
            if (sourceLength != null && targetLength != null && targetLength < sourceLength) return "目标字符长度不足";
            return "";
        }
        if (sourceType.binary) return targetType.binary ? "" : "目标字段不是二进制类型";
        if ("BOOLEAN".equals(sourceType.base)) return Set.of("BOOLEAN", "BOOL", "TINYINT", "BIT").contains(targetType.base) ? "" : "目标字段不能保存布尔值";
        if (sourceType.numeric) {
            if (!targetType.numeric) return "目标字段不是数值类型";
            Integer sourceRank = integerRank(sourceType.base), targetRank = integerRank(targetType.base);
            if (sourceRank != null && targetRank != null) return targetRank >= sourceRank ? "" : "目标整数范围小于源字段";
            if (sourceType.decimal) {
                Long sourcePrecision = firstNumber(source.get("numericPrecision"), sourceType.precision);
                Long sourceScale = firstNumber(source.get("numericScale"), sourceType.scale);
                Long targetPrecision = firstNumber(target.get("numericPrecision"), targetType.precision);
                Long targetScale = firstNumber(target.get("numericScale"), targetType.scale);
                if (targetType.decimal && sourcePrecision != null && targetPrecision != null) {
                    long sourceInteger = sourcePrecision - value(sourceScale);
                    long targetInteger = targetPrecision - value(targetScale);
                    if (targetInteger < sourceInteger || value(targetScale) < value(sourceScale)) return "目标 DECIMAL 精度或小数位不足";
                    return "";
                }
            }
            return Set.of("DECIMAL", "NUMERIC", "DOUBLE", "FLOAT", "REAL").contains(targetType.base) ? "" : "目标数值范围不足";
        }
        if ("DATE".equals(sourceType.base)) return Set.of("DATE", "DATETIME", "TIMESTAMP").contains(targetType.base) ? "" : "目标字段不是日期类型";
        if (Set.of("TIMESTAMP", "TIMESTAMP_LTZ", "DATETIME").contains(sourceType.base)) {
            if (!Set.of("DATETIME", "TIMESTAMP").contains(targetType.base)) return "目标字段不是时间戳类型";
            Long sourcePrecision = firstNumber(source.get("datetimePrecision"), sourceType.precision);
            Long targetPrecision = firstNumber(target.get("datetimePrecision"), targetType.precision);
            return sourcePrecision != null && targetPrecision != null && targetPrecision < sourcePrecision ? "目标时间精度不足" : "";
        }
        if ("TIME".equals(sourceType.base)) return "TIME".equals(targetType.base) ? "" : "目标字段不是 TIME 类型";
        return sourceType.base.equals(targetType.base) ? "" : "基础类型不一致";
    }

    private static String sourceType(Map<String, Object> column) {
        String value = text(column.get("dataType"));
        return value.isEmpty() ? text(column.get("type")) : value;
    }
    private static String targetType(Map<String, Object> column) {
        String value = text(column.get("fullType"));
        return value.isEmpty() ? text(column.get("type")) : value;
    }
    private static String normalizedType(Map<String, Object> column) {
        String value = targetType(column);
        if (value.isEmpty()) value = sourceType(column);
        return value.toUpperCase(Locale.ROOT).replace(" NOT NULL", "").replaceAll("\\s+", " ").trim();
    }
    private static Map<String, Map<String, Object>> byName(List<Map<String, Object>> columns) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> column : columns) result.put(text(column.get("name")).toLowerCase(Locale.ROOT), column);
        return result;
    }
    private static Integer integerRank(String type) {
        switch (type) {
            case "TINYINT": return 1; case "SMALLINT": return 2; case "MEDIUMINT": return 3;
            case "INT": case "INTEGER": return 4; case "BIGINT": return 5; default: return null;
        }
    }
    private static Long firstNumber(Object first, Long fallback) { Long value = number(first); return value == null ? fallback : value; }
    private static long value(Long value) { return value == null ? 0L : value; }
    private static Long number(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        try { String text = text(value); return text.isEmpty() ? null : Long.parseLong(text); }
        catch (NumberFormatException ignored) { return null; }
    }
    private static boolean bool(Object value, boolean fallback) {
        if (value instanceof Boolean) return (Boolean) value;
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }

    private static final class Type {
        final String base; final Long length, precision, scale; final boolean string, binary, numeric, decimal, unsigned;
        private Type(String base, Long length, Long precision, Long scale, boolean unsigned) {
            this.base = base; this.length = length; this.precision = precision; this.scale = scale; this.unsigned = unsigned;
            this.string = Set.of("CHAR", "VARCHAR", "STRING", "TEXT", "TINYTEXT", "MEDIUMTEXT", "LONGTEXT", "JSON").contains(base);
            this.binary = Set.of("BINARY", "VARBINARY", "BYTES", "BLOB", "TINYBLOB", "MEDIUMBLOB", "LONGBLOB").contains(base);
            this.numeric = Set.of("TINYINT", "SMALLINT", "MEDIUMINT", "INT", "INTEGER", "BIGINT", "FLOAT", "REAL", "DOUBLE", "DECIMAL", "NUMERIC").contains(base);
            this.decimal = Set.of("DECIMAL", "NUMERIC").contains(base);
        }
        static Type parse(String raw) {
            String value = text(raw).toUpperCase(Locale.ROOT).replace(" NOT NULL", "").trim();
            boolean unsigned = value.contains(" UNSIGNED"); value = value.replace(" UNSIGNED", "").trim();
            int open = value.indexOf('('), close = value.indexOf(')', open + 1);
            String base = (open < 0 ? value : value.substring(0, open)).trim().replace(" WITH LOCAL TIME ZONE", "_LTZ");
            Long first = null, second = null;
            if (open >= 0 && close > open) {
                String[] parts = value.substring(open + 1, close).split(",");
                first = number(parts[0]); if (parts.length > 1) second = number(parts[1]);
            }
            boolean decimal = Set.of("DECIMAL", "NUMERIC").contains(base);
            boolean lengthType = Set.of("CHAR", "VARCHAR", "BINARY", "VARBINARY").contains(base);
            boolean precisionType = decimal || Set.of("TIME", "TIMESTAMP", "TIMESTAMP_LTZ", "DATETIME").contains(base);
            return new Type(base, lengthType ? first : null, precisionType ? first : null, decimal ? second : null, unsigned);
        }
    }
}
