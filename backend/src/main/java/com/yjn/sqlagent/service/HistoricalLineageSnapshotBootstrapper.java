package com.yjn.sqlagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjn.sqlagent.datamap.project.LineageSnapshotBootstrapper;
import com.yjn.sqlagent.datamap.store.LineageOutboxService;
import com.yjn.sqlagent.realtime.repository.LineageRelationRepository;
import com.yjn.sqlagent.realtime.service.ManagedSqlAnalyzer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

/** 分批为升级前已存在的离线和实时任务生成当前版本血缘快照。 */
@Service
public class HistoricalLineageSnapshotBootstrapper implements LineageSnapshotBootstrapper {
    private static final Logger LOG = LoggerFactory.getLogger(HistoricalLineageSnapshotBootstrapper.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<Map<String, Object>>() { };
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TaskSqlStructureService offlineAnalyzer;
    private final TaskLineageSnapshotService offlineSnapshots;
    private final ManagedSqlAnalyzer realtimeAnalyzer;
    private final LineageRelationRepository relationRepository;
    private final LineageOutboxService outbox;

    public HistoricalLineageSnapshotBootstrapper(JdbcTemplate jdbc, ObjectMapper mapper,
            TaskSqlStructureService offlineAnalyzer, TaskLineageSnapshotService offlineSnapshots,
            ManagedSqlAnalyzer realtimeAnalyzer, LineageRelationRepository relationRepository,
            LineageOutboxService outbox) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.offlineAnalyzer = offlineAnalyzer;
        this.offlineSnapshots = offlineSnapshots;
        this.realtimeAnalyzer = realtimeAnalyzer;
        this.relationRepository = relationRepository;
        this.outbox = outbox;
    }

    @Override
    public int backfill(int limit) {
        int safeLimit = Math.max(1, Math.min(100, limit));
        int offlineLimit = Math.max(1, safeLimit / 2);
        int completed = backfillOffline(offlineLimit);
        return completed + backfillRealtime(Math.max(1, safeLimit - completed));
    }

