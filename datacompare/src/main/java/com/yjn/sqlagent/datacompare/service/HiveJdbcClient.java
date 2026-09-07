package com.yjn.sqlagent.datacompare.service;

import com.yjn.sqlagent.datacompare.config.DataCompareProperties;
import com.yjn.sqlagent.datacompare.model.TableColumn;
import com.yjn.sqlagent.parsesql.SqlDialect;
import com.yjn.sqlagent.parsesql.SqlLineageParser;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class HiveJdbcClient {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*(\\.[A-Za-z_][A-Za-z0-9_$]*)?");
    private final String jdbcUrl;
    private final String jdbcUser;
    private final SqlLineageParser sqlParser;

    public HiveJdbcClient(DataCompareProperties properties) {
        this(properties, new SqlLineageParser());
    }

    @Autowired
    public HiveJdbcClient(DataCompareProperties properties, SqlLineageParser sqlParser) {
        this.jdbcUrl = properties.getHiveJdbcUrl();
        this.jdbcUser = properties.getHiveJdbcUser();
        this.sqlParser = sqlParser;
    }

    public void execute(String sql, Consumer<Statement> statementListener) throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statementListener.accept(statement);
            statement.execute(sql);
        }
    }

    public void executeScript(String script, Consumer<Statement> statementListener, Consumer<String> logger)
            throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statementListener.accept(statement);
            for (String sql : sqlParser.splitStatements(script, SqlDialect.HIVE)) {
                if (sql.trim().isEmpty()) continue;
                logger.accept("[HIVE] " + compact(sql));
                statement.execute(sql);
            }
        }
    }

    public List<Map<String, Object>> query(String sql, Consumer<Statement> statementListener) throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statementListener.accept(statement);
            try (ResultSet rs = statement.executeQuery(sql)) {
                ResultSetMetaData metadata = rs.getMetaData();
                List<Map<String, Object>> result = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= metadata.getColumnCount(); i++) {
                        String name = metadata.getColumnLabel(i);
                        int dot = name.lastIndexOf('.');
                        row.put((dot >= 0 ? name.substring(dot + 1) : name).toLowerCase(Locale.ROOT), rs.getObject(i));
                    }
                    result.add(row);
                }
                return result;
            }
        }
    }

    public List<TableColumn> columns(String table, Consumer<Statement> statementListener) throws SQLException {
        requireTable(table);
        List<Map<String, Object>> rows = query("DESCRIBE " + quote(table), statementListener);
        List<TableColumn> result = new ArrayList<>();
        boolean partition = false;
        for (Map<String, Object> row : rows) {
            String name = string(first(row, "col_name", "col_name "));
            String type = string(first(row, "data_type", "data_type "));
            String comment = string(first(row, "comment", "comment "));
            if (name == null) continue;
            name = name.trim();
            if (name.startsWith("# Partition Information")) { partition = true; continue; }
            if (name.isEmpty() || name.startsWith("#")) continue;
            if (!Pattern.matches("[A-Za-z_][A-Za-z0-9_$]*", name)) continue;
            result.add(new TableColumn(name, type == null ? "" : type.trim(), comment, partition));
        }
        return result;
    }

    public List<String> partitions(String table, Consumer<Statement> statementListener) throws SQLException {
        requireTable(table);
        List<Map<String, Object>> rows;
        try {
            rows = query("SHOW PARTITIONS " + quote(table), statementListener);
        } catch (SQLException error) {
            if (error.getMessage() != null && error.getMessage().toLowerCase(Locale.ROOT).contains("not partitioned")) {
                return new ArrayList<>();
            }
            throw error;
        }
        List<String> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object value = row.values().stream().findFirst().orElse(null);
            if (value != null && !value.toString().trim().isEmpty()) result.add(value.toString().trim());
        }
        return result;
    }

    public void createLike(String target, String source, Consumer<Statement> listener) throws SQLException {
        requireTable(target); requireTable(source);
        execute(createLikeSql(target, source), listener);
    }

    public static String quote(String identifier) {
        requireTable(identifier);
        String[] parts = identifier.split("\\.");
        return parts.length == 1 ? "`" + parts[0] + "`" : "`" + parts[0] + "`.`" + parts[1] + "`";
    }

    public static String quoteColumn(String identifier) {
        if (!Pattern.matches("[A-Za-z_][A-Za-z0-9_$]*", identifier)) {
            throw new IllegalArgumentException("非法Hive字段名");
        }
        return "`" + identifier + "`";
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, jdbcUser, "");
    }

    String jdbcUser() {
        return jdbcUser;
    }

    static String createLikeSql(String target, String source) {
        requireTable(target); requireTable(source);
        return "CREATE TABLE " + quote(target) + " LIKE " + quote(source);
    }

    private static void requireTable(String table) {
        if (table == null || !IDENTIFIER.matcher(table).matches()) {
            throw new IllegalArgumentException("非法Hive表名");
        }
    }

    private static Object first(Map<String, Object> row, String... names) {
        for (String name : names) if (row.containsKey(name)) return row.get(name);
        return row.values().stream().findFirst().orElse(null);
    }

    private static String string(Object value) { return value == null ? null : value.toString(); }

    private static String compact(String sql) {
        return sql.replaceAll("\\s+", " ").trim().substring(0, Math.min(500, sql.replaceAll("\\s+", " ").trim().length()));
    }

}
