package com.yjn.sqlagent.realtime.repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 复用 rt_paimon_business_domain 的统一资产域仓储。 */
@Repository
public class BusinessDomainRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public BusinessDomainRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    public Map<String, Object> page(Map<String, String> query) {
        int page = positive(query.get("page"), 1);
        int size = Math.min(100, positive(query.get("pageSize"), 20));
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        String keyword = text(query.get("keyword"));
        if (!keyword.isEmpty()) {
            where.append(" AND (domain_code LIKE :keyword OR domain_name LIKE :keyword OR COALESCE(description,'') LIKE :keyword)");
            params.addValue("keyword", "%" + keyword + "%");
        }
        String enabled = text(query.get("enabled"));
        if ("true".equalsIgnoreCase(enabled) || "false".equalsIgnoreCase(enabled)) {
            where.append(" AND enabled_flag=:enabled");
            params.addValue("enabled", Boolean.parseBoolean(enabled));
        }
        Long total = named.queryForObject("SELECT COUNT(*) FROM rt_paimon_business_domain" + where, params, Long.class);
        params.addValue("limit", size).addValue("offset", (page - 1) * size);
        List<Map<String, Object>> records = named.queryForList(
                "SELECT d.id,d.domain_code code,d.domain_name name,d.description,d.owner,d.sort_order sortOrder,"
                        + "d.enabled_flag enabled,d.disabled_time disabledTime,d.create_time createTime,d.update_time updateTime,"
                        + "(SELECT COUNT(*) FROM rt_asset_business_domain_relation r WHERE r.domain_id=d.id) assetCount "
                        + "FROM rt_paimon_business_domain d" + where
                        + " ORDER BY d.sort_order,d.id LIMIT :limit OFFSET :offset", params);
        return Map.of("records", records, "total", total == null ? 0 : total,
                "pageNo", page, "pageSize", size);
    }

    public List<Map<String, Object>> enabledOptions() {
        return jdbc.queryForList("SELECT id,domain_code code,domain_name name,description,owner,sort_order sortOrder "
                + "FROM rt_paimon_business_domain WHERE enabled_flag=1 ORDER BY sort_order,id");
    }

    public Map<String, Object> required(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,domain_code code,domain_name name,description,owner,sort_order sortOrder,"
                        + "enabled_flag enabled,disabled_time disabledTime,create_time createTime,update_time updateTime "
                        + "FROM rt_paimon_business_domain WHERE id=?", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("业务域不存在：" + id);
        return rows.get(0);
    }

    public long create(Map<String, Object> value) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO rt_paimon_business_domain(domain_code,domain_name,description,owner,sort_order,enabled_flag) "
                            + "VALUES(?,?,?,?,?,1)", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, text(value.get("code")));
            statement.setString(2, text(value.get("name")));
            statement.setString(3, nullable(value.get("description")));
            statement.setString(4, nullable(value.get("owner")));
            statement.setInt(5, integer(value.get("sortOrder"), 0));
            return statement;
        }, holder);
        return ((Number) holder.getKeys().values().iterator().next()).longValue();
    }

    public void update(long id, Map<String, Object> value) {
        int count = jdbc.update("UPDATE rt_paimon_business_domain SET domain_name=?,description=?,owner=?,sort_order=?,update_time=NOW() WHERE id=?",
                text(value.get("name")), nullable(value.get("description")), nullable(value.get("owner")),
                integer(value.get("sortOrder"), 0), id);
        if (count == 0) throw new IllegalArgumentException("业务域不存在：" + id);
    }

    public void status(long id, boolean enabled) {
        int count = jdbc.update("UPDATE rt_paimon_business_domain SET enabled_flag=?,disabled_time=IF(?,NULL,NOW()),update_time=NOW() WHERE id=?",
                enabled, enabled, id);
        if (count == 0) throw new IllegalArgumentException("业务域不存在：" + id);
    }

    public Map<String, Object> asset(String type, String key) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT r.id,r.asset_type assetType,r.asset_key assetKey,r.realtime_table_id realtimeTableId,"
                        + "r.catalog_name catalogName,r.database_name databaseName,r.table_name tableName,"
                        + "r.domain_id domainId,d.domain_code domainCode,d.domain_name domainName,d.enabled_flag domainEnabled,"
                        + "r.updated_by updatedBy,r.update_time updateTime "
                        + "FROM rt_asset_business_domain_relation r JOIN rt_paimon_business_domain d ON d.id=r.domain_id "
                        + "WHERE r.asset_type=? AND r.asset_key=?", type, key);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    public Map<String, Object> assetPage(long domainId, String type, String database, String keyword,
                                         int page, int pageSize) {
        int size = Math.min(100, Math.max(1, pageSize));
        int current = Math.max(1, page);
        String typeFilter = text(type).toUpperCase(Locale.ROOT);
        String databaseFilter = text(database);
        String keywordFilter = text(keyword);
        StringBuilder where = new StringBuilder(" WHERE domain_id=:domainId");
        MapSqlParameterSource params = new MapSqlParameterSource("domainId", domainId);
        if (!typeFilter.isEmpty()) { where.append(" AND asset_type=:assetType"); params.addValue("assetType", typeFilter); }
        if (!databaseFilter.isEmpty()) { where.append(" AND database_name=:databaseName"); params.addValue("databaseName", databaseFilter); }
        if (!keywordFilter.isEmpty()) { where.append(" AND (table_name LIKE :keyword OR asset_key LIKE :keyword)"); params.addValue("keyword", "%" + keywordFilter + "%"); }
        Integer total = named.queryForObject("SELECT COUNT(*) FROM rt_asset_business_domain_relation" + where,
                params, Integer.class);
        params.addValue("limit", size).addValue("offset", (current - 1) * size);
        List<Map<String, Object>> records = named.queryForList(
                "SELECT id,asset_type assetType,asset_key assetKey,realtime_table_id realtimeTableId,"
                        + "catalog_name catalogName,database_name databaseName,table_name tableName,updated_by updatedBy,update_time updateTime "
                        + "FROM rt_asset_business_domain_relation" + where
                        + " ORDER BY update_time DESC LIMIT :limit OFFSET :offset", params);
        return Map.of("records", records, "total", total == null ? 0 : total,
                "pageNo", current, "pageSize", size);
    }

    @Transactional
    public void assign(String type, String key, Long realtimeTableId, String catalog, String database,
                       String table, long domainId, String actor) {
        jdbc.update("INSERT INTO rt_asset_business_domain_relation(asset_type,asset_key,asset_key_hash,realtime_table_id,catalog_name,database_name,table_name,domain_id,updated_by) "
                        + "VALUES(?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE realtime_table_id=VALUES(realtime_table_id),"
                        + "catalog_name=VALUES(catalog_name),database_name=VALUES(database_name),table_name=VALUES(table_name),"
                        + "domain_id=VALUES(domain_id),updated_by=VALUES(updated_by),update_time=NOW()",
                type, key, sha256(key), realtimeTableId, nullable(catalog), database, table, domainId, actor);
    }

    public void unassign(String type, String key) {
        jdbc.update("DELETE FROM rt_asset_business_domain_relation WHERE asset_type=? AND asset_key=?", type, key);
    }

    private int positive(String value, int fallback) { try { int parsed=Integer.parseInt(text(value)); return parsed>0?parsed:fallback; } catch(Exception ignored) { return fallback; } }
    private int integer(Object value, int fallback) { try { return Integer.parseInt(text(value)); } catch(Exception ignored) { return fallback; } }
    private String nullable(Object value) { String result=text(value); return result.isEmpty()?null:result; }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : bytes) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception error) {
            throw new IllegalStateException("无法计算资产唯一键", error);
        }
    }
}
