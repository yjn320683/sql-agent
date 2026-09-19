package com.yjn.sqlagent.realtime.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 将不可变血缘快照投影为可索引的表、字段关系；不重新解析 SQL。 */
@Repository
public class LineageRelationRepository {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<Map<String, Object>>() { };
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public LineageRelationRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public void index(long snapshotId, String taskScope, long taskId, Long versionId,
                      int versionNo, Map<String, Object> facts) {
        for (Relation relation : project(facts)) insert(snapshotId, taskScope, taskId, versionId, versionNo, relation);
    }

    /** 历史快照分批回填；只读取已有 JSON，避免重新访问 Hive/Paimon。 */
    @Scheduled(initialDelayString = "${app.lineage.index-initial-delay-ms:30000}",
            fixedDelayString = "${app.lineage.index-delay-ms:300000}")
    public void backfill() {
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList("SELECT s.id,s.task_scope taskScope,s.task_id taskId,s.version_id versionId,"
                    + "s.version_no versionNo,s.lineage_json lineageJson FROM task_lineage_snapshot s "
                    + "WHERE NOT EXISTS (SELECT 1 FROM task_lineage_relation r WHERE r.snapshot_id=s.id "
                    + "AND r.relation_kind='SNAPSHOT') ORDER BY s.id LIMIT 200");
        } catch (RuntimeException ignored) {
            return; // 升级 SQL 尚未执行时不影响服务启动。
        }
        for (Map<String, Object> row : rows) {
            try {
                Map<String, Object> facts = mapper.readValue(String.valueOf(row.get("lineageJson")), MAP);
                index(number(row.get("id")), text(row.get("taskScope")), number(row.get("taskId")),
                        nullableNumber(row.get("versionId")), ((Number) row.get("versionNo")).intValue(), facts);
            } catch (Exception ignored) {
                // 损坏快照由查询完整性状态暴露，不能阻塞其他快照回填。
            }
        }
    }

    private List<Relation> project(Map<String, Object> facts) {
        List<Relation> relations = new ArrayList<>();
        for (Map<String, Object> table : maps(facts.get("inputs"))) {
            relations.add(new Relation("TABLE_INPUT", table(table, null), null, null, null));
        }
        for (Map<String, Object> table : maps(facts.get("outputs"))) {
            relations.add(new Relation("TABLE_OUTPUT", null, table(table, null), null, null));
        }
        for (Map<String, Object> statement : maps(facts.get("statements"))) {
            Integer statementIndex = integer(statement.get("statementIndex"));
            for (Map<String, Object> lineage : maps(statement.get("columnLineages"))) {
                Endpoint target = table(map(lineage.get("targetTable")), textOrNull(lineage.get("targetColumn")));
                for (Map<String, Object> source : maps(lineage.get("sources"))) {
                    relations.add(new Relation("COLUMN_DERIVATION", table(source, textOrNull(source.get("column"))),
                            target, null, boolObject(source.get("direct")), statementIndex));
                }
            }
            for (Map<String, Object> usage : maps(statement.get("columnUsages"))) {
                for (Map<String, Object> column : maps(usage.get("columns"))) {
                    relations.add(new Relation("COLUMN_USAGE", table(column, textOrNull(column.get("column"))),
                            null, textOrNull(usage.get("type")), null, statementIndex));
                }
            }
        }
        // 完成标记必须最后写入；历史回填中途失败时，下次调度仍会继续补齐。
        relations.add(new Relation("SNAPSHOT", null, null, null, null));
        return relations;
    }

    private void insert(long snapshotId, String scope, long taskId, Long versionId, int versionNo, Relation value) {
        String signature = sha256(value.canonical());
        jdbc.update("INSERT IGNORE INTO task_lineage_relation(snapshot_id,relation_signature,task_scope,task_id,version_id,"
                        + "version_no,statement_index,relation_kind,source_catalog,source_database,source_table,source_column,"
                        + "target_catalog,target_database,target_table,target_column,usage_type,direct_flag) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                snapshotId, signature, scope, taskId, versionId, versionNo, value.statementIndex, value.kind,
                value.source == null ? null : value.source.catalog,
                value.source == null ? null : value.source.database,
                value.source == null ? null : value.source.table,
                value.source == null ? null : value.source.column,
                value.target == null ? null : value.target.catalog,
                value.target == null ? null : value.target.database,
                value.target == null ? null : value.target.table,
                value.target == null ? null : value.target.column,
                value.usageType, value.direct);
    }

    private Endpoint table(Map<String, Object> value, String column) {
        if (value == null || value.isEmpty()) return null;
        String database = textOrNull(value.containsKey("database") ? value.get("database") : value.get("db"));
        String table = textOrNull(value.get("table"));
        if (table == null) return null;
        return new Endpoint(textOrNull(value.get("catalog")), database, table, column);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map ? new LinkedHashMap<>((Map<String, Object>) value) : new LinkedHashMap<>();
    }
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) { return value instanceof List ? (List<Map<String, Object>>) value : List.of(); }
    private Integer integer(Object value) { return value instanceof Number ? ((Number) value).intValue() : null; }
    private Boolean boolObject(Object value) { return value == null ? null : Boolean.valueOf(String.valueOf(value)); }
    private long number(Object value) { return ((Number) value).longValue(); }
    private Long nullableNumber(Object value) { return value instanceof Number ? ((Number) value).longValue() : null; }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private String textOrNull(Object value) { String result = text(value); return result.isEmpty() ? null : result; }
    private String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : bytes) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    private static final class Endpoint {
        private final String catalog, database, table, column;
        private Endpoint(String catalog, String database, String table, String column) {
            this.catalog = catalog; this.database = database; this.table = table; this.column = column;
        }
        private String canonical() { return String.join("|", value(catalog), value(database), value(table), value(column)); }
        private static String value(String value) { return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT); }
    }
    private static final class Relation {
        private final String kind; private final Endpoint source, target; private final String usageType;
        private final Boolean direct; private final Integer statementIndex;
        private Relation(String kind, Endpoint source, Endpoint target, String usageType, Boolean direct) {
            this(kind, source, target, usageType, direct, null);
        }
        private Relation(String kind, Endpoint source, Endpoint target, String usageType, Boolean direct, Integer statementIndex) {
            this.kind = kind; this.source = source; this.target = target; this.usageType = usageType;
            this.direct = direct; this.statementIndex = statementIndex;
        }
        private String canonical() {
            return String.join("#", kind, source == null ? "" : source.canonical(), target == null ? "" : target.canonical(),
                    usageType == null ? "" : usageType, direct == null ? "" : String.valueOf(direct),
                    statementIndex == null ? "" : String.valueOf(statementIndex));
        }
    }
}
