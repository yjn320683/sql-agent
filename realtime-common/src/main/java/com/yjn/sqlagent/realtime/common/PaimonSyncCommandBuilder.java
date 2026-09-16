package com.yjn.sqlagent.realtime.common;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 将同步任务快照转换为 Paimon mysql_sync_database Action 参数。 */
public class PaimonSyncCommandBuilder {

    private static final Pattern DOMAIN = Pattern.compile("[a-z0-9]+");
    public Command build(SubmissionSpec spec) {
        return build(spec, false);
    }

    /** 预览阶段尚未创建实例，仅展示实际启动时会自动分配 Server ID。 */
    public Command buildPreview(SubmissionSpec spec) {
        return build(spec, true);
    }

    private Command build(SubmissionSpec spec, boolean preview) {
        required(spec, "提交配置不存在");
        SubmissionSpec.TaskSpec task = required(spec.getTask(), "任务配置不存在");
        if (!"sync".equals(task.getTaskType()) || !"mysql-cdc".equals(task.getSourceType())) {
            throw new IllegalArgumentException("同步任务仅支持 mysql-cdc");
        }
        SubmissionSpec.RuntimeConfig runtime = required(spec.getRuntimeConfig(), "运行配置不存在");
        Map<String, Object> config = task.getTaskConfig();
        Map<String, Object> cdc = objectMap(config.get("cdcConfig"));
        Long serverId = longValue(config.get("sourceServerId"));
        SubmissionSpec.ServerSnapshot server = spec.getServers().stream()
                .filter(item -> Objects.equals(item.getId(), serverId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("MySQL Server 不存在"));
        String warehouse = "DEBUG".equalsIgnoreCase(spec.getExecutionMode())
                ? first(runtime.getPaimonDebugWarehouse(), runtime.getPaimonWarehouse())
                : runtime.getPaimonWarehouse();
        String database = first(text(cdc.get("targetDatabase")), runtime.getTargetDatabase());
        List<String> args = new ArrayList<>();
        args.add("mysql_sync_database");
        option(args, "--warehouse", required(warehouse, "Paimon warehouse 未配置"));
        option(args, "--database", required(database, "Paimon 目标库未配置"));
        Endpoint endpoint = endpoint(required(server.getAddress(), "Server 地址未配置"));
        List<String> tables = strings(cdc.get("selectedTables"));
        if (tables.isEmpty()) throw new IllegalArgumentException("请至少选择一张 MySQL 源表");
        String includingTables = tables.stream().map(this::regexLiteral).collect(Collectors.joining("|"));
        option(args, "--including_tables", includingTables);
        option(args, "--excluding_tables", text(cdc.get("excludingTables")));
        option(args, "--merge_shards", text(cdc.get("mergeShards")));
        option(args, "--mode", "combined");
        option(args, "--ignore_incompatible", text(cdc.get("ignoreIncompatible")));
        String tablePrefix = tablePrefix(database, cdc, server);
        validateTargetIdentifiers(database, tablePrefix, text(cdc.get("tableSuffix")), tables);
        option(args, "--table_prefix", tablePrefix);
        option(args, "--table_suffix", text(cdc.get("tableSuffix")));
        csv(args, "--type_mapping", strings(cdc.get("typeMappings")));
        csv(args, "--metadata_column", strings(cdc.get("metadataColumns")));
        validateTableConfigs(tables, objectMap(cdc.get("tableConfigs")));
        appendTableConfigs(args, tables, objectMap(cdc.get("tableConfigs")));
        Map<String, String> mysql = new LinkedHashMap<>(runtime.getMysqlDefaultConf());
        mysql.putAll(stringMap(cdc.get("mysqlConfOverrides")));
        mysql.put("server-id", serverIdRange(spec, preview));
        mysql.put("hostname", endpoint.host);
        mysql.put("port", String.valueOf(endpoint.port));
        mysql.put("username", required(server.getAccount(), "Server 账号未配置"));
        mysql.put("password", required(server.getPassword(), "Server 密码未配置"));
        mysql.put("database-name", first(text(cdc.get("databaseName")), server.getDatabaseName()));
        appendMap(args, "--mysql_conf", mysql);
        Map<String, String> catalog = new LinkedHashMap<>(runtime.getCatalogConf());
        catalog.put("hive.metastore.uri.selection", "SEQUENTIAL");
        appendMap(args, "--catalog_conf", catalog);
        Map<String, String> tableConf = new LinkedHashMap<>(runtime.getDefaultTableConf());
        tableConf.putAll(stringMap(cdc.get("tableConfOverrides")));
        PaimonSyncOptionValidator.validateTableConf(tableConf);
        if (hasPartitionKeys(tables, objectMap(cdc.get("tableConfigs")))) {
            tableConf.put("metastore.partitioned-table", "true");
        }
        appendMap(args, "--table_conf", tableConf);
        return new Command(required(runtime.getPaimonActionJarPath(), "Paimon Action Jar 未配置"), args);
    }

    private String serverIdRange(SubmissionSpec spec, boolean preview) {
        Integer parallelism = spec.getTask().getParallelism();
        if (parallelism != null && parallelism > 16) {
            throw new IllegalArgumentException("Source 并行度不能超过 Server ID 范围容量 16");
        }
        Long instanceId = spec.getTaskInstanceId();
        if (instanceId == null && preview) return "<启动时自动分配>";
        if (instanceId == null || instanceId <= 0 || instanceId > (999999999L - 1000000L - 15) / 16) {
            throw new IllegalArgumentException("实例 ID 缺失或 Server ID 分配范围越界");
        }
        long start = 1000000L + instanceId * 16;
        return start + "-" + (start + 15);
    }

    private void appendTableConfigs(List<String> args, List<String> tables, Map<String, Object> configs) {
        for (String table : tables) {
            Map<String, Object> item = objectMap(configs.get(table));
            csvWithPrefix(args, "--multiple_table_primary_keys", table, strings(item.get("primaryKeys")));
            csvWithPrefix(args, "--multiple_table_partition_keys", table, strings(item.get("partitionKeys")));
            for (String expression : strings(item.get("computedColumns"))) {
                option(args, "--multiple_table_computed_column", table + "=" + expression);
            }
        }
    }

    private void validateTableConfigs(List<String> tables, Map<String, Object> configs) {
        for (String table : configs.keySet()) {
            if (!tables.contains(table)) throw new IllegalArgumentException("私有配置表不在已选源表中：" + table);
        }
    }

    private boolean hasPartitionKeys(List<String> tables, Map<String, Object> configs) {
        for (String table : tables) {
            if (!strings(objectMap(configs.get(table)).get("partitionKeys")).isEmpty()) return true;
        }
        return false;
    }

    private String tablePrefix(String database, Map<String, Object> cdc, SubmissionSpec.ServerSnapshot server) {
        String sourceDatabase = first(text(cdc.get("databaseName")), text(server.getDatabaseName()));
        String domain = text(cdc.get("domainPrefix"));
        if (!DOMAIN.matcher(domain).matches()) throw new IllegalArgumentException("业务域只能包含小写字母和数字");
        return database + "_"
                + (text(server.getDatabasePrefix()).isEmpty() ? "" : text(server.getDatabasePrefix()) + "_")
                + required(sourceDatabase, "MySQL 源库不能为空") + "_" + domain + "_";
    }

    private void validateTargetIdentifiers(String database, String prefix, String suffix, List<String> tables) {
        if (database.length() > 128) throw new IllegalArgumentException("Paimon 目标库名称不能超过 128 个字符");
        if (tables.isEmpty() && prefix.length() > 128) throw new IllegalArgumentException("Paimon 目标表前缀不能超过 128 个字符");
        for (String table : tables) {
            String target = prefix + table + suffix;
            if (target.indexOf('$') >= 0) throw new IllegalArgumentException("Paimon 目标表名不能包含 $，该字符用于系统表：" + target);
            if (target.length() > 128) throw new IllegalArgumentException("Paimon 目标表名不能超过 128 个字符：" + target);
        }
    }

    private void csvWithPrefix(List<String> args, String name, String table, List<String> values) {
        if (!values.isEmpty()) option(args, name, table + "=" + String.join(",", values));
    }
    private void csv(List<String> args, String name, List<String> values) {
        if (!values.isEmpty()) option(args, name, String.join(",", values));
    }
    private void appendMap(List<String> args, String name, Map<String, String> values) {
        values.forEach((key, value) -> option(args, name, key + "=" + value));
    }
    private void option(List<String> args, String name, String value) {
        if (!text(value).isEmpty()) { args.add(name); args.add(value); }
    }
    private String regexLiteral(String value) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if ("\\.^$|?*+()[]{}".indexOf(character) >= 0) result.append('\\');
            result.append(character);
        }
        return result.toString();
    }
    private Endpoint endpoint(String address) {
        String value = address.split(",")[0].trim();
        if (value.startsWith("jdbc:mysql://")) value = value.substring("jdbc:mysql://".length());
        int path = value.indexOf('/');
        if (path >= 0) value = value.substring(0, path);
        int query = value.indexOf('?');
        if (query >= 0) value = value.substring(0, query);
        int split = value.lastIndexOf(':');
        if (split <= 0 || split == value.length() - 1) return new Endpoint(value, 3306);
        try { return new Endpoint(value.substring(0, split), Integer.parseInt(value.substring(split + 1))); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("Server 端口格式不正确", ex); }
    }
    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        return value instanceof Map ? new LinkedHashMap<>((Map<String, Object>) value) : new LinkedHashMap<>();
    }
    private Map<String, String> stringMap(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        objectMap(value).forEach((key, item) -> { if (!text(item).isEmpty()) result.put(key, text(item)); });
        return result;
    }
    private List<String> strings(Object value) {
        if (value instanceof Iterable) {
            List<String> result = new ArrayList<>();
            for (Object item : (Iterable<?>) value) if (!text(item).isEmpty()) result.add(text(item));
            return result;
        }
        return text(value).isEmpty() ? new ArrayList<>() : Arrays.asList(text(value));
    }
    private Long longValue(Object value) { return text(value).isEmpty() ? null : Long.valueOf(text(value)); }
    private String first(String... values) {
        for (String value : values) if (!text(value).isEmpty()) return text(value);
        return "";
    }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private <T> T required(T value, String message) {
        if (value == null) throw new IllegalArgumentException(message);
        return value;
    }
    private String required(String value, String message) {
        if (text(value).isEmpty()) throw new IllegalArgumentException(message);
        return value.trim();
    }

    private static final class Endpoint {
        private final String host;
        private final int port;
        private Endpoint(String host, int port) { this.host = host; this.port = port; }
    }

    public static final class Command {
        private final String jarPath;
        private final List<String> arguments;
        public Command(String jarPath, List<String> arguments) {
            this.jarPath = jarPath;
            this.arguments = new ArrayList<>(arguments);
        }
        public String getJarPath() { return jarPath; }
        public List<String> getArguments() { return new ArrayList<>(arguments); }
        public List<String> maskedArguments() {
            List<String> result = getArguments();
            for (int i = 0; i + 1 < result.size(); i++) {
                if ("--mysql_conf".equals(result.get(i)) && result.get(i + 1).startsWith("password=")) {
                    result.set(i + 1, "password=******");
                }
            }
            return result;
        }
    }
}
