package com.yjn.sqlagent.service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 只读检查数据库迁移版本；应用启动绝不执行 DDL。 */
@Service
public class SchemaMigrationStatusService {
    public static final String REQUIRED_VERSION = "20260918.02";
    private static final Set<String> REQUIRED_COLUMNS = Set.of(
            "chat_session.context_type",
            "chat_session.context_id",
            "chat_session.context_title",
            "task_lineage_snapshot.lineage_json",
            "data_map_graph_outbox.status",
            "data_map_graph_outbox.available_at",
            "data_map_graph_outbox.locked_at");
    private static final Set<String> REQUIRED_INDEXES = Set.of(
            "chat_session.idx_chat_session_context",
            "data_map_graph_outbox.idx_data_map_outbox_recovery");
    private final JdbcTemplate jdbc;

    public SchemaMigrationStatusService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> status() {
        boolean tracked = tableExists("flyway_schema_history");
        String current = tracked ? currentVersion() : null;
        List<String> missingContracts = missingContracts();
        boolean versionCompatible = tracked && compareVersions(current, REQUIRED_VERSION) >= 0;
        boolean compatible = versionCompatible && missingContracts.isEmpty();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tracked", tracked);
        result.put("currentVersion", current);
        result.put("requiredVersion", REQUIRED_VERSION);
        result.put("pendingMigrations", versionCompatible ? 0 : 1);
        result.put("contractCompatible", missingContracts.isEmpty());
        result.put("missingContracts", missingContracts);
        result.put("compatible", compatible);
        result.put("message", compatible ? "数据库迁移版本与关键结构契约均兼容"
                : !missingContracts.isEmpty() ? "数据库关键结构缺失：" + String.join("、", missingContracts)
                : tracked ? "数据库结构版本落后，请显式执行 scripts/db_migrate.sh migrate"
                : "数据库尚未建立迁移基线，请显式执行 scripts/db_migrate.sh migrate");
        return result;
    }

    public boolean compatible() {
        return Boolean.TRUE.equals(status().get("compatible"));
    }

    private boolean tableExists(String table) {
        Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema=DATABASE() AND table_name=?", Integer.class, table);
        return value != null && value > 0;
    }

    private String currentVersion() {
        List<String> versions = jdbc.queryForList("SELECT version FROM flyway_schema_history "
                + "WHERE success=1 AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1", String.class);
        return versions.isEmpty() ? null : versions.get(0);
    }

    private List<String> missingContracts() {
        Set<String> existingColumns = new LinkedHashSet<>(jdbc.queryForList(
                "SELECT CONCAT(table_name,'.',column_name) FROM information_schema.columns "
                        + "WHERE table_schema=DATABASE() AND ((table_name='chat_session' AND column_name IN "
                        + "('context_type','context_id','context_title')) OR "
                        + "(table_name='task_lineage_snapshot' AND column_name='lineage_json') OR "
                        + "(table_name='data_map_graph_outbox' AND column_name IN "
                        + "('status','available_at','locked_at')))", String.class));
        Set<String> existingIndexes = new LinkedHashSet<>(jdbc.queryForList(
                "SELECT DISTINCT CONCAT(table_name,'.',index_name) FROM information_schema.statistics "
                        + "WHERE table_schema=DATABASE() AND ((table_name='chat_session' "
                        + "AND index_name='idx_chat_session_context') OR "
                        + "(table_name='data_map_graph_outbox' AND index_name='idx_data_map_outbox_recovery'))",
                String.class));
        List<String> missing = new java.util.ArrayList<>();
        for (String value : REQUIRED_COLUMNS) if (!existingColumns.contains(value)) missing.add(value);
        for (String value : REQUIRED_INDEXES) if (!existingIndexes.contains(value)) missing.add(value);
        java.util.Collections.sort(missing);
        return missing;
    }

    static int compareVersions(String left, String right) {
        if (left == null || left.trim().isEmpty()) return -1;
        String[] a = left.split("[._-]");
        String[] b = right.split("[._-]");
        for (int index = 0; index < Math.max(a.length, b.length); index++) {
            long av = index < a.length ? number(a[index]) : 0;
            long bv = index < b.length ? number(b[index]) : 0;
            if (av != bv) return Long.compare(av, bv);
        }
        return 0;
    }

    private static long number(String value) {
        try { return Long.parseLong(value); }
        catch (NumberFormatException ignored) { return 0L; }
    }
}
