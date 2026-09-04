package com.yjn.sqlagent.realtime.service;

import com.yjn.sqlagent.realtime.model.ServerRequest;
import com.yjn.sqlagent.realtime.repository.RealtimeSyncRepository;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;
import org.springframework.stereotype.Service;

@Service
public class RealtimeServerService {
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
        Map<String, Object> server = repository.requiredServer(serverId, true);
        try (Connection connection = connection(server)) {
            DatabaseMetaData metadata = connection.getMetaData();
            List<Map<String, Object>> columns = new ArrayList<>();
            try (ResultSet rows = metadata.getColumns(connection.getCatalog(), null, table, "%")) {
                while (rows.next()) {
                    Map<String, Object> column = new LinkedHashMap<>();
                    column.put("name", rows.getString("COLUMN_NAME"));
                    column.put("ordinalPosition", rows.getInt("ORDINAL_POSITION"));
                    column.put("type", rows.getString("TYPE_NAME"));
                    column.put("nullable", rows.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls);
                    column.put("comment", rows.getString("REMARKS"));
                    column.put("defaultValue", rows.getObject("COLUMN_DEF"));
                    column.put("extra", columnExtra(rows));
                    columns.add(column);
                }
            }
            columns.sort(Comparator.comparingInt(column -> ((Number) column.get("ordinalPosition")).intValue()));
            columns.forEach(column -> column.remove("ordinalPosition"));
            List<String> primaryKeys = new ArrayList<>();
            try (ResultSet rows = metadata.getPrimaryKeys(connection.getCatalog(), null, table)) {
                Map<Short, String> ordered = new java.util.TreeMap<>();
                while (rows.next()) ordered.put(rows.getShort("KEY_SEQ"), rows.getString("COLUMN_NAME"));
                primaryKeys.addAll(ordered.values());
            }
            return Map.of("database", text(server.get("databaseName")), "table", table,
                    "columns", columns, "primaryKeys", primaryKeys);
        } catch (Exception ex) {
            throw new IllegalStateException("读取 MySQL 表结构失败：" + safe(ex), ex);
        }
    }

    public List<Map<String, Object>> commonColumns(long serverId, List<String> tables) {
        Map<String, Map<String, Object>> common = null;
        for (String table : tables) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> columns = (List<Map<String, Object>>) schema(serverId, table).get("columns");
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

    private Connection connection(Map<String, Object> server) throws Exception {
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
    private String columnExtra(ResultSet rows) {
        try {
            List<String> values = new ArrayList<>();
            if ("YES".equalsIgnoreCase(rows.getString("IS_AUTOINCREMENT"))) values.add("auto_increment");
            if ("YES".equalsIgnoreCase(rows.getString("IS_GENERATEDCOLUMN"))) values.add("generated");
            return String.join(" ", values);
        } catch (Exception ignored) {
            return "";
        }
    }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
}
