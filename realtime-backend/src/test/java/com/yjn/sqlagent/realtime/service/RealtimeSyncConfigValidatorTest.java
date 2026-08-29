package com.yjn.sqlagent.realtime.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
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
        when(servers.schema(3L, "orders")).thenReturn(schema(List.of("id", "tenant_id")));
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
    void rejectsMissingKeyAndPartitionCoveringAllKeys() {
        when(servers.schema(3L, "orders")).thenReturn(schema(List.of()));
        assertEquals("MySQL CDC 源表无主键，请配置私有主键：orders",
                assertThrows(IllegalArgumentException.class,
                        () -> validator.validate("mysql-cdc", 3L, validConfig())).getMessage());

        when(servers.schema(3L, "orders")).thenReturn(schema(List.of("id")));
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
        assertEquals("Sequence Field 引用的源字段不存在：missing",
                assertThrows(IllegalArgumentException.class,
                        () -> validator.validate("mysql-cdc", 3L, invalid)).getMessage());
    }

    private Map<String, Object> validConfig() {
        Map<String, Object> cdc = new LinkedHashMap<>();
        cdc.put("databaseName", "sales");
        cdc.put("selectedTables", List.of("orders"));
        cdc.put("targetDatabase", "ods_real");
        cdc.put("domainPrefix", "trade");
        cdc.put("metadataColumns", List.of("database_name", "table_name", "op_ts"));
        cdc.put("typeMappings", List.of());
        cdc.put("mode", "divided");
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

    private Map<String, Object> schema(List<String> primaryKeys) {
        return Map.of("table", "orders", "primaryKeys", primaryKeys, "columns", List.of(
                Map.of("name", "id", "type", "BIGINT", "nullable", false),
                Map.of("name", "tenant_id", "type", "BIGINT", "nullable", false),
                Map.of("name", "created_at", "type", "TIMESTAMP", "nullable", false),
                Map.of("name", "name", "type", "VARCHAR", "nullable", true)));
    }
}
