package com.yjn.sqlagent.realtime.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.paimon.types.DataTypeJsonParser;
import org.apache.paimon.utils.TypeUtils;

/** 结构化与高级计算列共用的保存校验，遵循 Paimon 1.4.2 参数语义。 */
final class ComputedColumnConfigValidation {
    private static final Set<String> TEMPORAL = Set.of("year", "month", "day", "hour", "minute", "second");
    private static final Set<String> FUNCTIONS = Set.of("year", "month", "day", "hour", "minute", "second",
            "date_format", "substring", "truncate", "cast", "now", "upper", "lower", "trim");
    private static final Set<String> INTEGERS = Set.of("TINYINT", "SMALLINT", "INT", "BIGINT");

    private ComputedColumnConfigValidation() {}

    static void validate(String function, List<String> args, String referenceType) {
        if (!FUNCTIONS.contains(function)) throw new IllegalArgumentException("不支持计算列函数：" + function);
        if ("cast".equals(function)) {
            count(function, args, 1, 2);
            if (args.get(0).isBlank() || args.get(0).matches(".*[(),].*")) {
                throw new IllegalArgumentException("cast 需要常量值，不能包含逗号或括号");
            }
            // 使用目标类型的实际 CDC 转换规则，避免常量保存后才在 Sink 中报错。
            String type = args.size() == 2 ? args.get(1) : "STRING";
            if (!Set.of("STRING", "BOOLEAN", "TINYINT", "SMALLINT", "INT", "BIGINT",
                    "FLOAT", "DOUBLE", "DECIMAL", "DATE", "TIME", "TIMESTAMP", "TIMESTAMP_LTZ").contains(type)) {
                throw new IllegalArgumentException("cast 不支持目标数据类型：" + type);
            }
            try {
                if (TypeUtils.castFromCdcValueString(args.get(0), DataTypeJsonParser.parseAtomicTypeSQLString(type)) == null) {
                    throw new IllegalArgumentException("转换结果为空");
                }
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException("cast 常量不能转换为 " + type + "：" + args.get(0));
            }
            return;
        }
        if ("now".equals(function)) {
            count(function, args, 0, 0);
            return;
        }
        if (TEMPORAL.contains(function) || "date_format".equals(function)) {
            boolean format = "date_format".equals(function);
            int required = format ? 2 : 1;
            count(function, args, required, required + 1);
            String base = base(referenceType);
            if (!INTEGERS.contains(base) && !Set.of("DATE", "DATETIME", "TIMESTAMP", "TIMESTAMP_LTZ", "STRING", "VARCHAR").contains(base)) {
                incompatible(function, referenceType);
            }
            if (args.size() > required) {
                if (!Set.of("0", "3", "6", "9").contains(args.get(required))) {
                    throw new IllegalArgumentException(function + " 精度只支持 0、3、6、9");
                }
                if (!INTEGERS.contains(base) && !Set.of("STRING", "VARCHAR").contains(base)) {
                    throw new IllegalArgumentException(function + " 精度仅适用于整数时间戳或数字字符串");
                }
            }
            if (format) {
                if (args.get(1).contains("/")) throw new IllegalArgumentException("date_format 日期格式不能包含 /，请使用 - 或无分隔符格式");
                if (args.get(1).contains(",")) throw new IllegalArgumentException("date_format 格式参数不能包含逗号");
                try {
                    // Paimon 对 LocalDateTime 格式化；需同时拒绝无法取得时区等字段的格式。
                    DateTimeFormatter.ofPattern(args.get(1)).format(LocalDateTime.of(2000, 2, 29, 12, 34, 56, 123456789));
                } catch (RuntimeException ex) {
                    throw new IllegalArgumentException("date_format 日期格式无效：" + args.get(1));
                }
            }
            return;
        }
        if ("substring".equals(function)) {
            count(function, args, 2, 3);
            int begin = integer(args.get(1), "substring 开始位置");
            if (begin < 0) throw new IllegalArgumentException("substring 开始位置必须是非负整数");
            if (args.size() == 3 && integer(args.get(2), "substring 结束位置") <= begin) {
                throw new IllegalArgumentException("substring 结束位置必须大于开始位置");
            }
            // substring 接收序列化字符串，不能简单限制为字符串源字段。
            return;
        }
        if ("truncate".equals(function)) {
            count(function, args, 2, 2);
            if (integer(args.get(1), "truncate 宽度") <= 0) throw new IllegalArgumentException("truncate 宽度必须是正整数");
            if (!INTEGERS.contains(base(referenceType)) && !Set.of("DECIMAL", "CHAR", "VARCHAR", "STRING").contains(base(referenceType))) {
                incompatible(function, referenceType);
            }
            return;
        }
        count(function, args, 1, 1);
        if (!Set.of("STRING", "VARCHAR").contains(base(referenceType))) incompatible(function, referenceType);
    }

    static String sourceType(String raw, List<String> mappings) {
        String mapped = PartitionFieldTypes.source(raw, mappings);
        if (mappings.contains("to-string")) return mapped;
        String type = raw == null ? "" : raw.toUpperCase(Locale.ROOT).trim();
        Matcher decimal = Pattern.compile("(?:DECIMAL|NUMERIC|FIXED)\\s*\\((\\d+)(?:\\s*,\\s*(\\d+))?\\).*").matcher(type);
        if (decimal.matches()) return Integer.parseInt(decimal.group(1)) > 38 ? "STRING" : "DECIMAL";
        if (Set.of("NUMERIC", "FIXED", "SERIAL").contains(base(mapped))) return "DECIMAL";
        if (Set.of("GEOMETRY", "POINT", "LINESTRING", "POLYGON", "MULTIPOINT", "MULTILINESTRING", "MULTIPOLYGON", "GEOMETRYCOLLECTION").contains(base(mapped))) return "STRING";
        if (type.matches("(?:CHAR|VARCHAR)\\s*\\(\\s*0\\s*\\).*")) return "STRING";
        return mapped;
    }

    private static String base(String type) {
        return type == null ? "" : type.replaceAll("[ (].*", "");
    }

    private static void count(String function, List<String> args, int min, int max) {
        if (args.size() < min || args.size() > max) {
            throw new IllegalArgumentException(function + " 参数数量错误，要求 " + (min == max ? min : min + "～" + max) + " 个");
        }
    }

    private static int integer(String value, String label) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(label + "必须是 INT 范围内的整数");
        }
    }

    private static void incompatible(String function, String type) {
        throw new IllegalArgumentException(function + " 不支持字段类型：" + type);
    }
}
