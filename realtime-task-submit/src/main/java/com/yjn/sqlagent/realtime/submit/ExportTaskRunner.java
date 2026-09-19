package com.yjn.sqlagent.realtime.submit;

import com.yjn.sqlagent.realtime.common.DebugReport;
import com.yjn.sqlagent.realtime.common.ExportSchemaCompatibility;
import com.yjn.sqlagent.realtime.common.SubmissionSpec;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.table.catalog.Column;
import org.apache.flink.table.catalog.ResolvedSchema;
import org.apache.flink.types.Row;

final class ExportTaskRunner implements TaskRunner {
    @Override public DebugReport validate(SubmissionSpec spec) throws Exception { return build(spec, false); }
    @Override public void execute(SubmissionSpec spec) throws Exception { build(spec, true); }

    private DebugReport build(SubmissionSpec spec, boolean execute) throws Exception {
        if (spec.getServers().isEmpty()) throw new IllegalArgumentException("出仓任务缺少目标 Server 快照");
        SubmissionSpec.ServerSnapshot server = spec.getServers().get(0);
        Map<String, Object> export = FlinkSqlSupport.map(spec.getTask().getTaskConfig().get("exportConfig"));
        List<Map<String, Object>> mappings = FlinkSqlSupport.maps(export.get("mappings"));
        if (mappings.isEmpty()) throw new IllegalArgumentException("出仓任务没有表映射");
        String url = url(server);
        DebugReport report = new DebugReport(); report.setTaskType("export");
        StreamExecutionEnvironment environment = StreamExecutionEnvironment.getExecutionEnvironment();
        StreamTableEnvironment table = FlinkSqlSupport.environment(spec, environment);
        validateMysql(url, server, export, mappings, table, report);
        Map<String, Object> sink = FlinkSqlSupport.map(export.get("sink"));
        int batch = integer(sink.get("batchSize"), 500), retries = integer(sink.get("maxRetries"), 3);
        long interval = integer(sink.get("flushIntervalMs"), 2000);
        if (!execute) {
            report.setSummary("Paimon 源表、MySQL 连接、Schema 基线、字段映射和主键校验通过，未读取或写入业务数据");
            return report;
        }
        for (Map<String, Object> mapping : mappings) {
            List<Map<String, Object>> columns = FlinkSqlSupport.maps(mapping.get("columnMappings"));
            List<String> source = new ArrayList<>(), target = new ArrayList<>();
            for (Map<String, Object> column : columns) {
                source.add(FlinkSqlSupport.text(column.get("sourceColumn")));
                target.add(FlinkSqlSupport.text(column.get("targetColumn")));
            }
            String select = String.join(",", source.stream().map(value -> "`" + value + "`").toArray(String[]::new));
            Table sourceTable = table.sqlQuery("SELECT " + select + " FROM paimon.`" + mapping.get("sourceDatabase") + "`.`" + mapping.get("sourceTable") + "`");
            DataStream<Row> stream = table.toChangelogStream(sourceTable);
            List<String> keys = strings(mapping.get("primaryKeys"));
            stream.sinkTo(new MysqlRowSink(new MysqlRowSink.Config(url, server.getAccount(), server.getPassword(),
                    FlinkSqlSupport.text(mapping.get("targetTable")), source, target, keys, batch, interval, retries)))
                    .name("mysql-export-" + mapping.get("targetTable"));
        }
        environment.execute(spec.getJobName()); return report;
    }

