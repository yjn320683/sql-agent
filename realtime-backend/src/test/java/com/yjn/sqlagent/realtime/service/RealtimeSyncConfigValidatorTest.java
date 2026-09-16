package com.yjn.sqlagent.realtime.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RealtimeSyncConfigValidatorTest {
    private RealtimeServerService servers;
    private RealtimeSyncConfigValidator validator;

    @BeforeEach
    void setUp() {
        servers = mock(RealtimeServerService.class);
        validator = new RealtimeSyncConfigValidator(servers);
        when(servers.schemas(3L, List.of("orders"))).thenReturn(
                Map.of("orders", schema("orders", List.of("id", "tenant_id"))));
    }

    @Test
    void acceptsSourcePrimaryKeyAndStructuredComputedColumn() {
        Map<String, Object> config = validConfig();
        cdc(config).put("tableConfigs", Map.of("orders", Map.of(
                "computedColumns", List.of("order_day=date_format(created_at,yyyyMMdd)"),
                "primaryKeys", List.of("id", "order_day"),
                "partitionKeys", List.of("order_day"))));

        assertDoesNotThrow(() -> validator.validate("mysql-cdc", 3L, config));
    }

    @Test
    void rejectsStringIgnoreIncompatibleAndMemoryWithoutUnit() {
        Map<String, Object> stringBooleanConfig = validConfig();
        cdc(stringBooleanConfig).put("ignoreIncompatible", "false");
        assertEquals("ignoreIncompatible 必须是布尔值",
                assertThrows(IllegalArgumentException.class,
                        () -> validator.validate("mysql-cdc", 3L, stringBooleanConfig)).getMessage());

        Map<String, Object> invalid = validConfig();
        invalid.put("taskManagerMemory", "1024");
        assertEquals("TaskManager 内存必须包含单位，例如 1GB 或 2048MB",
                assertThrows(IllegalArgumentException.class,
                        () -> validator.validate("mysql-cdc", 3L, invalid)).getMessage());
    }

    @Test
    void acceptsOnlyCombinedModeAndRejectsNullableKeys() {
        Map<String, Object> divided = validConfig();
        cdc(divided).put("mode", "divided");
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("mysql-cdc", 3L, divided));

        Map<String, Object> nullableKey = validConfig();
        cdc(nullableKey).put("tableConfigs", Map.of("orders", Map.of("primaryKeys", List.of("name"))));
        assertEquals("源表 orders 的主键字段 name 允许 NULL，不能作为 Paimon 主键",
                assertThrows(IllegalArgumentException.class,
                        () -> validator.validate("mysql-cdc", 3L, nullableKey)).getMessage());
    }

    @Test
    void rejectsMissingKeyAndPartitionCoveringAllKeys() {
        when(servers.schemas(3L, List.of("orders"))).thenReturn(
                Map.of("orders", schema("orders", List.of())));
        assertEquals("MySQL CDC 源表无主键，请配置私有主键：orders",
                assertThrows(IllegalArgumentException.class,
                        () -> validator.validate("mysql-cdc", 3L, validConfig())).getMessage());

        when(servers.schemas(3L, List.of("orders"))).thenReturn(
                Map.of("orders", schema("orders", List.of("id"))));
        Map<String, Object> config = validConfig();
        cdc(config).put("tableConfigs", Map.of("orders", Map.of("partitionKeys", List.of("id"))));
        assertEquals("源表 orders 的分区键不能覆盖全部最终主键，请至少保留一个非分区主键字段",
                assertThrows(IllegalArgumentException.class,
                        () -> validator.validate("mysql-cdc", 3L, config)).getMessage());
    }

    @Test
    void rejectsInvalidComputedReferenceAndCommonTableFieldOverride() {
        Map<String, Object> computedConfig = validConfig();
        cdc(computedConfig).put("tableConfigs", Map.of("orders", Map.of(
                "computedColumns", List.of("order_day=date_format(missing,yyyyMMdd)"))));
        assertEquals("源表 orders 的计算列引用字段不存在：missing",
                assertThrows(IllegalArgumentException.class,
                        () -> validator.validate("mysql-cdc", 3L, computedConfig)).getMessage());

        Map<String, Object> invalid = validConfig();
        cdc(invalid).put("tableConfOverrides", Map.of("sequence.field", "missing"));
        assertEquals("Sequence Field 引用的目标字段不存在：missing",
                assertThrows(IllegalArgumentException.class,
                        () -> validator.validate("mysql-cdc", 3L, invalid)).getMessage());
    }

    @Test
    void readsAllSelectedSchemasInOneBatch() {
        List<String> tables = java.util.stream.IntStream.range(0, 100)
                .mapToObj(index -> "orders_" + index).collect(java.util.stream.Collectors.toList());
        Map<String, Map<String, Object>> schemas = new LinkedHashMap<>();
        for (String table : tables) schemas.put(table, schema(table, List.of("id")));
        when(servers.schemas(3L, tables)).thenReturn(schemas);
        Map<String, Object> config = validConfig();
        cdc(config).put("selectedTables", tables);

        assertDoesNotThrow(() -> validator.validate("mysql-cdc", 3L, config));

        verify(servers).schemas(3L, tables);
        verify(servers, never()).schema(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
    }

    private Map<String, Object> validConfig() {
        Map<String, Object> cdc = new LinkedHashMap<>();
        cdc.put("databaseName", "sales");
        cdc.put("selectedTables", List.of("orders"));
        cdc.put("targetDatabase", "ods_real");
        cdc.put("domainPrefix", "trade");
        cdc.put("metadataColumns", List.of("database_name", "table_name", "op_ts"));
        cdc.put("typeMappings", List.of());
        cdc.put("mode", "combined");
        cdc.put("ignoreIncompatible", false);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("sourceServerId", 3L);
        config.put("taskManagerMemory", "3GB");
        config.put("jobManagerMemory", "1GB");
        config.put("cdcConfig", cdc);
        return config;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cdc(Map<String, Object> config) {
        return (Map<String, Object>) config.get("cdcConfig");
    }

    private Map<String, Object> schema(String table, List<String> primaryKeys) {
        return Map.of("table", table, "primaryKeys", primaryKeys, "columns", List.of(
                Map.of("name", "id", "type", "BIGINT", "nullable", false),
                Map.of("name", "tenant_id", "type", "BIGINT", "nullable", false),
                Map.of("name", "created_at", "type", "TIMESTAMP", "nullable", false),
                Map.of("name", "name", "type", "VARCHAR", "nullable", true)));
    }
}
