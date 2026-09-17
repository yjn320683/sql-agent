package com.yjn.sqlagent.realtime.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RealtimeTableRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final ObjectMapper mapper;

    public RealtimeTableRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc; this.named = new NamedParameterJdbcTemplate(jdbc); this.mapper = mapper;
    }

    public Map<String, Object> page(Map<String, String> query) {
        int page = positive(query.get("page"), 1), pageSize = Math.min(100, positive(query.get("pageSize"), 20));
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        like(where, params, query.get("keyword"), " AND (r.database_name LIKE :keyword OR r.table_name LIKE :keyword OR r.table_comment LIKE :keyword)", "keyword");
        equal(where, params, query.get("status"), " AND r.physical_status=:status", "status");
        equal(where, params, query.get("source"), " AND r.creation_source=:source", "source");
        String producer = text(query.get("producer"));
        if (!producer.isEmpty()) {
            where.append(" AND (CAST(r.producer_task_id AS CHAR) LIKE :producer OR t.task_name LIKE :producer)");
            params.addValue("producer", "%" + producer + "%");
        }
        String businessDomainId = text(query.get("businessDomainId"));
        if (!businessDomainId.isEmpty()) {
            where.append(" AND EXISTS (SELECT 1 FROM rt_asset_business_domain_relation ar "
                    + "WHERE ar.asset_type='PAIMON' AND ar.realtime_table_id=r.id AND ar.domain_id=:businessDomainId)");
            params.addValue("businessDomainId", businessDomainId);
        }
        Long total = named.queryForObject("SELECT COUNT(*) FROM rt_realtime_table r LEFT JOIN rt_task t ON t.id=r.producer_task_id" + where, params, Long.class);
        params.addValue("limit", pageSize).addValue("offset", (page - 1) * pageSize);
        List<Map<String, Object>> records = named.queryForList("SELECT r.id,r.catalog_name catalogName,r.database_name databaseName,"
                + "r.table_name tableName,r.table_comment tableComment,r.table_type tableType,r.creation_source creationSource,"
                + "r.producer_task_id producerTaskId,t.task_name producerTaskName,r.physical_status physicalStatus,"
                + "r.table_options_json tableOptionsJson,r.last_error lastError,r.last_synced_at lastSyncedAt,"
                + "r.operator,r.create_time createTime,r.update_time updateTime,"
                + "(SELECT COUNT(*) FROM rt_realtime_table_column c WHERE c.realtime_table_id=r.id) columnCount,"
                + "(SELECT COUNT(*) FROM rt_task_table_reference x WHERE x.realtime_table_id=r.id) referenceCount "
                + "FROM rt_realtime_table r LEFT JOIN rt_task t ON t.id=r.producer_task_id" + where
                + " ORDER BY r.update_time DESC,r.id DESC LIMIT :limit OFFSET :offset", params);
        records.forEach(row -> row.put("options", jsonMap(row.remove("tableOptionsJson"))));
        return Map.of("records", records, "total", total == null ? 0 : total, "page", page, "pageSize", pageSize);
    }

    public Map<String, Object> required(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT r.id,r.catalog_name catalogName,r.database_name databaseName,"
                + "r.table_name tableName,r.table_comment tableComment,r.table_type tableType,r.creation_source creationSource,"
                + "r.producer_task_id producerTaskId,t.task_name producerTaskName,r.physical_status physicalStatus,"
                + "r.table_options_json tableOptionsJson,r.last_error lastError,r.last_synced_at lastSyncedAt,"
                + "r.operator,r.create_time createTime,r.update_time updateTime FROM rt_realtime_table r "
                + "LEFT JOIN rt_task t ON t.id=r.producer_task_id WHERE r.id=?", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("实时表不存在：" + id);
        Map<String, Object> result = new LinkedHashMap<>(rows.get(0));
        result.put("options", jsonMap(result.remove("tableOptionsJson")));
        result.put("columns", columns(id)); result.put("dependencies", dependencies(id));
        return result;
    }

    public List<Map<String, Object>> available() {
        return jdbc.queryForList("SELECT r.id,r.database_name databaseName,r.table_name tableName,r.table_type tableType,r.physical_status physicalStatus,"
                + "r.producer_task_id producerTaskId,t.task_name producerTaskName FROM rt_realtime_table r "
                + "LEFT JOIN rt_task t ON t.id=r.producer_task_id WHERE r.physical_status='active' ORDER BY r.database_name,r.table_name");
    }

    public Long findId(String database, String tableName) {
        List<Long> ids = jdbc.query("SELECT id FROM rt_realtime_table WHERE catalog_name='paimon'"
                        + " AND LOWER(database_name)=LOWER(?) AND LOWER(table_name)=LOWER(?)",
                (rs, row) -> rs.getLong(1), database, tableName);
        return ids.isEmpty() ? null : ids.get(0);
    }

    public List<Map<String, Object>> declaredSyncTables() {
        return jdbc.queryForList("SELECT id,database_name databaseName,table_name tableName FROM rt_realtime_table "
                + "WHERE creation_source='sync' AND physical_status IN ('declared','error') ORDER BY update_time LIMIT 100");
    }

    public List<Map<String, Object>> columns(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id,realtime_table_id realtimeTableId,column_name name,data_type dataType,"
                + "nullable_flag nullable,primary_key_flag primaryKey,partition_key_flag partitionKey,column_comment comment,sort_order sortOrder "
                + "FROM rt_realtime_table_column WHERE realtime_table_id=? ORDER BY sort_order,id", id);
        rows.forEach(row -> { row.put("nullable", bool(row.get("nullable"))); row.put("primaryKey", bool(row.get("primaryKey"))); row.put("partitionKey", bool(row.get("partitionKey"))); });
        return rows;
    }

    public List<Map<String, Object>> dependencies(long id) {
        return jdbc.queryForList("SELECT x.task_id taskId,t.task_name taskName,t.task_type taskType,x.reference_role referenceRole,"
                + "t.status FROM rt_task_table_reference x JOIN rt_task t ON t.id=x.task_id "
                + "WHERE x.realtime_table_id=? AND t.status<>'deleted' ORDER BY x.reference_role,t.task_name", id);
    }

    @Transactional
    public long createDeclared(Map<String, Object> request, String actor) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("INSERT INTO rt_realtime_table(catalog_name,database_name,table_name,"
                    + "table_comment,table_type,creation_source,producer_task_id,physical_status,table_options_json,operator) "
                    + "VALUES('paimon',?,?,?,?, 'manual',NULL,'declared',?,?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, text(request.get("databaseName"))); ps.setString(2, text(request.get("tableName")));
            ps.setString(3, text(request.get("comment"))); ps.setString(4, text(request.get("tableType"), "primary_key"));
            ps.setString(5, json(request.getOrDefault("options", Map.of()))); ps.setString(6, actor); return ps;
        }, holder);
        long id = ((Number) holder.getKeys().values().iterator().next()).longValue();
        replaceColumns(id, maps(request.get("columns")));
        return id;
    }

    @Transactional
    public void markPhysical(long id, Map<String, Object> physical, String actor) {
        Map<String, Object> table = required(id);
        jdbc.update("UPDATE rt_realtime_table SET table_comment=?,table_options_json=?,physical_status='active',last_error=NULL,"
                        + "last_synced_at=NOW(),operator=?,update_time=NOW() WHERE id=?",
                text(physical.get("comment"), text(table.get("tableComment"))), json(physical.getOrDefault("options", Map.of())), actor, id);
        replaceColumns(id, maps(physical.get("columns")));
    }

    public void markError(long id, Throwable error, String actor) {
        jdbc.update("UPDATE rt_realtime_table SET physical_status='error',last_error=?,operator=?,update_time=NOW() WHERE id=?",
                safe(error), actor, id);
    }

    @Transactional
    public void updateFromPhysical(long id, Map<String, Object> request, Map<String, Object> physical, String actor) {
        jdbc.update("UPDATE rt_realtime_table SET table_comment=?,table_options_json=?,physical_status='active',last_error=NULL,"
                        + "last_synced_at=NOW(),operator=?,update_time=NOW() WHERE id=?",
                text(physical.get("comment")), json(physical.getOrDefault("options", Map.of())), actor, id);
        replaceColumns(id, maps(physical.get("columns")));
    }

    @Transactional
    public long registerSync(long taskId, String database, String tableName, String actor) {
        List<Long> existing = jdbc.query("SELECT id FROM rt_realtime_table WHERE catalog_name='paimon' AND database_name=? AND table_name=?",
                (rs, row) -> rs.getLong(1), database, tableName);
        long id;
        if (existing.isEmpty()) {
            KeyHolder holder = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement("INSERT INTO rt_realtime_table(catalog_name,database_name,table_name,"
                        + "table_type,creation_source,producer_task_id,physical_status,table_options_json,operator) "
                        + "VALUES('paimon',?,?,'primary_key','sync',?,'declared','{}',?)", Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, database); ps.setString(2, tableName); ps.setLong(3, taskId); ps.setString(4, actor); return ps;
            }, holder);
            id = ((Number) holder.getKeys().values().iterator().next()).longValue();
        } else {
            id = existing.get(0);
            Map<String, Object> row = required(id);
            Object producer = row.get("producerTaskId");
            if (producer != null && ((Number) producer).longValue() != taskId) throw new IllegalStateException("实时表已有生产任务：" + database + "." + tableName);
            jdbc.update("UPDATE rt_realtime_table SET creation_source='sync',producer_task_id=?,operator=?,update_time=NOW() WHERE id=?", taskId, actor, id);
        }
        jdbc.update("INSERT IGNORE INTO rt_task_table_reference(task_id,realtime_table_id,reference_role) VALUES(?,?,'OUTPUT')", taskId, id);
        return id;
    }

    @Transactional
    public void replaceReferences(long taskId, List<Long> inputs, List<Long> outputs) {
        jdbc.update("DELETE FROM rt_task_table_reference WHERE task_id=?", taskId);
        for (Long id : inputs) jdbc.update("INSERT INTO rt_task_table_reference(task_id,realtime_table_id,reference_role) VALUES(?,?,'INPUT')", taskId, id);
        for (Long id : outputs) {
            Map<String, Object> table = required(id);
            Object producer = table.get("producerTaskId");
            if (producer != null && ((Number) producer).longValue() != taskId) throw new IllegalStateException("输出表已绑定其他生产任务：" + table.get("databaseName") + "." + table.get("tableName"));
            jdbc.update("UPDATE rt_realtime_table SET producer_task_id=? WHERE id=?", taskId, id);
            jdbc.update("INSERT INTO rt_task_table_reference(task_id,realtime_table_id,reference_role) VALUES(?,?,'OUTPUT')", taskId, id);
        }
    }

    @Transactional
    public void releaseTask(long taskId) {
        jdbc.update("UPDATE rt_realtime_table SET producer_task_id=NULL WHERE producer_task_id=? AND creation_source='manual'", taskId);
        jdbc.update("DELETE FROM rt_task_table_reference WHERE task_id=?", taskId);
    }

    private void replaceColumns(long tableId, List<Map<String, Object>> columns) {
        jdbc.update("DELETE FROM rt_realtime_table_column WHERE realtime_table_id=?", tableId);
        int order = 0;
        for (Map<String, Object> column : columns) {
            jdbc.update("INSERT INTO rt_realtime_table_column(realtime_table_id,column_name,data_type,nullable_flag,primary_key_flag,"
                            + "partition_key_flag,column_comment,sort_order) VALUES(?,?,?,?,?,?,?,?)", tableId,
                    text(column.get("name")), text(column.get("dataType")), boolDefault(column.get("nullable"), true),
                    bool(column.get("primaryKey")), bool(column.get("partitionKey")), text(column.get("comment")), order++);
        }
    }

    private void like(StringBuilder where, MapSqlParameterSource params, String value, String sql, String name) {
        if (!text(value).isEmpty()) { where.append(sql); params.addValue(name, "%" + text(value) + "%"); }
    }
    private void equal(StringBuilder where, MapSqlParameterSource params, String value, String sql, String name) {
        if (!text(value).isEmpty() && !"all".equalsIgnoreCase(text(value))) { where.append(sql); params.addValue(name, text(value)); }
    }
    private int positive(String value, int fallback) { try { int result=Integer.parseInt(text(value)); return result>0?result:fallback; } catch(Exception ex){ return fallback; } }
    private boolean bool(Object value) { return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(text(value)) || "1".equals(text(value)); }
    private boolean boolDefault(Object value, boolean fallback) { return value == null ? fallback : bool(value); }
    private String safe(Throwable error) { String result=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage(); return result.replaceAll("(?i)(password=)[^&\\s]+", "$1******"); }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch(Exception ex){ throw new IllegalStateException(ex); } }
    private Map<String, Object> jsonMap(Object value) { try { return value==null?new LinkedHashMap<>():mapper.readValue(String.valueOf(value), new TypeReference<Map<String,Object>>(){}); } catch(Exception ex){ return new LinkedHashMap<>(); } }
    @SuppressWarnings("unchecked") private List<Map<String, Object>> maps(Object value) { return value instanceof List ? (List<Map<String,Object>>) value : List.of(); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private String text(Object value, String fallback) { String result=text(value); return result.isEmpty()?fallback:result; }
}
