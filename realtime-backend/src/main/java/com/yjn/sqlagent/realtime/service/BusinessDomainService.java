package com.yjn.sqlagent.realtime.service;

import com.yjn.sqlagent.realtime.repository.BusinessDomainRepository;
import com.yjn.sqlagent.realtime.repository.RealtimeTableRepository;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class BusinessDomainService {
    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9_]{0,31}");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$-]{0,127}");
    private final BusinessDomainRepository repository;
    private final RealtimeTableRepository tables;

    public BusinessDomainService(BusinessDomainRepository repository, RealtimeTableRepository tables) {
        this.repository = repository; this.tables = tables;
    }

    public Map<String, Object> create(Map<String, Object> body) {
        validateDomain(body, true);
        return repository.required(repository.create(body));
    }

    public Map<String, Object> update(long id, Map<String, Object> body) {
        repository.required(id);
        validateDomain(body, false);
        repository.update(id, body);
        return repository.required(id);
    }

    public Map<String, Object> status(long id, boolean enabled) {
        repository.required(id);
        repository.status(id, enabled);
        return repository.required(id);
    }

    public Map<String, Object> assignment(String assetType, String catalog, String database, String table) {
        Asset asset = asset(assetType, catalog, database, table, null);
        return repository.asset(asset.type, asset.key);
    }

    public Map<String, Object> assign(Map<String, Object> body, String actor) {
        Long realtimeTableId = longValue(body.get("realtimeTableId"));
        Asset asset = asset(text(body.get("assetType")), text(body.get("catalogName")),
                text(body.get("databaseName")), text(body.get("tableName")), realtimeTableId);
        long domainId = requiredLong(body.get("domainId"), "业务域");
        Map<String, Object> domain = repository.required(domainId);
        if (!enabled(domain.get("enabled"))) throw new IllegalStateException("停用的业务域不能新增资产关联");
        repository.assign(asset.type, asset.key, asset.realtimeTableId, asset.catalog,
                asset.database, asset.table, domainId, actor);
        return repository.asset(asset.type, asset.key);
    }

    public void unassign(String assetType, String catalog, String database, String table) {
        Asset asset = asset(assetType, catalog, database, table, null);
        repository.unassign(asset.type, asset.key);
    }

    private Asset asset(String assetType, String catalog, String database, String table, Long realtimeTableId) {
        String type = text(assetType).toUpperCase(Locale.ROOT);
        if (!"HIVE".equals(type) && !"PAIMON".equals(type)) throw new IllegalArgumentException("资产类型必须是 HIVE 或 PAIMON");
        String db = text(database); String name = text(table); String cat = text(catalog);
        if (!IDENTIFIER.matcher(db).matches() || !IDENTIFIER.matcher(name).matches()) throw new IllegalArgumentException("资产库表标识不正确");
        Long managedId = realtimeTableId;
        if ("PAIMON".equals(type) && realtimeTableId == null) {
            throw new IllegalArgumentException("Paimon 资产必须引用实时表管理中的表");
        }
        if ("PAIMON".equals(type)) {
            Map<String, Object> managed = tables.required(realtimeTableId);
            db = text(managed.get("databaseName")); name = text(managed.get("tableName"));
            cat = text(managed.get("catalogName")); if (cat.isEmpty()) cat = "paimon";
        }
        if (cat.isEmpty()) cat = "HIVE".equals(type) ? "hive" : "paimon";
        String key = (cat + "." + db + "." + name).toLowerCase(Locale.ROOT);
        return new Asset(type, key, cat, db, name, managedId);
    }

    private void validateDomain(Map<String, Object> body, boolean requireCode) {
        if (requireCode && !CODE.matcher(text(body.get("code"))).matches()) throw new IllegalArgumentException("业务域编码必须以小写字母开头，只能包含小写字母、数字和下划线，最长32位");
        String name = text(body.get("name")); if (name.isEmpty() || name.length() > 64) throw new IllegalArgumentException("业务域名称长度必须为1至64位");
        if (text(body.get("description")).length() > 512) throw new IllegalArgumentException("业务域说明不能超过512位");
        if (text(body.get("owner")).length() > 64) throw new IllegalArgumentException("业务域负责人不能超过64位");
    }

    private long requiredLong(Object value, String label) { Long result=longValue(value); if(result==null||result<=0)throw new IllegalArgumentException(label+"不能为空"); return result; }
    private Long longValue(Object value) { try { return value==null?null:Long.parseLong(String.valueOf(value)); } catch(Exception ignored) { return null; } }
    private boolean enabled(Object value) { return value instanceof Boolean ? (Boolean) value : value instanceof Number && ((Number) value).intValue() == 1; }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private static final class Asset { private final String type,key,catalog,database,table;private final Long realtimeTableId;private Asset(String type,String key,String catalog,String database,String table,Long realtimeTableId){this.type=type;this.key=key;this.catalog=catalog;this.database=database;this.table=table;this.realtimeTableId=realtimeTableId;} }
}
