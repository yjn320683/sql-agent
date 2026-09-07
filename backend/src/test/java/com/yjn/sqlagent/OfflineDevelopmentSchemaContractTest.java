package com.yjn.sqlagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    private static final Set<String> REQUIRED_TABLES = Set.of(
            "sql_task_version_check", "sql_task_schedule", "sql_task_dependency",
            "sql_task_schedule_run", "sql_task_backfill_batch");

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
    }

    @Test
    void upgradeScriptCreatesExactlyTheExpectedFeatureTables() throws Exception {
        String migration = resource("db/20260907_offline_development_platform.sql");

        assertEquals(REQUIRED_TABLES, tables(migration));
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS sql_task_version_check"));
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS sql_task_schedule_run"));
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS sql_task_backfill_batch"));
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
