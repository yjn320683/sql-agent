package com.yjn.sqlagent.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class RealtimeSchemaContractTest {

    private static final Set<String> REQUIRED_TABLES = Set.of(
            "rt_project", "rt_task", "rt_task_version", "rt_sync_task_config",
            "rt_sync_task_table_mapping", "rt_task_param", "rt_server", "rt_job_instance",
            "rt_task_operation", "rt_task_change_log", "rt_alert", "rt_paimon_business_domain");

    @Test
    void schemaContainsOnlyTheTwelveSyncTablesAndPrunedFields() throws Exception {
        String schema = resource("db/realtime_sync_schema.sql");
        Matcher matcher = Pattern.compile("CREATE TABLE IF NOT EXISTS (rt_[a-z_]+)").matcher(schema);
        List<String> tables = new java.util.ArrayList<>();
        while (matcher.find()) tables.add(matcher.group(1));

        assertEquals(REQUIRED_TABLES, tables.stream().collect(Collectors.toSet()));
        assertEquals(12, tables.size());
        assertTrue(schema.contains("managed_flag TINYINT(1) NOT NULL DEFAULT 1"));
        assertFalse(schema.contains("current_draft_version_id"));
        assertFalse(schema.contains("current_release_version_id"));
        assertFalse(schema.contains("last_success_debug_id"));
        assertFalse(schema.contains("debug_enabled"));
        assertFalse(schema.contains("object_type"));
    }

    @Test
    void migrationKeepsTheSyncClosureAndMarksEveryImportedInstanceReadOnly() throws Exception {
        String migration = resource("db/migrate_realtime_sync_data.sql");
        assertTrue(migration.contains("WHERE task_type='sync'"));
        assertTrue(migration.contains("WHERE c.source_server_id=s.id"));
        assertTrue(migration.contains("i.execution_mode, 0,"));
        assertTrue(migration.contains("NULL, o.create_time"));
        assertTrue(migration.contains("目标实时同步表已有数据，禁止重复迁移"));
        assertTrue(migration.contains("DECLARE EXIT HANDLER FOR SQLEXCEPTION"));
        assertTrue(migration.contains("JSON_VALID(v.config)=0"));
        assertTrue(migration.contains("同步任务引用闭包校验失败"));
    }

    private String resource(String path) throws Exception {
        try (java.io.InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("missing resource " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
