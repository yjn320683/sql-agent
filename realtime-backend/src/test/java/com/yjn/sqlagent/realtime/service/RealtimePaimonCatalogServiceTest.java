package com.yjn.sqlagent.realtime.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.yjn.sqlagent.realtime.config.RealtimeProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RealtimePaimonCatalogServiceTest {
    private final RealtimePaimonCatalogService service = new RealtimePaimonCatalogService(new RealtimeProperties());

    @Test
    void onlyAcceptsManagedTableOptionWhitelist() {
        assertEquals(Map.of("bucket", "4", "changelog-producer", "input"),
                service.safeOptions(Map.of("bucket", 4, "changelog-producer", "input")));
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
}