    private void validateMysql(String url, SubmissionSpec.ServerSnapshot server, Map<String, Object> export,
            List<Map<String, Object>> mappings, StreamTableEnvironment flink, DebugReport report) throws Exception {
        Map<String, Map<String, Object>> contracts = contracts(export.get("schemaContracts"));
        try (Connection connection = DriverManager.getConnection(url, server.getAccount(), server.getPassword())) {
            report.check("MYSQL_CONNECTION", server.getName(), "MySQL 连接与认证通过");
            DatabaseMetaData metadata = connection.getMetaData();
            List<String> inputs = new ArrayList<>(), outputs = new ArrayList<>();
            for (Map<String, Object> mapping : mappings) {
                String target = FlinkSqlSupport.text(mapping.get("targetTable"));
                String sourceDatabase = FlinkSqlSupport.text(mapping.get("sourceDatabase"));
                String sourceTable = FlinkSqlSupport.text(mapping.get("sourceTable"));
                requireTable(connection, metadata, target);
                List<Map<String, Object>> targetColumns = mysqlColumns(connection, server.getDatabaseName(), target);
                List<String> targetKeys = primaryKeys(connection, server.getDatabaseName(), target);
                if (targetKeys.isEmpty()) throw new IllegalArgumentException("MySQL 目标表缺少主键：" + target);
                ResolvedSchema resolved = flink.from("paimon.`" + sourceDatabase + "`.`" + sourceTable + "`").getResolvedSchema();
                List<Map<String, Object>> sourceColumns = paimonColumns(resolved);
                List<Map<String, Object>> columns = FlinkSqlSupport.maps(mapping.get("columnMappings"));
                if (columns.isEmpty()) throw new IllegalArgumentException("出仓字段映射为空：" + target);

                Map<String, Object> contract = contracts.get(contractKey(mapping));
                boolean drifted = contract != null && schemaDrifted(contract, sourceColumns, targetColumns, targetKeys);
                try {
                    ExportSchemaCompatibility.validate(sourceColumns, targetColumns, columns, targetKeys, target);
                } catch (IllegalArgumentException ex) {
                    if (drifted) throw new IllegalArgumentException("MYSQL_SCHEMA_DRIFT: " + ex.getMessage(), ex);
                    throw ex;
                }
                if (contract == null) {
                    report.check("SCHEMA_BASELINE", sourceDatabase + "." + sourceTable + " -> " + target,
                            "历史版本没有 Schema 基线，已按当前实际 Schema 完成兼容校验");
                } else if (drifted) {
                    report.check("MYSQL_SCHEMA_DRIFT_COMPATIBLE", sourceDatabase + "." + sourceTable + " -> " + target,
                            "实际 Schema 与保存基线不同，但当前字段映射仍兼容");
                } else {
                    report.check("SCHEMA_BASELINE", sourceDatabase + "." + sourceTable + " -> " + target,
                            "实际 Schema 与版本保存基线一致");
                }
                inputs.add(sourceDatabase + "." + sourceTable); outputs.add(server.getDatabaseName() + "." + target);
                report.check("TABLE_MAPPING", sourceDatabase + "." + sourceTable + " -> " + target,
                        "字段类型、可空性与主键映射通过（" + columns.size() + " 个字段）");
            }
            report.setInputs(inputs); report.setOutputs(outputs);
        }
    }

    private boolean schemaDrifted(Map<String, Object> contract, List<Map<String, Object>> sourceColumns,
            List<Map<String, Object>> targetColumns, List<String> targetKeys) {
        Map<String, Object> source = FlinkSqlSupport.map(contract.get("source"));
        Map<String, Object> target = FlinkSqlSupport.map(contract.get("target"));
        String currentSource = ExportSchemaCompatibility.fingerprint(sourceColumns,
                ExportSchemaCompatibility.primaryKeys(sourceColumns));
        String currentTarget = ExportSchemaCompatibility.fingerprint(targetColumns, targetKeys);
        return !currentSource.equals(FlinkSqlSupport.text(source.get("fingerprint")))
                || !currentTarget.equals(FlinkSqlSupport.text(target.get("fingerprint")));
    }

