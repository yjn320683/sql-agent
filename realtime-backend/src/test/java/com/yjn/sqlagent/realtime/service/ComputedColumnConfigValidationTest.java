package com.yjn.sqlagent.realtime.service;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ComputedColumnConfigValidationTest {
    @Test
    void rejectsSlashInDateFormat() {
        for (String format : List.of("yyyy/MM/dd", "yyyy'/'MM")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> ComputedColumnConfigValidation.validate("date_format", List.of("ts", format), "DATETIME"));
            assertEquals("date_format 日期格式不能包含 /，请使用 - 或无分隔符格式", error.getMessage());
        }
    }
    @Test
    void rejectsUnknownFunctionsAndWrongArgumentCounts() {
        rejects("custom_now", List.of(), null);
        rejects("year", List.of(), null);
        rejects("year", List.of("ts", "3", "9"), "TIMESTAMP");
        rejects("date_format", List.of("ts"), "TIMESTAMP");
        rejects("date_format", List.of("ts", "yyyyMMdd", "3", "9"), "TIMESTAMP");
        rejects("substring", List.of("name"), "STRING");
        rejects("substring", List.of("name", "0", "1", "2"), "STRING");
        rejects("truncate", List.of("n", "10", "20"), "INT");
        rejects("upper", List.of("name", "1"), "STRING");
        rejects("lower", List.of(), "STRING");
        rejects("trim", List.of("name", "1"), "STRING");
        rejects("now", List.of("ts"), "TIMESTAMP");
    }

    @Test
    void validatesOptionalPrecisionForEveryTemporalFunction() {
        for (String function : List.of("year", "month", "day", "hour", "minute", "second", "date_format")) {
            accepts(function, "date_format".equals(function) ? List.of("value", "yyyy-MM-dd") : List.of("value"), "BIGINT");
            for (String precision : List.of("0", "3", "6", "9")) {
                accepts(function, temporalArgs(function, precision), "BIGINT");
                accepts(function, temporalArgs(function, precision), "STRING");
                rejects(function, temporalArgs(function, precision), "TIMESTAMP");
            }
            for (String precision : List.of("2", "-1", "abc", "2147483648")) {
                rejects(function, temporalArgs(function, precision), "BIGINT");
            }
        }
    }

    @Test
    void validatesFormattingWithoutPartitionDefaults() {
        accepts("date_format", List.of("ts", "yyyy-MM-dd'T'HH:mm:ss.SSS"), "TIMESTAMP");
        accepts("date_format", List.of("ts", "yyyy-ww"), "TIMESTAMP");
        rejects("date_format", List.of("ts", "yyyy-MM-dd#"), "TIMESTAMP");
        rejects("date_format", List.of("ts", "yyyy-MM-dd XXX"), "TIMESTAMP");
        rejects("date_format", List.of("ts", "yyyy','MM"), "TIMESTAMP");
    }

    @Test
    void validatesSubstringIndexesWithoutRestrictingSerializedInputType() {
        accepts("substring", List.of("amount", "0"), "DOUBLE");
        accepts("substring", List.of("ts", "0", "10"), "TIMESTAMP");
        accepts("substring", List.of("name", "0", "2147483647"), "STRING");
        for (List<String> args : List.of(List.of("name", "-1"), List.of("name", "0", "0"),
                List.of("name", "5", "2"), List.of("name", "abc"), List.of("name", "2147483648"))) {
            rejects("substring", args, "STRING");
        }
    }

    @Test
    void validatesTruncateWidthAndMappedType() {
        for (String type : List.of("TINYINT", "SMALLINT", "INT", "BIGINT", "DECIMAL", "CHAR(8)", "VARCHAR(8)", "STRING")) {
            accepts("truncate", List.of("value", "10"), type);
        }
        for (String width : List.of("0", "-1", "1.5", "abc", "2147483648")) rejects("truncate", List.of("value", width), "INT");
        for (String type : List.of("FLOAT", "DOUBLE", "DATE", "BOOLEAN", "BYTES")) rejects("truncate", List.of("value", "10"), type);
    }

    @Test
    void validatesStringAndTemporalFunctionInputTypes() {
        for (String function : List.of("upper", "lower", "trim")) {
            accepts(function, List.of("value"), "VARCHAR(8)");
            accepts(function, List.of("value"), "STRING");
            for (String type : List.of("INT", "CHAR(8)", "TIMESTAMP", "BOOLEAN", "BYTES")) rejects(function, List.of("value"), type);
        }
        for (String type : List.of("DATE", "DATETIME", "TIMESTAMP", "TIMESTAMP_LTZ", "INT", "BIGINT", "STRING", "VARCHAR(64)")) accepts("year", List.of("value"), type);
        for (String type : List.of("DOUBLE", "DECIMAL", "BOOLEAN", "BYTES", "TIME", "CHAR(8)")) rejects("year", List.of("value"), type);
    }

    @Test
    void castsConstantsUsingActualCdcConversionAndRejectsNullResults() {
        for (List<String> args : List.of(List.of("abc"), List.of("127", "TINYINT"), List.of("-32768", "SMALLINT"),
                List.of("2147483647", "INT"), List.of("9223372036854775807", "BIGINT"), List.of("true", "BOOLEAN"),
                List.of("1.5", "FLOAT"), List.of("1.5", "DOUBLE"), List.of("11", "DECIMAL"),
                List.of("2024-02-29", "DATE"), List.of("12:34:56", "TIME"), List.of("25:00:00", "TIME"),
                List.of("2026-10-14 12:34:56", "TIMESTAMP"), List.of("2026-10-14 12:34:56", "TIMESTAMP_LTZ"))) {
            accepts("cast", args, null);
        }
        for (List<String> args : List.of(List.of("abc", "INT"), List.of("128", "TINYINT"), List.of("32768", "SMALLINT"),
                List.of("2147483648", "INT"), List.of("9223372036854775808", "BIGINT"), List.of("abc", "BOOLEAN"),
                List.of("abc", "FLOAT"), List.of("abc", "DOUBLE"), List.of("99999999999", "DECIMAL"),
                List.of("2026-02-30", "DATE"), List.of("invalid", "TIME"), List.of("invalid", "TIMESTAMP"))) {
            rejects("cast", args, null);
        }
    }

    @Test
    void appliesMysqlMappingsBeforeCheckingFunctionTypes() {
        assertEquals("BOOLEAN", ComputedColumnConfigValidation.sourceType("tinyint(1)", List.of()));
        assertEquals("TINYINT", ComputedColumnConfigValidation.sourceType("tinyint(1)", List.of("tinyint1-not-bool")));
        assertEquals("STRING", ComputedColumnConfigValidation.sourceType("char(8)", List.of("char-to-string")));
        assertEquals("BYTES", ComputedColumnConfigValidation.sourceType("longtext", List.of("longtext-to-bytes")));
        assertEquals("STRING", ComputedColumnConfigValidation.sourceType("decimal(50,2)", List.of()));
        assertEquals("STRING", ComputedColumnConfigValidation.sourceType("geometry", List.of()));
        assertEquals("DECIMAL", ComputedColumnConfigValidation.sourceType("bigint unsigned", List.of()));
    }

    private List<String> temporalArgs(String function, String precision) {
        return "date_format".equals(function) ? List.of("value", "yyyy-MM-dd", precision) : List.of("value", precision);
    }

    private void accepts(String function, List<String> args, String type) {
        assertDoesNotThrow(() -> ComputedColumnConfigValidation.validate(function, args, type), function + args + type);
    }

    private void rejects(String function, List<String> args, String type) {
        assertThrows(IllegalArgumentException.class, () -> ComputedColumnConfigValidation.validate(function, args, type), function + args + type);
    }
}
