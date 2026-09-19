package com.yjn.sqlagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class OfflineDevelopmentSchemaContractTest {
    private static final Set<String> LEGACY_REQUIRED_TABLES = Set.of(
            "sql_task_version_check", "sql_task_schedule", "sql_task_dependency",
            "sql_task_schedule_run", "sql_task_backfill_batch");
    private static final Set<String> REQUIRED_TABLES = Set.of(
            "sql_task_version_check", "sql_task_schedule", "sql_task_dependency",
            "sql_task_schedule_run", "sql_task_backfill_batch", "sql_task_backfill_item");

    @Test
    void baseSchemaContainsEveryNewOfflineDevelopmentTable() throws Exception {
        String schema = resource("db/sql_task.sql");
        Set<String> tables = tables(schema);

        assertTrue(tables.containsAll(REQUIRED_TABLES));
        assertTrue(schema.contains("version_revision BIGINT NOT NULL"));
        assertTrue(schema.contains("version_checksum CHAR(64)"));
        assertTrue(schema.contains("UNIQUE KEY uk_task_upstream (task_id,upstream_task_id)"));
        assertTrue(schema.contains("backfill_batch_id BIGINT NULL"));
        assertTrue(schema.contains("retry_interval_seconds INT NOT NULL DEFAULT 60"));
        assertTrue(schema.contains("timeout_policy VARCHAR(16) NOT NULL DEFAULT 'ALERT_ONLY'"));
        assertTrue(schema.contains("UNIQUE KEY uk_backfill_batch_date (batch_id,business_date)"));
        assertTrue(schema.contains("idx_task_execution_success_sample (task_id, status, id)"));
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS task_lineage_snapshot"));
    }

    @Test
    void upgradeScriptCreatesExactlyTheExpectedFeatureTables() throws Exception {
        String migration = resource("db/20260907_offline_development_platform.sql");

        assertEquals(LEGACY_REQUIRED_TABLES, tables(migration));
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS sql_task_version_check"));
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS sql_task_schedule_run"));
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS sql_task_backfill_batch"));
    }

    @Test
    void batchFiveUpgradeIsSelfContainedAndIdempotentForOlderEnvironments() throws Exception {
        String migration = resource("db/upgrade-20260916-scheduling-dag-sla-backfill.sql");

        assertTrue(tables(migration).containsAll(Set.of(
                "sql_task_schedule", "sql_task_dependency", "sql_task_schedule_run",
                "sql_task_backfill_batch", "sql_task_backfill_item")));
        assertTrue(migration.contains("information_schema.COLUMNS"));
        assertTrue(migration.contains("idx_task_execution_success_sample"));
        assertTrue(migration.contains("UNIQUE KEY uk_backfill_batch_date (batch_id,business_date)"));
    }

    @Test
    void batchSevenUpgradeKeepsLineageAndBusinessDomainSchemaIdempotent() throws Exception {
        String migration = resource("db/upgrade-20260917-lineage-domain-assets.sql");

        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS task_lineage_snapshot"));
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS rt_asset_business_domain_relation"));
        assertTrue(migration.contains("information_schema.columns"));
        assertTrue(migration.contains("column_name='description'"));
        assertTrue(migration.contains("column_name='owner'"));
        assertTrue(migration.contains("column_name='disabled_time'"));
    }

    @Test
    void batchEightUpgradeKeepsSnapshotsImmutableAndBackfillsOnlyCurrentSchemaBaseline() throws Exception {
        String migration = resource("db/upgrade-20260917-global-lineage-schema-history.sql");

        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS task_lineage_relation"));
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS rt_realtime_table_schema_version"));
        assertTrue(migration.contains("BACKFILLED_BASELINE"));
        assertTrue(migration.contains("WHERE NOT EXISTS"));
        assertFalse(migration.contains("UPDATE task_lineage_snapshot"));
    }

    @Test
    void batchNineUpgradeProvidesReliableNeo4jProjectionContracts() throws Exception {
        String migration = resource("db/upgrade-20260917-data-map-neo4j.sql");

        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS data_map_lineage_run"));
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS data_map_lineage_task_state"));
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS data_map_graph_outbox"));
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS data_map_projection_generation"));
        assertTrue(migration.contains("UNIQUE KEY uk_data_map_outbox_snapshot (snapshot_id,event_type,generation_no)"));
        assertTrue(migration.contains("KEY idx_data_map_outbox_claim (status,available_at,id)"));
        assertTrue(migration.contains("UNIQUE KEY uk_data_map_generation_no (generation_no)"));
    }

    private Set<String> tables(String sql) {
        Matcher matcher = Pattern.compile("CREATE TABLE IF NOT EXISTS (sql_task_[a-z_]+)").matcher(sql);
        List<String> names = new ArrayList<>();
        while (matcher.find()) names.add(matcher.group(1));
        return names.stream().collect(Collectors.toSet());
    }

    private String resource(String path) throws Exception {
        try (java.io.InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("missing resource " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
