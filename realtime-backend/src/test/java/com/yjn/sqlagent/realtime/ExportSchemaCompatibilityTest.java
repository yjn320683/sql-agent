package com.yjn.sqlagent.realtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.yjn.sqlagent.realtime.common.ExportSchemaCompatibility;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExportSchemaCompatibilityTest {
    @Test
    void validatesLengthPrecisionNullabilityAndPrimaryKey() {
        List<Map<String, Object>> mappings = List.of(
                Map.of("sourceColumn", "id", "targetColumn", "id"),
                Map.of("sourceColumn", "name", "targetColumn", "name"),
                Map.of("sourceColumn", "amount", "targetColumn", "amount"));
        List<Map<String, Object>> source = List.of(
                column("id", "BIGINT", false), column("name", "VARCHAR(32)", false),
                column("amount", "DECIMAL(12,2)", false));
        List<Map<String, Object>> compatible = List.of(
                mysql("id", "bigint", false, null, 19L, 0L, false),
                mysql("name", "varchar(64)", false, 64L, null, null, false),
                mysql("amount", "decimal(14,4)", false, null, 14L, 4L, false));
        assertDoesNotThrow(() -> ExportSchemaCompatibility.validate(source, compatible, mappings, List.of("id"), "orders"));

        List<Map<String, Object>> tooShort = List.of(
                mysql("id", "bigint", false, null, 19L, 0L, false),
                mysql("name", "varchar(8)", false, 8L, null, null, false),
                mysql("amount", "decimal(14,4)", false, null, 14L, 4L, false));
        assertEquals("字段类型不兼容：name(VARCHAR(32)) → orders.name(varchar(8))；目标字符长度不足",
                assertThrows(IllegalArgumentException.class, () -> ExportSchemaCompatibility.validate(
                        source, tooShort, mappings, List.of("id"), "orders")).getMessage());
    }

    @Test
    void rejectsUnsignedTargetAndInsufficientDecimalScale() {
        List<Map<String, Object>> mapping = List.of(Map.of("sourceColumn", "value", "targetColumn", "value"));
        assertThrows(IllegalArgumentException.class, () -> ExportSchemaCompatibility.validate(
                List.of(column("value", "INT", false)),
                List.of(mysql("value", "int unsigned", false, null, 10L, 0L, true)), mapping, List.of(), "sink"));
        assertThrows(IllegalArgumentException.class, () -> ExportSchemaCompatibility.validate(
                List.of(column("value", "DECIMAL(12,4)", false)),
                List.of(mysql("value", "decimal(12,2)", false, null, 12L, 2L, false)), mapping, List.of(), "sink"));
    }

    @Test
    void fingerprintIsStableAndSensitiveToSchema() {
        java.util.Map<String, Object> primary = new java.util.LinkedHashMap<>(column("id", "BIGINT", false));
        primary.put("primaryKey", true);
        List<Map<String, Object>> first = List.of(primary);
        assertEquals(ExportSchemaCompatibility.fingerprint(first, List.of("id")),
                ExportSchemaCompatibility.fingerprint(first, List.of("id")));
        assertEquals(List.of("id"), ExportSchemaCompatibility.primaryKeys(first));
        org.junit.jupiter.api.Assertions.assertNotEquals(
                ExportSchemaCompatibility.fingerprint(first, List.of("id")),
                ExportSchemaCompatibility.fingerprint(first, List.of()));
        org.junit.jupiter.api.Assertions.assertNotEquals(
                ExportSchemaCompatibility.fingerprint(first, List.of("id")),
                ExportSchemaCompatibility.fingerprint(List.of(column("id", "INT", false)), List.of("id")));
    }

    private Map<String, Object> column(String name, String type, boolean nullable) {
        return Map.of("name", name, "dataType", type, "nullable", nullable);
    }
    private Map<String, Object> mysql(String name, String type, boolean nullable, Long length,
            Long precision, Long scale, boolean unsigned) {
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("name", name); result.put("type", type.replaceAll("\\(.*", "")); result.put("fullType", type);
        result.put("nullable", nullable); result.put("characterMaximumLength", length);
        result.put("numericPrecision", precision); result.put("numericScale", scale); result.put("unsigned", unsigned);
        return result;
    }
}