    private Map<String, Map<String, Object>> contracts(Object value) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> contract : FlinkSqlSupport.maps(value)) {
            Map<String, Object> source = FlinkSqlSupport.map(contract.get("source"));
            Map<String, Object> target = FlinkSqlSupport.map(contract.get("target"));
            result.put(FlinkSqlSupport.text(source.get("database")).toLowerCase(Locale.ROOT) + "."
                    + FlinkSqlSupport.text(source.get("table")).toLowerCase(Locale.ROOT) + "->"
                    + FlinkSqlSupport.text(target.get("table")).toLowerCase(Locale.ROOT), contract);
        }
        return result;
    }
    private String contractKey(Map<String, Object> mapping) {
        return FlinkSqlSupport.text(mapping.get("sourceDatabase")).toLowerCase(Locale.ROOT) + "."
                + FlinkSqlSupport.text(mapping.get("sourceTable")).toLowerCase(Locale.ROOT) + "->"
                + FlinkSqlSupport.text(mapping.get("targetTable")).toLowerCase(Locale.ROOT);
    }
    private void requireTable(Connection connection, DatabaseMetaData metadata, String table) throws Exception {
        try (ResultSet rows = metadata.getTables(connection.getCatalog(), null, table, new String[] {"TABLE"})) {
            if (!rows.next()) throw new IllegalArgumentException("MySQL 目标表不存在：" + table);
        }
    }
    private List<Map<String, Object>> mysqlColumns(Connection connection, String database, String table) throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();
        String sql = "SELECT COLUMN_NAME,DATA_TYPE,COLUMN_TYPE,IS_NULLABLE,CHARACTER_MAXIMUM_LENGTH,"
                + "NUMERIC_PRECISION,NUMERIC_SCALE,DATETIME_PRECISION FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, database); statement.setString(2, table);
            try (ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                Map<String, Object> column = new LinkedHashMap<>();
                String type = rows.getString("DATA_TYPE"), fullType = rows.getString("COLUMN_TYPE");
                column.put("name", rows.getString("COLUMN_NAME")); column.put("type", type); column.put("fullType", fullType);
                column.put("nullable", "YES".equalsIgnoreCase(rows.getString("IS_NULLABLE")));
                column.put("characterMaximumLength", rows.getObject("CHARACTER_MAXIMUM_LENGTH"));
                column.put("numericPrecision", rows.getObject("NUMERIC_PRECISION"));
                column.put("numericScale", rows.getObject("NUMERIC_SCALE"));
                column.put("datetimePrecision", rows.getObject("DATETIME_PRECISION"));
                column.put("unsigned", fullType != null && fullType.toLowerCase(Locale.ROOT).contains("unsigned")); result.add(column);
            }
            }
        }
        if (result.isEmpty()) throw new IllegalArgumentException("MySQL 目标表不存在或没有字段：" + table);
        return result;
    }
    private List<String> primaryKeys(Connection connection, String database, String table) throws Exception {
        Map<Integer, String> ordered = new java.util.TreeMap<>();
        String sql = "SELECT COLUMN_NAME,SEQ_IN_INDEX FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND INDEX_NAME='PRIMARY' ORDER BY SEQ_IN_INDEX";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, database); statement.setString(2, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) ordered.put(rows.getInt("SEQ_IN_INDEX"), rows.getString("COLUMN_NAME"));
            }
        }
        return new ArrayList<>(ordered.values());
    }
    private List<Map<String, Object>> paimonColumns(ResolvedSchema schema) {
        List<Map<String, Object>> result = new ArrayList<>(); Set<String> keys = new LinkedHashSet<>();
        schema.getPrimaryKey().ifPresent(key -> keys.addAll(key.getColumns()));
        for (Column column : schema.getColumns()) {
            Map<String, Object> value = new LinkedHashMap<>(); value.put("name", column.getName());
            value.put("dataType", column.getDataType().toString()); value.put("nullable", column.getDataType().getLogicalType().isNullable());
            value.put("primaryKey", keys.stream().anyMatch(key -> key.equalsIgnoreCase(column.getName()))); result.add(value);
        }
        return result;
    }
    private String url(SubmissionSpec.ServerSnapshot server) { String address=server.getAddress();String value=address.startsWith("jdbc:mysql://")?address:"jdbc:mysql://"+address;if(value.substring("jdbc:mysql://".length()).indexOf('/')<0)value+="/"+server.getDatabaseName();return value+(value.contains("?")?"&":"?")+"useUnicode=true&characterEncoding=utf8&useSSL=false"; }
    private int integer(Object value, int fallback) { try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); } catch (Exception ignored) { return fallback; } }
    @SuppressWarnings("unchecked") private List<String> strings(Object value) { return value instanceof List ? (List<String>) value : List.of(); }
}
