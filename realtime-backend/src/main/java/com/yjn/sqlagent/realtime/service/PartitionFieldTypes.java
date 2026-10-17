package com.yjn.sqlagent.realtime.service;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 与 Paimon MySQL 映射一致的分区字段和默认值校验子集。 */
final class PartitionFieldTypes {
    private PartitionFieldTypes() {}

    static String source(String raw, List<String> mappings) {
        String type = (raw == null ? "" : raw).toUpperCase(Locale.ROOT).trim();
        String base = type.replaceAll("[ (].*", "");
        if (mappings.contains("to-string")) return "STRING";
        boolean unsigned = type.contains("UNSIGNED") || type.contains("ZEROFILL");
        switch (base) {
            case "TINYINT":
                if (!unsigned && type.matches("TINYINT\\s*\\(1\\).*") && !mappings.contains("tinyint1-not-bool")) return "BOOLEAN";
                return unsigned ? "SMALLINT" : "TINYINT";
            case "SMALLINT": return unsigned ? "INT" : "SMALLINT";
            case "INT": case "INTEGER": case "MEDIUMINT": return unsigned ? "BIGINT" : "INT";
            case "YEAR": return "INT";
            case "BIGINT": return unsigned && !mappings.contains("bigint-unsigned-to-bigint") ? "DECIMAL" : "BIGINT";
            case "DATE": return "DATE";
            case "CHAR": case "VARCHAR": return mappings.contains("char-to-string") ? "STRING" : type.replaceAll("\\s+.*$", "");
            case "TINYTEXT": case "TEXT": case "MEDIUMTEXT": case "ENUM": case "JSON": return "STRING";
            case "LONGTEXT": return mappings.contains("longtext-to-bytes") ? "BYTES" : "STRING";
            default: return base;
        }
    }

    static boolean directAllowed(String raw) {
        return Set.of("TINYINT", "SMALLINT", "MEDIUMINT", "INT", "INTEGER", "BIGINT", "YEAR",
                "DATE", "CHAR", "VARCHAR", "TINYTEXT", "TEXT", "MEDIUMTEXT", "LONGTEXT")
                .contains((raw == null ? "" : raw).toUpperCase(Locale.ROOT).trim().replaceAll("[ (].*", ""));
    }

    static boolean allowed(String type) {
        return type != null && Set.of("TINYINT", "SMALLINT", "INT", "BIGINT", "DATE", "STRING", "CHAR", "VARCHAR")
                .contains(type.replaceAll("\\(.*", ""));
    }

    static String computed(String function, List<String> args, String referenceType) {
        switch (function) {
            case "year": case "month": case "day": case "hour": case "minute": case "second": return "INT";
            case "date_format": case "substring": case "upper": case "lower": case "trim": return "STRING";
            case "cast": return args.size() == 2 ? args.get(1).toUpperCase(Locale.ROOT) : "STRING";
            case "truncate": return referenceType;
            default: return "UNSUPPORTED";
        }
    }

    static void validate(String value, String type, String format) {
        if (value == null || value.isBlank() || !allowed(type)) throw new IllegalArgumentException("分区类型或默认值无效");
        if (value.contains("/")) throw new IllegalArgumentException("默认分区值不能包含 /");
        if (format != null && format.contains("/")) throw new IllegalArgumentException("分区日期格式不能包含 /");
        switch (type) {
            case "TINYINT": integer(value, 8); break;
            case "SMALLINT": integer(value, 16); break;
            case "INT": integer(value, 32); break;
            case "BIGINT": integer(value, 64); break;
            case "DATE":
                if (!value.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException("日期必须为 yyyy-MM-dd");
                LocalDate.parse(value); break;
            default:
                Matcher length = Pattern.compile("(?:CHAR|VARCHAR)\\((\\d+)\\)").matcher(type);
                if (length.matches() && value.codePointCount(0, value.length()) > Integer.parseInt(length.group(1))) {
                    throw new IllegalArgumentException("默认值超过字符长度限制");
                }
                if (format != null) partitionDateFormatter(format).parse(value);
        }
    }

    private static DateTimeFormatter partitionDateFormatter(String format) {
        DateTimeFormatterBuilder builder = new DateTimeFormatterBuilder();
        boolean quoted = false;
        int start = 0;
        for (int index = 0; index < format.length(); index++) {
            char character = format.charAt(index);
            if (character == '\'') quoted = !quoted;
            else if (!quoted && (character == 'y' || character == 'u')) {
                int end = index + 1;
                while (end < format.length() && format.charAt(end) == character) end++;
                if (end - index == 4) {
                    builder.appendPattern(format.substring(start, index));
                    builder.appendValue(ChronoField.YEAR, 4);
                    start = end;
                }
                index = end - 1;
            }
        }
        return builder.appendPattern(format.substring(start)).toFormatter().withResolverStyle(ResolverStyle.STRICT);
    }

    private static void integer(String value, int bits) {
        if (!value.matches("[+-]?\\d+")) throw new IllegalArgumentException("默认值必须为整数");
        BigInteger number = new BigInteger(value);
        BigInteger boundary = BigInteger.ONE.shiftLeft(bits - 1);
        if (number.compareTo(boundary.negate()) < 0 || number.compareTo(boundary) >= 0) {
            throw new IllegalArgumentException("默认值超出整数范围");
        }
    }
}
