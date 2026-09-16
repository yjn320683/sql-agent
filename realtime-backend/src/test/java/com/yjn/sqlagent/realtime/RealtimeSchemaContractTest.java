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
            "rt_sync_task_table_mapping", "rt_task_param", "rt_server", "rt_task_instance",
            "rt_task_operation", "rt_task_change_log", "rt_alert", "rt_paimon_business_domain",
            "rt_realtime_table", "rt_realtime_table_column", "rt_task_table_reference",
            "rt_compute_task_config", "rt_export_task_config", "rt_export_task_table_mapping",
            "rt_sync_progress_snapshot", "rt_sync_dirty_record", "rt_schema_change_event");

    @Test
    void schemaContainsUnifiedRealtimeTablesAndPrunedFields() throws Exception {
        String schema = resource("db/realtime_sync_schema.sql");
        Matcher matcher = Pattern.compile("CREATE TABLE IF NOT EXISTS (rt_[a-z_]+)").matcher(schema);
        List<String> tables = new java.util.ArrayList<>();
        while (matcher.find()) tables.add(matcher.group(1));

        assertEquals(REQUIRED_TABLES, tables.stream().collect(Collectors.toSet()));
        assertEquals(21, tables.size());
        assertTrue(schema.contains("managed_flag TINYINT(1) NOT NULL DEFAULT 1"));
        assertTrue(schema.contains("idx_task_instance_managed_status"));
        assertTrue(schema.contains("idx_task_instance_task_mode_create"));
        assertTrue(schema.contains("idx_task_instance_yarn_application"));
        assertTrue(schema.contains("idx_task_instance_task_mode_status"));
        assertTrue(schema.contains("idx_task_operation_instance"));
        assertTrue(schema.contains("idx_task_change_log_instance"));
        assertFalse(schema.contains("CREATE TABLE IF NOT EXISTS rt_job_instance"));
        assertFalse(schema.contains("job_instance_id"));
        assertFalse(schema.contains("idx_job_"));
        assertFalse(schema.contains("current_draft_version_id"));
        assertFalse(schema.contains("current_release_version_id"));
        assertFalse(schema.contains("last_success_debug_id"));
        assertFalse(schema.contains("debug_enabled"));
        assertFalse(schema.contains("object_type"));
        assertTrue(schema.contains("INSERT IGNORE INTO rt_task_param"));
        assertTrue(schema.contains("'sync','mysql_conf','scan.snapshot.fetch.size'"));
        assertTrue(schema.contains("'sync','table_conf','changelog-producer'"));
        assertTrue(schema.contains("'sync','flink_conf','high-availability.type'"));
        assertTrue(schema.contains("INSERT IGNORE INTO rt_paimon_business_domain"));
        assertTrue(schema.contains("realtime_table_id BIGINT NULL"));
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS rt_compute_task_config"));
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS rt_export_task_config"));
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS rt_sync_progress_snapshot"));
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS rt_sync_dirty_record"));
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS rt_schema_change_event"));
    }

    @Test
    void upgradesLegacyServerAndInstanceNamesToTheUnifiedContract() throws Exception {
        String instanceUpgrade = resource("db/upgrade-20260903-unified-task-instance.sql");
        assertTrue(instanceUpgrade.contains("RENAME TABLE rt_job_instance TO rt_task_instance"));
        assertTrue(instanceUpgrade.contains("CHANGE COLUMN job_instance_id task_instance_id"));
        assertTrue(instanceUpgrade.contains("RENAME INDEX idx_job_managed_status TO idx_task_instance_managed_status"));
        assertTrue(instanceUpgrade.contains("RENAME INDEX idx_job_task_mode_create TO idx_task_instance_task_mode_create"));
        assertTrue(instanceUpgrade.contains("RENAME INDEX idx_job_yarn_application TO idx_task_instance_yarn_application"));

        String serverUpgrade = resource("db/upgrade-20260903-server-database-prefix.sql");
        assertTrue(serverUpgrade.contains("CHANGE COLUMN database_abbr database_prefix"));
        assertTrue(serverUpgrade.contains("uk_server_type_database_identity"));
    }

    @Test
    void migrationKeepsTheSyncClosureAndMarksEveryImportedInstanceReadOnly() throws Exception {
        String migration = resource("db/migrate_realtime_sync_data.sql");
        assertTrue(migration.contains("WHERE task_type='sync'"));
        assertTrue(migration.contains("COALESCE(deleted_flag,0)=0"));
        assertTrue(migration.contains("WHERE c.source_server_id=s.id"));
        assertTrue(migration.contains("i.execution_mode, 0,"));
        assertTrue(migration.contains("NULL, o.create_time"));
        assertTrue(migration.contains("目标实时同步表已有数据，禁止重复迁移"));
        assertTrue(migration.contains("DECLARE EXIT HANDLER FOR SQLEXCEPTION"));
        assertTrue(migration.contains("JSON_VALID(v.config)=0"));
        assertTrue(migration.contains("同步任务引用闭包校验失败"));
    }

    @Test
    void observabilityUpgradeCreatesAllThreeTablesAndIsRepeatable() throws Exception {
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                "jdbc:h2:mem:observabilityUpgrade;MODE=MySQL;DATABASE_TO_LOWER=TRUE")) {
            String upgrade = resource("db/upgrade-20260907-sync-observability-schema-evolution.sql");
            for (int attempt = 0; attempt < 2; attempt++) {
                for (String statement : upgrade.split(";")) {
                    if (statement.isBlank() || statement.trim().startsWith("SET NAMES")) continue;
                    connection.createStatement().execute(statement);
                }
            }
            for (String table : List.of("rt_sync_progress_snapshot", "rt_sync_dirty_record", "rt_schema_change_event")) {
                try (java.sql.ResultSet rows = connection.createStatement().executeQuery("SELECT COUNT(*) FROM " + table)) {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getInt(1));
                }
            }
        }
    }

    @Test
    void syncDefaultsUpgradeOnlyChangesDictionaryAndCoversLegacySlots() throws Exception {
        String upgrade = resource("db/upgrade-20260911-sync-default-parallelism.sql");
        assertTrue(upgrade.contains("param_value IN ('1', '2')"));
        assertTrue(upgrade.contains("min_value = 1, max_value = 4"));
        assertFalse(upgrade.contains("UPDATE rt_task SET"));
        assertFalse(upgrade.contains("UPDATE rt_task_version"));
        assertFalse(upgrade.contains("UPDATE rt_sync_task_config"));
        assertFalse(upgrade.contains("UPDATE rt_task_instance"));
        assertTrue(resource("db/upgrade-20260911-add-sync-precommit-compact.sql").contains("'precommit-compact'"));
    }

    private String resource(String path) throws Exception {
        try (java.io.InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("missing resource " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
