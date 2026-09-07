package com.yjn.sqlagent.realtime.service;

import com.yjn.sqlagent.realtime.repository.RealtimeTableRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

@Service
public class RealtimeTableService {
    private final RealtimeTableRepository repository;
    private final RealtimePaimonCatalogService catalog;

    public RealtimeTableService(RealtimeTableRepository repository, RealtimePaimonCatalogService catalog) {
        this.repository = repository; this.catalog = catalog;
    }

    public Map<String, Object> create(Map<String, Object> request, String actor) {
        String database = text(request.get("databaseName"));
        String tableName = text(request.get("tableName"));
        if (repository.findId(database, tableName) != null) {
            throw new IllegalStateException("实时表已登记：" + database + "." + tableName);
        }
        long id = repository.createDeclared(request, actor);
        try { repository.markPhysical(id, catalog.create(request), actor); }
        catch (RuntimeException ex) { repository.markError(id, ex, actor); throw ex; }
        return repository.required(id);
    }

    public Map<String, Object> refresh(long id, String actor) {
        Map<String, Object> table = repository.required(id);
        try {
            Map<String, Object> physical = catalog.describe(text(table.get("databaseName")), text(table.get("tableName")));
            repository.markPhysical(id, physical, actor); return repository.required(id);
        } catch (RuntimeException ex) { repository.markError(id, ex, actor); throw ex; }
    }

    public Map<String, Object> safeUpdate(long id, Map<String, Object> request, String actor) {
        Map<String, Object> table = repository.required(id);
        if ("sync".equals(table.get("creationSource")) && request.containsKey("addColumns")) {
            throw new IllegalStateException("同步任务维护的表结构只能由同步任务演进");
        }
        Map<String, Object> physical = catalog.safeAlter(text(table.get("databaseName")), text(table.get("tableName")), request);
        repository.updateFromPhysical(id, request, physical, actor); return repository.required(id);
    }

    /** 仅供同步 Schema 演进流程调用；依然只允许 Paimon Catalog 支持的安全增量变更。 */
    public Map<String, Object> applySyncEvolution(long id, Map<String, Object> request, String actor) {
        Map<String, Object> table = repository.required(id);
        if (!"sync".equals(table.get("creationSource"))) {
            throw new IllegalStateException("该表不是同步任务维护的实时表");
        }
        if (!request.containsKey("addColumns") || maps(request.get("addColumns")).isEmpty()) {
            throw new IllegalArgumentException("Schema 演进仅支持新增字段");
        }
        Map<String, Object> physical = catalog.safeAlter(text(table.get("databaseName")), text(table.get("tableName")), request);
        repository.updateFromPhysical(id, request, physical, actor);
        return repository.required(id);
    }

    public Map<String, Object> detail(long id) {
        Map<String, Object> result = new LinkedHashMap<>(repository.required(id));
        try {
            Map<String, Object> physical = catalog.describe(text(result.get("databaseName")), text(result.get("tableName")));
            result.put("columns", physical.getOrDefault("columns", result.get("columns")));
            result.put("options", physical.getOrDefault("options", result.get("options")));
            String physicalComment = text(physical.get("comment"));
            if (!physicalComment.isEmpty()) result.put("tableComment", physicalComment);
        } catch (RuntimeException ex) {
            result.put("ddlError", ex.getMessage());
        }
        result.put("ddl", createTableDdl(result));
        return result;
    }

    String createTableDdl(Map<String, Object> table) {
        List<Map<String, Object>> columns = maps(table.get("columns"));
        if (columns.isEmpty()) return "";
        List<String> definitions = new ArrayList<>();
        List<String> primaryKeys = new ArrayList<>();
        List<String> partitionKeys = new ArrayList<>();
        for (Map<String, Object> column : columns) {
            String name = text(column.get("name"));
            StringBuilder definition = new StringBuilder("  ").append(quoteIdentifier(name)).append(' ')
                    .append(text(column.get("dataType")));
            if (!boolDefault(column.get("nullable"), true)) definition.append(" NOT NULL");
            String comment = text(column.get("comment"));
            if (!comment.isEmpty()) definition.append(" COMMENT '").append(quoteLiteral(comment)).append('\'');
            definitions.add(definition.toString());
            if (bool(column.get("primaryKey"))) primaryKeys.add(quoteIdentifier(name));
            if (bool(column.get("partitionKey"))) partitionKeys.add(quoteIdentifier(name));
        }
        if (!primaryKeys.isEmpty()) definitions.add("  PRIMARY KEY (" + String.join(", ", primaryKeys) + ") NOT ENFORCED");
        String catalogName = text(table.get("catalogName"));
        if (catalogName.isEmpty()) catalogName = "paimon";
        StringBuilder ddl = new StringBuilder("CREATE TABLE ")
                .append(quoteIdentifier(catalogName)).append('.')
                .append(quoteIdentifier(text(table.get("databaseName")))).append('.')
                .append(quoteIdentifier(text(table.get("tableName"))))
                .append(" (\n").append(String.join(",\n", definitions)).append("\n)");
        String tableComment = text(table.get("tableComment"));
        if (!tableComment.isEmpty()) ddl.append("\nCOMMENT '").append(quoteLiteral(tableComment)).append('\'');
        if (!partitionKeys.isEmpty()) ddl.append("\nPARTITIONED BY (").append(String.join(", ", partitionKeys)).append(')');
        Map<String, String> options = stringMap(table.get("options"));
        if (!options.isEmpty()) {
            List<String> optionLines = new ArrayList<>();
            new TreeMap<>(options).forEach((key, value) -> optionLines.add("  '" + quoteLiteral(key) + "' = '" + quoteLiteral(value) + "'"));
            ddl.append("\nWITH (\n").append(String.join(",\n", optionLines)).append("\n)");
        }
        return ddl.append(';').toString();
    }

    public List<String> databases() { return catalog.databases(); }
    // 物理表发现是兜底对账，不应复用实例状态的 10 秒轮询周期，避免频繁打开 Hive Catalog。
    @Scheduled(initialDelayString = "${app.realtime.table-refresh-initial-delay-ms:60000}",
            fixedDelayString = "${app.realtime.table-refresh-delay-ms:300000}")
    public void discoverSyncTables() {
        List<Map<String,Object>> declared = repository.declaredSyncTables();
        if (declared.isEmpty()) return;
        try { catalog.describeExisting(declared).forEach((id, physical) -> repository.markPhysical(id, physical, "system")); }
        catch (RuntimeException ignored) { /* Catalog 暂时不可用时由下一轮重试。 */ }
    }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private boolean bool(Object value) { return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(text(value)) || "1".equals(text(value)); }
    private boolean boolDefault(Object value, boolean fallback) { return value == null ? fallback : bool(value); }
    private String quoteIdentifier(String value) { return "`" + value.replace("`", "``") + "`"; }
    private String quoteLiteral(String value) { return value.replace("'", "''"); }
    @SuppressWarnings("unchecked") private List<Map<String, Object>> maps(Object value) { return value instanceof List ? (List<Map<String, Object>>) value : List.of(); }
    private Map<String, String> stringMap(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (value instanceof Map) ((Map<?, ?>) value).forEach((key, item) -> result.put(String.valueOf(key), String.valueOf(item)));
        return result;
    }
}
