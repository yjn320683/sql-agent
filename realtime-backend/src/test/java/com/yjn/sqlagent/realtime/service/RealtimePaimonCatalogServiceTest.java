package com.yjn.sqlagent.realtime.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.yjn.sqlagent.realtime.config.RealtimeProperties;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.catalog.CatalogFactory;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.options.Options;
import org.apache.paimon.schema.Schema;
import org.apache.paimon.types.DataTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RealtimePaimonCatalogServiceTest {
    private final RealtimePaimonCatalogService service = new RealtimePaimonCatalogService(new RealtimeProperties());

    @Test
    void onlyAcceptsManagedTableOptionWhitelist() {
        assertEquals(Map.of("bucket", "4", "changelog-producer", "input", "sink.parallelism", "2",
                        "consumer.expiration-time", "1 d"),
                service.safeOptions(Map.of("bucket", 4, "changelog-producer", "input", "sink.parallelism", 2,
                        "consumer.expiration-time", "1 d")));
        assertEquals("不允许配置 Paimon 参数：path",
                assertThrows(IllegalArgumentException.class,
                        () -> service.safeOptions(Map.of("path", "file:///tmp"))).getMessage());
    }

    @Test
    void acceptsSupportedScalarTypesAndRejectsComplexTypes() {
        assertEquals("DECIMAL(18, 2)", service.dataType("decimal(18,2)").asSQLString());
        assertEquals("TIMESTAMP(3)", service.dataType("timestamp(3)").asSQLString());
        assertEquals("不支持的 Paimon 标量类型：ARRAY<STRING>",
                assertThrows(IllegalArgumentException.class,
                        () -> service.dataType("ARRAY<STRING>")).getMessage());
    }

    @Test
    void checksPhysicalTableInFilesystemCatalog(@TempDir Path warehouse) throws Exception {
        Options options = new Options();
        options.set("warehouse", warehouse.toUri().toString());
        options.set("metastore", "filesystem");
        try (Catalog catalog = CatalogFactory.createCatalog(CatalogContext.create(options))) {
            catalog.createDatabase("ods_real", false);
            catalog.createTable(Identifier.create("ods_real", "orders"), Schema.newBuilder()
                    .column("id", DataTypes.BIGINT().notNull()).primaryKey("id").build(), false);
        }
        RealtimeProperties properties = new RealtimeProperties();
        properties.setPaimonWarehouse(warehouse.toUri().toString());
        properties.setCatalogConf(Map.of("metastore", "filesystem"));

        assertEquals(Set.of("orders"), new RealtimePaimonCatalogService(properties)
                .existingTables("ods_real", List.of("orders", "missing")));
    }
}