    private int backfillOffline(int limit) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT t.id taskId,t.sql_content sqlContent,t.sql_checksum sqlChecksum,"
                        + "COALESCE(t.effective_version_no,0) versionNo,v.id versionId "
                        + "FROM sql_task t LEFT JOIN sql_task_version v ON v.task_id=t.id "
                        + "AND v.version_no=t.effective_version_no WHERE t.archived=0 AND NOT EXISTS ("
                        + "SELECT 1 FROM task_lineage_snapshot s WHERE s.task_scope='OFFLINE' "
                        + "AND s.task_id=t.id AND s.version_no=COALESCE(t.effective_version_no,0) "
                        + "AND (s.sql_checksum=t.sql_checksum OR (s.sql_checksum IS NULL "
                        + "AND t.sql_checksum IS NULL)) AND s.default_database='default') "
                        + "ORDER BY t.id LIMIT ?", limit);
        int completed = 0;
        for (Map<String, Object> row : rows) {
            long taskId = number(row.get("taskId"));
            try {
                TaskLineageFacts facts;
                try {
                    facts = offlineAnalyzer.analyzeLineage(text(row.get("sqlContent")), "default");
                } catch (RuntimeException error) {
                    facts = TaskLineageFacts.failure("HISTORICAL_PARSE_FAILED", safe(error));
                }
                offlineSnapshots.saveOffline(taskId, nullableLong(row.get("versionId")),
                        integer(row.get("versionNo")), text(row.get("sqlChecksum")),
                        "default", "BACKFILLED", facts);
                completed++;
            } catch (RuntimeException error) {
                LOG.warn("离线任务 {} 历史血缘回填失败：{}", taskId, safe(error));
            }
        }
        return completed;
    }

    private int backfillRealtime(int limit) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT t.id taskId,t.task_type taskType,v.id versionId,v.version_no versionNo,v.config "
                        + "FROM rt_task t JOIN rt_task_version v ON v.id=(SELECT v2.id FROM rt_task_version v2 "
                        + "WHERE v2.task_id=t.id ORDER BY v2.version_no DESC,v2.id DESC LIMIT 1) "
                        + "WHERE t.status<>'deleted' AND NOT EXISTS (SELECT 1 FROM task_lineage_snapshot s "
                        + "WHERE s.task_scope='REALTIME' AND s.task_id=t.id AND s.version_no=v.version_no) "
                        + "ORDER BY t.id LIMIT ?", limit);
        int completed = 0;
        for (Map<String, Object> row : rows) {
            long taskId = number(row.get("taskId"));
            try {
                String configJson = text(row.get("config"));
                SnapshotDraft draft = realtimeDraft(taskId, text(row.get("taskType")), configJson);
                insertRealtime(taskId, number(row.get("versionId")), integer(row.get("versionNo")),
                        sha256(configJson), draft);
                completed++;
            } catch (RuntimeException error) {
                LOG.warn("实时任务 {} 历史血缘回填失败：{}", taskId, safe(error));
            }
        }
        return completed;
    }

    private SnapshotDraft realtimeDraft(long taskId, String taskType, String configJson) {
        try {
            Map<String, Object> config = mapper.readValue(configJson, MAP);
            if ("compute".equalsIgnoreCase(taskType)) return computeDraft(config);
            if ("sync".equalsIgnoreCase(taskType)) return mappingDraft(taskId, "sync");
            if ("export".equalsIgnoreCase(taskType)) return mappingDraft(taskId, "export");
            return failureDraft("default", "UNSUPPORTED_REALTIME_TASK", "不支持的实时任务类型：" + taskType);
        } catch (Exception error) {
            return failureDraft("default", "HISTORICAL_CONFIG_INVALID", safe(error));
        }
    }

    private SnapshotDraft computeDraft(Map<String, Object> request) {
        Map<String, Object> taskConfig = map(request.get("taskConfig"));
        Map<String, Object> compute = map(taskConfig.get("computeConfig"));
        String database = defaultDatabase(compute.get("defaultDatabase"));
        try {
            Map<String, Object> facts = new LinkedHashMap<>(realtimeAnalyzer
                    .analyze(text(compute.get("sql")), database).getLineageFacts());
            return new SnapshotDraft(database, facts, bool(facts.get("complete")), diagnostics(facts));
        } catch (RuntimeException error) {
            return failureDraft(database, "HISTORICAL_PARSE_FAILED", safe(error));
        }
    }

    private SnapshotDraft mappingDraft(long taskId, String type) {
        List<Map<String, Object>> rows;
        if ("sync".equals(type)) {
            rows = jdbc.queryForList("SELECT source_database sourceDatabase,source_table sourceTable,"
                    + "target_database targetDatabase,target_table targetTable "
                    + "FROM rt_sync_task_table_mapping WHERE task_id=? ORDER BY sort_order,id", taskId);
        } else {
            rows = jdbc.queryForList("SELECT r.database_name sourceDatabase,r.table_name sourceTable,"
                    + "m.target_database targetDatabase,m.target_table targetTable "
                    + "FROM rt_export_task_table_mapping m JOIN rt_realtime_table r "
                    + "ON r.id=m.realtime_table_id WHERE m.task_id=? ORDER BY m.sort_order,m.id", taskId);
        }
        List<Map<String, Object>> inputs = new ArrayList<>(), outputs = new ArrayList<>();
        String inputCatalog = "sync".equals(type) ? "mysql" : "paimon";
        String outputCatalog = "sync".equals(type) ? "paimon" : "mysql";
        for (Map<String, Object> row : rows) {
            inputs.add(table(inputCatalog, text(row.get("sourceDatabase")), text(row.get("sourceTable"))));
            outputs.add(table(outputCatalog, text(row.get("targetDatabase")), text(row.get("targetTable"))));
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("statementCount", rows.size());
        facts.put("inputs", inputs);
        facts.put("outputs", outputs);
        facts.put("statements", Collections.emptyList());
        facts.put("diagnostics", Collections.emptyList());
        facts.put("complete", !rows.isEmpty());
        String database = rows.isEmpty() ? "default" : defaultDatabase(rows.get(0).get("targetDatabase"));
        return rows.isEmpty()
                ? failureDraft(database, "HISTORICAL_MAPPING_MISSING", "任务没有可用于回填的表映射")
                : new SnapshotDraft(database, facts, true, Collections.emptyList());
    }

    private void insertRealtime(long taskId, long versionId, int versionNo,
                                String checksum, SnapshotDraft draft) {
        KeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO task_lineage_snapshot(task_scope,task_id,version_id,version_no,sql_checksum,"
                                + "dialect,default_database,parser_version,snapshot_source,complete_flag,lineage_json,"
                                + "diagnostics_json) VALUES('REALTIME',?,?,?,?,'FLINK',?,'parse-sql-v2',"
                                + "'BACKFILLED',?,?,?)", Statement.RETURN_GENERATED_KEYS);
                statement.setLong(1, taskId);
                statement.setLong(2, versionId);
                statement.setInt(3, versionNo);
                statement.setString(4, checksum);
                statement.setString(5, draft.database);
                statement.setBoolean(6, draft.complete);
                statement.setString(7, json(draft.facts));
                statement.setString(8, json(draft.diagnostics));
                return statement;
            }, keys);
        } catch (DuplicateKeyException ignored) {
            return;
        }
        Number snapshotId = keys.getKey();
        if (snapshotId == null) throw new IllegalStateException("实时任务血缘快照未返回主键");
        relationRepository.index(snapshotId.longValue(), "REALTIME", taskId,
                versionId, versionNo, draft.facts);
        outbox.enqueue(snapshotId.longValue(), "REALTIME", taskId, versionId, versionNo);
    }

    private SnapshotDraft failureDraft(String database, String code, String message) {
        Map<String, Object> diagnostic = new LinkedHashMap<>();
        diagnostic.put("code", code);
        diagnostic.put("severity", "ERROR");
        diagnostic.put("message", message);
        List<Map<String, Object>> diagnostics = Collections.singletonList(diagnostic);
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("statementCount", 0);
        facts.put("inputs", Collections.emptyList());
        facts.put("outputs", Collections.emptyList());
        facts.put("statements", Collections.emptyList());
        facts.put("diagnostics", diagnostics);
        facts.put("complete", false);
        return new SnapshotDraft(defaultDatabase(database), facts, false, diagnostics);
    }

    private Map<String, Object> table(String catalog, String database, String table) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("catalog", catalog);
        result.put("db", database);
        result.put("table", table);
        result.put("qualifiedName", catalog + "." + database + "." + table);
        result.put("dynamic", false);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map ? new LinkedHashMap<>((Map<String, Object>) value) : new LinkedHashMap<>();
    }
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> diagnostics(Map<String, Object> facts) {
        Object value = facts.get("diagnostics");
        return value instanceof List ? new ArrayList<>((List<Map<String, Object>>) value) : new ArrayList<>();
    }
    private boolean bool(Object value) { return Boolean.TRUE.equals(value); }
    private String defaultDatabase(Object value) {
        String result = text(value);
        return result.isEmpty() ? "default" : result;
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException("血缘快照无法序列化", error); }
    }
    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception error) { throw new IllegalStateException("无法计算配置校验和", error); }
    }
    private long number(Object value) { return ((Number) value).longValue(); }
    private int integer(Object value) { return ((Number) value).intValue(); }
    private Long nullableLong(Object value) { return value instanceof Number ? ((Number) value).longValue() : null; }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private String safe(Throwable error) {
        String value = error.getMessage();
        return value == null || value.trim().isEmpty() ? error.getClass().getSimpleName() : value.trim();
    }

    private static final class SnapshotDraft {
        private final String database;
        private final Map<String, Object> facts;
        private final boolean complete;
        private final List<Map<String, Object>> diagnostics;
        private SnapshotDraft(String database, Map<String, Object> facts, boolean complete,
                              List<Map<String, Object>> diagnostics) {
            this.database = database;
            this.facts = facts;
            this.complete = complete;
            this.diagnostics = diagnostics;
        }
    }
}
