package com.yjn.sqlagent.realtime.service;

import com.yjn.sqlagent.realtime.model.ServerRequest;
import com.yjn.sqlagent.realtime.repository.RealtimeSyncRepository;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RealtimeServerService {
    private static final Logger LOG = LoggerFactory.getLogger(RealtimeServerService.class);
    private final RealtimeSyncRepository repository;

    public RealtimeServerService(RealtimeSyncRepository repository) {
        this.repository = repository;
    }

    public Map<String, Object> test(long serverId) {
        return test(repository.requiredServer(serverId, true));
    }

    public Map<String, Object> test(ServerRequest request) {
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("address", request.getAddress()); server.put("databaseName", request.getDatabaseName());
        server.put("account", request.getAccount()); server.put("password", request.getPassword());
        return test(server);
    }

    public List<String> tables(long serverId) {
        Map<String, Object> server = repository.requiredServer(serverId, true);
        try (Connection connection = connection(server)) {
            List<String> result = new ArrayList<>();
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet rows = metadata.getTables(connection.getCatalog(), null, "%", new String[] {"TABLE"})) {
                while (rows.next()) result.add(rows.getString("TABLE_NAME"));
            }
            result.sort(String.CASE_INSENSITIVE_ORDER);
            return result;
        } catch (Exception ex) {
            throw new IllegalStateException("读取 MySQL 表列表失败：" + safe(ex), ex);
        }
    }

    public Map<String, Object> schema(long serverId, String table) {
        Map<String, Map<String, Object>> schemas = schemas(serverId, List.of(table));
        Map<String, Object> schema = schemas.get(table);
        if (schema == null) throw new IllegalStateException("读取 MySQL 表结构失败：" + table + " 不存在");
        return schema;
    }

    /** 同一批源表复用一个 JDBC 连接，避免同步任务校验随表数重复建连。 */
    public Map<String, Map<String, Object>> schemas(long serverId, List<String> tables) {
        Map<String, Object> server = repository.requiredServer(serverId, true);
        List<String> tableNames = normalizedTables(tables);
        if (tableNames.isEmpty()) return Map.of();
        long started = System.nanoTime();
        try (Connection connection = connection(server)) {
            String database = text(server.get("databaseName"));
            Map<String, Map<String, Object>> result = readSchemas(connection, database, tableNames);
            LOG.info("sync_schema_batch serverId={} tableCount={} connectionCount=1 metadataQueryCount={} costMs={}",
                    serverId, tableNames.size(), batchCount(tableNames.size()) * 2, elapsedMs(started));
            return result;
        } catch (Exception ex) {
            LOG.warn("sync_schema_batch_failed serverId={} tableCount={} connectionCount=1 costMs={} errorType={}",
                    serverId, tableNames.size(), elapsedMs(started), ex.getClass().getSimpleName());
            throw new IllegalStateException("批量读取 MySQL 表结构失败：" + safe(ex), ex);
        }
    }

    public List<Map<String, Object>> commonColumns(long serverId, List<String> tables) {
        Map<String, Map<String, Object>> common = null;
        for (Map<String, Object> schema : schemas(serverId, tables).values()) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> columns = (List<Map<String, Object>>) schema.get("columns");
            Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
            columns.forEach(column -> byName.put(String.valueOf(column.get("name")), column));
            if (common == null) common = byName;
            else common.entrySet().removeIf(entry -> !byName.containsKey(entry.getKey())
                    || !String.valueOf(entry.getValue().get("type"))
                            .equalsIgnoreCase(String.valueOf(byName.get(entry.getKey()).get("type"))));
        }
        return common == null ? List.of() : new ArrayList<>(common.values());
    }

    private Map<String, Object> test(Map<String, Object> server) {
        long started = System.currentTimeMillis();
        try (Connection connection = connection(server)) {
            return Map.of("success", connection.isValid(5), "latencyMs", System.currentTimeMillis() - started,
                    "databaseProduct", connection.getMetaData().getDatabaseProductVersion());
        } catch (Exception ex) {
            throw new IllegalStateException("MySQL 连接测试失败：" + safe(ex), ex);
        }
    }

    Connection connection(Map<String, Object> server) throws Exception {
        String address = text(server.get("address"));
        String database = text(server.get("databaseName"));
        String url = address.startsWith("jdbc:mysql://") ? address : "jdbc:mysql://" + address;
        if (!database.isEmpty() && url.substring("jdbc:mysql://".length()).indexOf('/') < 0) url += "/" + database;
        url += (url.contains("?") ? "&" : "?") + "useUnicode=true&characterEncoding=utf8&useSSL=false&connectTimeout=5000";
        return DriverManager.getConnection(url, text(server.get("account")), text(server.get("password")));
    }
    private String safe(Exception ex) {
        String value = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        return value.replaceAll("(?i)(password=)[^&\\s]+", "$1******");
    }
    private Map<String, Map<String, Object>> readSchemas(Connection connection, String database,
            List<String> tableNames) throws Exception {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        Map<String, Map<Integer, String>> primaryKeys = new LinkedHashMap<>();
        for (String table : tableNames) {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("database", database);
            schema.put("table", table);
            schema.put("columns", new ArrayList<Map<String, Object>>());
            schema.put("primaryKeys", new ArrayList<String>());
            result.put(table, schema);
            primaryKeys.put(table, new java.util.TreeMap<>());
        }
        for (int offset = 0; offset < tableNames.size(); offset += 500) {
            List<String> batch = tableNames.subList(offset, Math.min(tableNames.size(), offset + 500));
            String placeholders = String.join(",", java.util.Collections.nCopies(batch.size(), "?"));
            String columnSql = "SELECT TABLE_NAME,COLUMN_NAME,ORDINAL_POSITION,DATA_TYPE,IS_NULLABLE,"
                    + "COLUMN_COMMENT,COLUMN_DEFAULT,EXTRA FROM information_schema.COLUMNS "
                    + "WHERE TABLE_SCHEMA=? AND TABLE_NAME IN (" + placeholders + ") "
                    + "ORDER BY TABLE_NAME,ORDINAL_POSITION";
            try (PreparedStatement statement = connection.prepareStatement(columnSql)) {
                statement.setString(1, database);
                for (int index = 0; index < batch.size(); index++) statement.setString(index + 2, batch.get(index));
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        String table = rows.getString("TABLE_NAME");
                        Map<String, Object> schema = result.get(table);
                        if (schema == null) continue;
                        Map<String, Object> column = new LinkedHashMap<>();
                        column.put("name", rows.getString("COLUMN_NAME"));
                        column.put("ordinalPosition", rows.getInt("ORDINAL_POSITION"));
                        column.put("type", rows.getString("DATA_TYPE"));
                        column.put("nullable", "YES".equalsIgnoreCase(rows.getString("IS_NULLABLE")));
                        column.put("comment", rows.getString("COLUMN_COMMENT"));
                        column.put("defaultValue", rows.getObject("COLUMN_DEFAULT"));
                        column.put("extra", rows.getString("EXTRA"));
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> columns = (List<Map<String, Object>>) schema.get("columns");
                        columns.add(column);
                    }
                }
            }
            String primaryKeySql = "SELECT TABLE_NAME,COLUMN_NAME,SEQ_IN_INDEX FROM information_schema.STATISTICS "
                    + "WHERE TABLE_SCHEMA=? AND INDEX_NAME='PRIMARY' AND TABLE_NAME IN (" + placeholders + ") "
                    + "ORDER BY TABLE_NAME,SEQ_IN_INDEX";
            try (PreparedStatement statement = connection.prepareStatement(primaryKeySql)) {
                statement.setString(1, database);
                for (int index = 0; index < batch.size(); index++) statement.setString(index + 2, batch.get(index));
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        String table = rows.getString("TABLE_NAME");
                        if (!primaryKeys.containsKey(table)) continue;
                        primaryKeys.get(table).put(rows.getInt("SEQ_IN_INDEX"), rows.getString("COLUMN_NAME"));
                    }
                }
            }
        }
        for (String table : tableNames) {
            Map<String, Object> schema = result.get(table);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> columns = (List<Map<String, Object>>) schema.get("columns");
            if (columns.isEmpty()) throw new IllegalStateException("MySQL 表不存在或没有字段：" + table);
            columns.sort(Comparator.comparingInt(column -> ((Number) column.get("ordinalPosition")).intValue()));
            columns.forEach(column -> column.remove("ordinalPosition"));
            schema.put("primaryKeys", new ArrayList<>(primaryKeys.get(table).values()));
        }
        return result;
    }
    private int batchCount(int size) { return (size + 499) / 500; }
    private List<String> normalizedTables(List<String> tables) {
        Set<String> result = new LinkedHashSet<>();
        if (tables != null) {
            for (String table : tables) {
                String value = text(table);
                if (value.isEmpty()) throw new IllegalArgumentException("MySQL 表名不能为空");
                result.add(value);
            }
        }
        return new ArrayList<>(result);
    }
    private long elapsedMs(long started) { return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
}
