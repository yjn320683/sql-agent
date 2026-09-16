package com.yjn.sqlagent.realtime.service;

import com.yjn.sqlagent.realtime.config.RealtimeProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.catalog.CatalogFactory;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.options.Options;
import org.apache.paimon.schema.Schema;
import org.apache.paimon.schema.SchemaChange;
import org.apache.paimon.table.Table;
import org.apache.paimon.types.DataField;
import org.apache.paimon.types.DataType;
import org.apache.paimon.types.DataTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 受管实时表对 Paimon Catalog 的唯一写入口。 */
@Service
public class RealtimePaimonCatalogService {
    private static final Logger LOG = LoggerFactory.getLogger(RealtimePaimonCatalogService.class);
    private static final Pattern PARAMETERIZED = Pattern.compile("^(DECIMAL|CHAR|VARCHAR|BINARY|VARBINARY|TIME|TIMESTAMP)\\s*\\((\\d+)(?:\\s*,\\s*(\\d+))?\\)$");
    private static final Set<String> SAFE_OPTIONS = Set.of("bucket", "changelog-producer", "snapshot.time-retained",
            "snapshot.num-retained.min", "snapshot.num-retained.max", "compaction.min.file-num",
            "compaction.max.file-num", "target-file-size", "write-buffer-size", "sink.parallelism",
            "precommit-compact",
            "consumer.expiration-time");
    private static final Set<String> SAFE_ALTER_OPTIONS = Set.of("snapshot.time-retained",
            "snapshot.num-retained.min", "snapshot.num-retained.max", "compaction.min.file-num",
            "compaction.max.file-num", "target-file-size", "write-buffer-size");
    private final RealtimeProperties properties;

    public RealtimePaimonCatalogService(RealtimeProperties properties) { this.properties = properties; }

    public List<String> databases() {
        try (Catalog catalog = catalog()) { return catalog.listDatabases(); }
        catch (Exception ex) { throw failure("读取 Paimon 数据库失败", ex); }
    }

