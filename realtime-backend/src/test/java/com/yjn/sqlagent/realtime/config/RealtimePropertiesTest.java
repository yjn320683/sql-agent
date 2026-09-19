package com.yjn.sqlagent.realtime.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RealtimePropertiesTest {

    @Test
    void syncDatabasesAndCatalogDefaultsArePlatformControlled() {
        RealtimeProperties properties = new RealtimeProperties();
        properties.setTargetDatabase("legacy_database");
        properties.setPaimonDebugTargetDatabase("legacy_debug");

        assertEquals("ods_rt", properties.getTargetDatabase());
        assertEquals("paimon_debug", properties.getPaimonDebugTargetDatabase());
        assertEquals("hive", properties.getCatalogConf().get("metastore"));
        assertEquals("SEQUENTIAL", properties.getCatalogConf().get("hive.metastore.uri.selection"));
    }

    @Test
    void yarnQueueIsRequiredAndTrimmed() {
        RealtimeProperties properties = new RealtimeProperties();
        properties.setYarnQueue(" root.realtime ");
        assertEquals("root.realtime", properties.resolveYarnQueue());
        properties.setYarnQueue("  ");
        assertEquals("app.realtime.yarn-queue 未配置",
                assertThrows(IllegalStateException.class, properties::resolveYarnQueue).getMessage());
    }
}
