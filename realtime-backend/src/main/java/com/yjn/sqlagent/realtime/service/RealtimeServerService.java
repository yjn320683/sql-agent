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
                    column.put("type", rows.getString("TYPE_NAME"));
                    column.put("nullable", rows.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls);
                    column.put("comment", rows.getString("REMARKS"));
                    columns.add(column);
                }
            }
            List<String> primaryKeys = new ArrayList<>();
            try (ResultSet rows = metadata.getPrimaryKeys(connection.getCatalog(), null, table)) {
                Map<Short, String> ordered = new java.util.TreeMap<>();
                while (rows.next()) ordered.put(rows.getShort("KEY_SEQ"), rows.getString("COLUMN_NAME"));
                primaryKeys.addAll(ordered.values());
            }
            return Map.of("table", table, "columns", columns, "primaryKeys", primaryKeys);
        } catch (Exception ex) {
            throw new IllegalStateException("读取 MySQL 表结构失败：" + safe(ex), ex);
        }
    }

    public List<String> commonColumns(long serverId, List<String> tables) {
        Set<String> common = null;
        for (String table : tables) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> columns = (List<Map<String, Object>>) schema(serverId, table).get("columns");
            Set<String> names = new LinkedHashSet<>();
            columns.forEach(column -> names.add(String.valueOf(column.get("name"))));
            if (common == null) common = names; else common.retainAll(names);
        }
        return common == null ? List.of() : new ArrayList<>(common);
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
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
}