    public Map<String, Object> create(Map<String, Object> request) {
        String database = identifier(request.get("databaseName"), "数据库");
        String tableName = identifier(request.get("tableName"), "表名");
        List<Map<String, Object>> columns = maps(request.get("columns"));
        if (columns.isEmpty()) throw new IllegalArgumentException("实时表至少需要一个字段");
        Schema.Builder builder = Schema.newBuilder();
        List<String> primaryKeys = new ArrayList<>();
        List<String> partitionKeys = new ArrayList<>();
        Set<String> names = new java.util.LinkedHashSet<>();
        for (Map<String, Object> column : columns) {
            String name = identifier(column.get("name"), "字段名");
            if (!names.add(name.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("字段名重复：" + name);
            boolean primary = bool(column.get("primaryKey"));
            boolean partition = bool(column.get("partitionKey"));
            DataType type = dataType(text(column.get("dataType")));
            if (primary || !boolDefault(column.get("nullable"), true)) type = type.notNull();
            builder.column(name, type, text(column.get("comment")));
            if (primary) primaryKeys.add(name);
            if (partition) partitionKeys.add(name);
        }
        String tableType = text(request.get("tableType"));
        if ("primary_key".equals(tableType) && primaryKeys.isEmpty()) throw new IllegalArgumentException("主键表至少需要一个主键字段");
        if ("append_only".equals(tableType) && !primaryKeys.isEmpty()) throw new IllegalArgumentException("Append-only 表不能配置主键");
        Map<String, String> options = safeOptions(request.get("options"));
        builder.primaryKey(primaryKeys).partitionKeys(partitionKeys).options(options).comment(text(request.get("comment")));
        try (Catalog catalog = catalog()) {
            if (!catalog.listDatabases().contains(database)) throw new IllegalArgumentException("Paimon 数据库不存在：" + database);
            catalog.createTable(Identifier.create(database, tableName), builder.build(), false);
            return describe(catalog.getTable(Identifier.create(database, tableName)));
        } catch (IllegalArgumentException ex) { throw ex; }
        catch (Exception ex) { throw failure("创建 Paimon 实时表失败", ex); }
    }

    public Map<String, Object> describe(String database, String tableName) {
        try (Catalog catalog = catalog()) { return describe(catalog.getTable(Identifier.create(database, tableName))); }
        catch (Exception ex) { throw failure("读取 Paimon 表结构失败", ex); }
    }

    /** 精确检查目标表是否存在；不扫描目标库的全部表。 */
    public Set<String> existingTables(String database, List<String> tableNames) {
        Set<String> result = new java.util.LinkedHashSet<>();
        if (tableNames == null || tableNames.isEmpty()) return result;
        long startedAt = System.nanoTime();
        try (Catalog catalog = catalog()) {
            for (String tableName : tableNames) {
                try {
                    catalog.getTable(Identifier.create(database, tableName));
                    result.add(tableName);
                } catch (Catalog.TableNotExistException ignored) {
                    // 目标表不存在正是新增同步表的正常情况。
                }
            }
            LOG.info("sync_validation_stage stage=paimon_target_check database={} tableCount={} conflictCount={} catalogOpenCount=1 remoteQueryCount={} costMs={}",
                    database, tableNames.size(), result.size(), tableNames.size(), elapsedMs(startedAt));
            return result;
        } catch (Exception ex) {
            LOG.warn("sync_validation_stage stage=paimon_target_check database={} tableCount={} catalogOpenCount=1 result=failed costMs={} errorType={}",
                    database, tableNames.size(), elapsedMs(startedAt), ex.getClass().getSimpleName());
            throw failure("Paimon物理表检查失败", ex);
        }
    }

    public Map<Long, Map<String, Object>> describeExisting(List<Map<String, Object>> tables) {
        Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
        try (Catalog catalog = catalog()) {
            for (Map<String, Object> table : tables) {
                try {
                    long id = ((Number) table.get("id")).longValue();
                    result.put(id, describe(catalog.getTable(Identifier.create(
                            text(table.get("databaseName")), text(table.get("tableName"))))));
                } catch (Catalog.TableNotExistException ignored) {
                    // 同步 Action 还未创建物理表时继续保持“待创建”。
                }
            }
            return result;
        } catch (Exception ex) { throw failure("批量刷新 Paimon 表结构失败", ex); }
    }

    public Map<String, Object> safeAlter(String database, String tableName, Map<String, Object> request) {
        List<SchemaChange> changes = new ArrayList<>();
        if (request.containsKey("comment")) changes.add(SchemaChange.updateComment(text(request.get("comment"))));
        for (Map<String, Object> column : maps(request.get("addColumns"))) {
            if (!boolDefault(column.get("nullable"), true) || bool(column.get("primaryKey")) || bool(column.get("partitionKey"))) {
                throw new IllegalArgumentException("安全变更只能增加可空的非主键、非分区字段");
            }
            changes.add(SchemaChange.addColumn(identifier(column.get("name"), "字段名"),
                    dataType(text(column.get("dataType"))).nullable(), text(column.get("comment"))));
        }
        for (Map<String, Object> column : maps(request.get("columnComments"))) {
            changes.add(SchemaChange.updateColumnComment(identifier(column.get("name"), "字段名"),
                    text(column.get("comment"))));
        }
        safeOptions(request.get("options")).forEach((key, value) -> {
            if (!SAFE_ALTER_OPTIONS.contains(key)) {
                throw new IllegalArgumentException("安全变更不允许修改 Paimon 参数：" + key);
            }
            changes.add(SchemaChange.setOption(key, value));
        });
        if (changes.isEmpty()) throw new IllegalArgumentException("没有可应用的安全变更");
        try (Catalog catalog = catalog()) {
            Identifier identifier = Identifier.create(database, tableName);
            catalog.alterTable(identifier, changes, false);
            return describe(catalog.getTable(identifier));
        } catch (Exception ex) { throw failure("更新 Paimon 实时表失败", ex); }
    }

    private Catalog catalog() {
        String warehouse = text(properties.getPaimonWarehouse());
        if (warehouse.isEmpty()) throw new IllegalStateException("未配置 app.realtime.paimon-warehouse");
        Options options = new Options();
        options.set("warehouse", warehouse);
        properties.getCatalogConf().forEach(options::set);
        return CatalogFactory.createCatalog(CatalogContext.create(options));
    }

    private Map<String, Object> describe(Table table) {
        List<Map<String, Object>> columns = new ArrayList<>();
        int order = 0;
        for (DataField field : table.rowType().getFields()) {
            Map<String, Object> column = new LinkedHashMap<>();
            column.put("name", field.name()); column.put("dataType", field.type().asSQLString());
            column.put("nullable", field.type().isNullable()); column.put("primaryKey", table.primaryKeys().contains(field.name()));
            column.put("partitionKey", table.partitionKeys().contains(field.name())); column.put("comment", field.description());
            column.put("sortOrder", order++); columns.add(column);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("columns", columns); result.put("primaryKeys", table.primaryKeys()); result.put("partitionKeys", table.partitionKeys());
        result.put("options", table.options()); result.put("comment", table.comment().orElse(""));
        return result;
    }

    public Map<String, String> safeOptions(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        map(value).forEach((key, item) -> {
            if (!SAFE_OPTIONS.contains(key)) throw new IllegalArgumentException("不允许配置 Paimon 参数：" + key);
            String normalized = text(item);
            if (!normalized.isEmpty()) result.put(key, normalized);
        });
        return result;
    }

    DataType dataType(String raw) {
        String type = raw.trim().toUpperCase(Locale.ROOT);
        Matcher matcher = PARAMETERIZED.matcher(type);
        if (matcher.matches()) {
            int first = Integer.parseInt(matcher.group(2));
            String second = matcher.group(3);
            switch (matcher.group(1)) {
                case "DECIMAL": return DataTypes.DECIMAL(first, second == null ? 0 : Integer.parseInt(second));
                case "CHAR": return DataTypes.CHAR(first);
                case "VARCHAR": return DataTypes.VARCHAR(first);
                case "BINARY": return DataTypes.BINARY(first);
                case "VARBINARY": return DataTypes.VARBINARY(first);
                case "TIME": return DataTypes.TIME(first);
                case "TIMESTAMP": return DataTypes.TIMESTAMP(first);
                default: break;
            }
        }
        switch (type) {
            case "BOOLEAN": return DataTypes.BOOLEAN(); case "TINYINT": return DataTypes.TINYINT();
            case "SMALLINT": return DataTypes.SMALLINT(); case "INT": case "INTEGER": return DataTypes.INT();
            case "BIGINT": return DataTypes.BIGINT(); case "FLOAT": return DataTypes.FLOAT(); case "DOUBLE": return DataTypes.DOUBLE();
            case "STRING": case "TEXT": return DataTypes.STRING(); case "BYTES": return DataTypes.BYTES();
            case "DATE": return DataTypes.DATE(); case "TIME": return DataTypes.TIME(); case "TIMESTAMP": return DataTypes.TIMESTAMP();
            default: throw new IllegalArgumentException("不支持的 Paimon 标量类型：" + raw);
        }
    }

    private String identifier(Object value, String label) {
        String result = text(value);
        if (!result.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) throw new IllegalArgumentException(label + "格式不正确");
        return result;
    }
    private IllegalStateException failure(String message, Exception ex) { return new IllegalStateException(message + "：" + text(ex.getMessage()), ex); }
    @SuppressWarnings("unchecked") private Map<String, Object> map(Object value) { return value instanceof Map ? (Map<String, Object>) value : Map.of(); }
    @SuppressWarnings("unchecked") private List<Map<String, Object>> maps(Object value) { return value instanceof List ? (List<Map<String, Object>>) value : List.of(); }
    private boolean bool(Object value) { return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(text(value)) || "1".equals(text(value)); }
    private boolean boolDefault(Object value, boolean fallback) { return value == null ? fallback : bool(value); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private long elapsedMs(long startedAt) { return (System.nanoTime() - startedAt) / 1_000_000L; }
}
