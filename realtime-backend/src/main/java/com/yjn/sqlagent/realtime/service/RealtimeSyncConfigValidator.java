package com.yjn.sqlagent.realtime.service;

import com.yjn.sqlagent.realtime.model.SyncTaskRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 校验 MySQL CDC 同步任务固定配置、源表 Schema 以及每表键配置。 */
@Service
public class RealtimeSyncConfigValidator {
    private static final Logger LOG = LoggerFactory.getLogger(RealtimeSyncConfigValidator.class);
    private static final long MIN_PROCESS_MEMORY_BYTES = 1024L * 1024L * 1024L;
    private static final Pattern MEMORY = Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)\\s*([A-Za-z]+)$");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Set<String> CDC_MODES = Set.of("combined");
    private static final Set<String> METADATA_COLUMNS = Set.of("database_name", "table_name", "op_ts");
    private static final String METADATA_COLUMN_PREFIX = "__meta_";
    private static final Set<String> TYPE_MAPPINGS = Set.of(
            "to-nullable", "to-string", "char-to-string", "tinyint1-not-bool",
            "longtext-to-bytes", "bigint-unsigned-to-bigint");
    private static final Set<String> NULL_PROPAGATING_COMPUTED_FUNCTIONS = Set.of(
            "year", "month", "day", "hour", "minute", "second", "date_format",
            "substring", "truncate", "cast", "upper", "lower", "trim");
    private final RealtimeServerService servers;

    public RealtimeSyncConfigValidator(RealtimeServerService servers) {
        this.servers = servers;
    }

    public void validate(SyncTaskRequest request) {
        if (request == null) return;
        validate(request.getSourceType(), request.getSourceServerId(), request.getTaskConfig());
    }

    public void validateTask(Map<String, Object> task) {
        if (task == null) return;
        validate(text(task.get("sourceType")), longValue(task.get("sourceServerId")),
                objectMap(task.get("taskConfig"), "同步任务配置格式不正确"));
    }

    public void validate(String sourceType, Long sourceServerId, Map<String, Object> taskConfig) {
        if (!"mysql-cdc".equals(sourceType) || taskConfig == null) return;
        Map<String, Object> cdc = objectMap(taskConfig.get("cdcConfig"), "MySQL CDC 配置格式不正确");
        rejectLegacyKeys(cdc);
        validateFixedConfig(taskConfig, cdc);
        List<String> selectedTables = strings(cdc.get("selectedTables"), true, "源表列表");
        Set<String> metadataColumns = prefixedMetadataColumns();
        Map<String, Object> tableConfigs = tableConfigs(cdc.get("tableConfigs"));
        for (String table : tableConfigs.keySet()) {
            if (!selectedTables.contains(table)) throw new IllegalArgumentException("私有配置表不在已选源表中：" + table);
        }
        if (sourceServerId == null) throw new IllegalArgumentException("请选择 MySQL Server");

        Set<String> commonFields = null;
        long schemaStarted = System.nanoTime();
        Map<String, Map<String, Object>> schemas;
        try {
            schemas = servers.schemas(sourceServerId, selectedTables);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("读取 MySQL CDC 源表结构失败：" + safe(ex), ex);
        }
        LOG.info("sync_validation_stage stage=mysql_schema_read serverId={} tableCount={} costMs={}",
                sourceServerId, selectedTables.size(), elapsedMs(schemaStarted));
        for (String table : selectedTables) {
            Map<String, Object> schema = schemas.get(table);
            if (schema == null) throw new IllegalArgumentException("读取 MySQL CDC 源表结构失败（" + table + "）：未返回表结构");
            validateTable(table, schema, tableConfigs.get(table), cdc.get("tableConfOverrides"), metadataColumns);
            Set<String> fields = fields(schema);
            if (commonFields == null) commonFields = new LinkedHashSet<>(fields);
            else commonFields.retainAll(fields);
        }
        validateTableFieldReferences(cdc.get("tableConfOverrides"),
                commonFields == null ? Set.of() : commonFields, metadataColumns);
    }

    private void validateFixedConfig(Map<String, Object> taskConfig, Map<String, Object> cdc) {
        validateProcessMemory(taskConfig.get("taskManagerMemory"), "TaskManager");
        validateProcessMemory(taskConfig.get("jobManagerMemory"), "JobManager");
        validateAllowedValue(cdc.get("mode"), CDC_MODES, "CDC mode");
        validateAllowedValues(cdc.get("metadataColumns"), METADATA_COLUMNS, "元数据列");
        validateAllowedValues(cdc.get("typeMappings"), TYPE_MAPPINGS, "类型映射");
        Object ignoreIncompatible = cdc.get("ignoreIncompatible");
        if (ignoreIncompatible != null && !(ignoreIncompatible instanceof Boolean)) {
            throw new IllegalArgumentException("ignoreIncompatible 必须是布尔值");
        }
    }

    private void validateProcessMemory(Object value, String component) {
        String memory = text(value);
        if (memory.isEmpty()) throw new IllegalArgumentException(component + " 内存不能为空");
        Matcher matcher = MEMORY.matcher(memory);
        if (!matcher.matches()) throw new IllegalArgumentException(component + " 内存必须包含单位，例如 1GB 或 2048MB");
        long multiplier;
        switch (matcher.group(2).toUpperCase(Locale.ROOT)) {
            case "B": multiplier = 1L; break;
            case "K": case "KB": case "KIB": multiplier = 1024L; break;
            case "M": case "MB": case "MIB": multiplier = 1024L * 1024L; break;
            case "G": case "GB": case "GIB": multiplier = 1024L * 1024L * 1024L; break;
            case "T": case "TB": case "TIB": multiplier = 1024L * 1024L * 1024L * 1024L; break;
            default: throw new IllegalArgumentException(component + " 内存格式错误：" + memory);
        }
        if (new BigDecimal(matcher.group(1)).multiply(BigDecimal.valueOf(multiplier))
                .compareTo(BigDecimal.valueOf(MIN_PROCESS_MEMORY_BYTES)) < 0) {
            throw new IllegalArgumentException(component + " 内存不能小于 1GB：" + memory);
        }
    }

    private void validateAllowedValue(Object value, Set<String> allowed, String label) {
        String configured = text(value);
        if (!configured.isEmpty() && !allowed.contains(configured)) {
            throw new IllegalArgumentException(label + "不支持该选项：" + configured);
        }
    }

    private void validateAllowedValues(Object value, Set<String> allowed, String label) {
        for (String configured : strings(value, false, label)) {
            if (!allowed.contains(configured)) throw new IllegalArgumentException(label + "不支持该选项：" + configured);
        }
    }

    private void rejectLegacyKeys(Map<String, Object> cdc) {
        if (hasValue(cdc.get("primaryKeys")) || hasValue(cdc.get("partitionKeys"))) {
            throw new IllegalArgumentException("同步任务已不支持公共主键或公共分区键，请先执行配置迁移");
        }
    }

    private void validateTable(String table, Map<String, Object> schema, Object rawConfig,
            Object rawOverrides, Set<String> metadataColumns) {
        Map<String, Object> config = rawConfig == null ? Map.of()
                : objectMap(rawConfig, "源表 " + table + " 的私有配置格式不正确");
        List<String> customPrimaryKeys = config.containsKey("primaryKeys")
                ? strings(config.get("primaryKeys"), true, table + " 主键") : List.of();
        List<String> partitionKeys = config.containsKey("partitionKeys")
                ? strings(config.get("partitionKeys"), true, table + " 分区键") : List.of();
        Map<String, Map<String, Object>> sourceColumns = columns(schema);
        Set<String> sourceFields = sourceColumns.keySet();
        Map<String, ComputedColumnDefinition> computedColumns = validateComputedColumns(
                table, config.get("computedColumns"), sourceColumns);
        Set<String> finalFields = new LinkedHashSet<>(sourceFields);
        finalFields.addAll(computedColumns.keySet());
        for (String metadataColumn : metadataColumns) {
            if (finalFields.contains(metadataColumn)) {
                throw new IllegalArgumentException(
                        "源表 " + table + " 的字段或计算列与同步元数据列重名：" + metadataColumn);
            }
        }
        List<String> sourcePrimaryKeys = strings(schema.get("primaryKeys"), false, table + " 源表主键");
        if (sourcePrimaryKeys.isEmpty() && customPrimaryKeys.isEmpty()) {
            throw new IllegalArgumentException("MySQL CDC 源表无主键，请配置私有主键：" + table);
        }
        List<String> effectivePrimaryKeys = customPrimaryKeys.isEmpty() ? sourcePrimaryKeys : customPrimaryKeys;
        validateFields(table, "主键", customPrimaryKeys, finalFields);
        validateFields(table, "分区键", partitionKeys, finalFields);
        validateBucketFunction(table, rawOverrides, effectivePrimaryKeys, schema);
        if (!effectivePrimaryKeys.containsAll(partitionKeys)) {
            throw new IllegalArgumentException("源表 " + table + " 的分区键必须包含在最终主键中");
        }
        if (!partitionKeys.isEmpty() && partitionKeys.containsAll(effectivePrimaryKeys)) {
            throw new IllegalArgumentException("源表 " + table + " 的分区键不能覆盖全部最终主键，请至少保留一个非分区主键字段");
        }
        validateKeyNullability(table, effectivePrimaryKeys, partitionKeys, sourceColumns, computedColumns);
    }

    private Map<String, ComputedColumnDefinition> validateComputedColumns(String table, Object value,
            Map<String, Map<String, Object>> sourceColumns) {
        List<String> expressions = computedExpressions(value);
        Map<String, ComputedColumnDefinition> definitionsByName = new LinkedHashMap<>();
        Set<String> definitions = new LinkedHashSet<>();
        for (String expression : expressions) {
            if (!definitions.add(expression)) throw new IllegalArgumentException("源表 " + table + " 的计算列表达式不能重复：" + expression);
            int equals = expression.indexOf('=');
            if (equals <= 0) {
                if (equals == 0) throw new IllegalArgumentException("缺少计算列名，请填写：create_date=" + expression.substring(1).trim());
                if (expression.matches("[A-Za-z_][A-Za-z0-9_]*\\s*\\(.*")) {
                    throw new IllegalArgumentException("缺少计算列名，请填写：create_date=" + expression);
                }
                throw new IllegalArgumentException("源表 " + table + " 的计算列表达式格式错误：" + expression);
            }
            int open = expression.indexOf('(', equals + 1);
            int close = expression.lastIndexOf(')');
            if (open <= equals + 1 || close != expression.length() - 1 || !balancedFunctionCall(expression, open)) {
                throw new IllegalArgumentException("源表 " + table + " 的计算列表达式格式错误：" + expression);
            }
            String name = expression.substring(0, equals).trim();
            if (!IDENTIFIER.matcher(name).matches()) throw new IllegalArgumentException("源表 " + table + " 的计算列名称格式错误：" + name);
            if (definitionsByName.containsKey(name)) throw new IllegalArgumentException("源表 " + table + " 的计算列名称不能重复：" + name);
            if (sourceColumns.containsKey(name)) throw new IllegalArgumentException("源表 " + table + " 的计算列名称与源字段重复：" + name);
            String functionName = expression.substring(equals + 1, open).trim();
            if (!IDENTIFIER.matcher(functionName).matches()) throw new IllegalArgumentException("源表 " + table + " 的计算列函数名称格式错误：" + functionName);
            List<String> arguments = splitArguments(expression.substring(open + 1, close).trim());
            if (arguments == null) throw new IllegalArgumentException("源表 " + table + " 的计算列表达式格式错误：" + expression);
            if ("date_format".equals(functionName) && arguments.size() >= 2 && quoted(arguments.get(1))) {
                throw new IllegalArgumentException("date_format 格式参数不需要单引号或双引号");
            }
            String referenceField = arguments.isEmpty() ? null : arguments.get(0);
            if (referenceField != null && !sourceColumns.containsKey(referenceField)) {
                throw new IllegalArgumentException("源表 " + table + " 的计算列引用字段不存在：" + arguments.get(0));
            }
            boolean safeAsKey = "now".equals(functionName) && arguments.isEmpty();
            if (NULL_PROPAGATING_COMPUTED_FUNCTIONS.contains(functionName) && referenceField != null) {
                safeAsKey = Boolean.FALSE.equals(sourceColumns.get(referenceField).get("nullable"));
            }
            definitionsByName.put(name, new ComputedColumnDefinition(
                    expression, functionName, referenceField, safeAsKey));
        }
        return definitionsByName;
    }

    private void validateKeyNullability(String table, List<String> primaryKeys, List<String> partitionKeys,
            Map<String, Map<String, Object>> sourceColumns,
            Map<String, ComputedColumnDefinition> computedColumns) {
        for (String key : primaryKeys) {
            String keyType = partitionKeys.contains(key) ? "主键/分区键" : "主键";
            ComputedColumnDefinition computed = computedColumns.get(key);
            if (computed != null) {
                if (!computed.safeAsKey) throw new IllegalArgumentException(computed.nullabilityError(table, keyType));
                continue;
            }
            Map<String, Object> source = sourceColumns.get(key);
            if (source == null || !Boolean.FALSE.equals(source.get("nullable"))) {
                throw new IllegalArgumentException("源表 " + table + " 的" + keyType + "字段 " + key
                        + " 允许 NULL，不能作为 Paimon " + keyType);
            }
        }
    }

    private static final class ComputedColumnDefinition {
        private final String expression;
        private final String functionName;
        private final String referenceField;
        private final boolean safeAsKey;

        private ComputedColumnDefinition(String expression, String functionName,
                String referenceField, boolean safeAsKey) {
            this.expression = expression;
            this.functionName = functionName;
            this.referenceField = referenceField;
            this.safeAsKey = safeAsKey;
        }

        private String nullabilityError(String table, String keyType) {
            if (NULL_PROPAGATING_COMPUTED_FUNCTIONS.contains(functionName) && referenceField != null) {
                return "源表 " + table + " 的计算列 " + expression + " 引用字段 " + referenceField
                        + " 允许 NULL，结果可能为空，不能作为" + keyType;
            }
            return "源表 " + table + " 的计算列 " + expression + " 无法证明结果非空，不能作为" + keyType;
        }
    }

    private void validateBucketFunction(String table, Object rawOverrides, List<String> effectivePrimaryKeys,
            Map<String, Object> schema) {
        if (!(rawOverrides instanceof Map<?, ?>)) return;
        Map<?, ?> overrides = (Map<?, ?>) rawOverrides;
        if (!"mod".equalsIgnoreCase(text(overrides.get("bucket-function.type")))) return;
        List<String> bucketKeys = hasValue(overrides.get("bucket-key"))
                ? strings(overrides.get("bucket-key"), true, "Bucket Key") : effectivePrimaryKeys;
        if (bucketKeys.size() != 1) {
            throw new IllegalArgumentException("源表 " + table + " 使用 mod 分桶函数时，Bucket Key 必须且只能包含一个字段");
        }
        String type = columnType(schema, bucketKeys.get(0));
        String base = type.replaceAll("\\(.*$", "").trim().toLowerCase(Locale.ROOT);
        if (!("int".equals(base) || "integer".equals(base) || "bigint".equals(base))) {
            throw new IllegalArgumentException("源表 " + table + " 使用 mod 分桶函数时，Bucket Key " + bucketKeys.get(0)
                    + " 必须是 INT 或 BIGINT 类型，当前类型：" + (type.isEmpty() ? "未知" : type));
        }
    }

    private Set<String> prefixedMetadataColumns() {
        Set<String> result = new LinkedHashSet<>();
        for (String column : METADATA_COLUMNS) result.add(METADATA_COLUMN_PREFIX + column);
        return result;
    }

    private void validateTableFieldReferences(Object value, Set<String> fields, Set<String> metadataColumns) {
        if (!(value instanceof Map<?, ?>)) return;
        Map<?, ?> overrides = (Map<?, ?>) value;
        validateReferencedFields(overrides, "bucket-key", "Bucket Key", fields);
        Set<String> sequenceFields = new LinkedHashSet<>(fields);
        sequenceFields.addAll(metadataColumns);
        validateReferencedFields(overrides, "sequence.field", "Sequence Field", "目标字段", sequenceFields);
        validateReferencedFields(overrides, "changelog-producer.row-deduplicate-ignore-fields", "Row Deduplicate Ignore Fields", fields);
    }

    private void validateReferencedFields(Map<?, ?> overrides, String key, String label, Set<String> fields) {
        validateReferencedFields(overrides, key, label, "源字段", fields);
    }

    private void validateReferencedFields(Map<?, ?> overrides, String key, String label,
            String fieldScope, Set<String> fields) {
        if (!overrides.containsKey(key)) return;
        for (String reference : strings(overrides.get(key), true, label)) {
            if (!fields.contains(reference)) throw new IllegalArgumentException(label + " 引用的" + fieldScope + "不存在：" + reference);
        }
    }

    private void validateFields(String table, String keyType, List<String> keys, Set<String> fields) {
        for (String key : keys) {
            if (!fields.contains(key)) throw new IllegalArgumentException("源表 " + table + " 的" + keyType + "字段不存在：" + key);
        }
    }

    private Set<String> fields(Map<String, Object> schema) {
        return columns(schema).keySet();
    }

    private Map<String, Map<String, Object>> columns(Map<String, Object> schema) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        Object columns = schema.get("columns");
        if (columns instanceof Iterable<?>) {
            for (Object column : (Iterable<?>) columns) {
                Map<String, Object> item = objectMap(column, "源表字段格式不正确");
                String name = text(item.get("name"));
                if (!name.isEmpty()) result.put(name, item);
            }
        }
        return result;
    }

    private String columnType(Map<String, Object> schema, String field) {
        Object columns = schema.get("columns");
        if (columns instanceof Iterable<?>) {
            for (Object column : (Iterable<?>) columns) {
                Map<String, Object> item = objectMap(column, "源表字段格式不正确");
                if (field.equals(text(item.get("name")))) return text(item.get("type"));
            }
        }
        return "";
    }

    private boolean balancedFunctionCall(String expression, int openIndex) {
        int depth = 0; char quote = 0;
        for (int index = openIndex; index < expression.length(); index++) {
            char character = expression.charAt(index);
            if (quote != 0) {
                if (character == quote && (index == 0 || expression.charAt(index - 1) != '\\')) quote = 0;
                continue;
            }
            if (character == '\'' || character == '"') quote = character;
            else if (character == '(') depth++;
            else if (character == ')') {
                depth--;
                if (depth < 0 || (depth == 0 && index != expression.length() - 1)) return false;
            }
        }
        return depth == 0 && quote == 0;
    }

    private List<String> splitArguments(String value) {
        if (value.isEmpty()) return List.of();
        List<String> result = new ArrayList<>();
        int start = 0; int depth = 0; char quote = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (quote != 0) {
                if (character == quote && (index == 0 || value.charAt(index - 1) != '\\')) quote = 0;
                continue;
            }
            if (character == '\'' || character == '"') quote = character;
            else if (character == '(') depth++;
            else if (character == ')') { depth--; if (depth < 0) return null; }
            else if (character == ',' && depth == 0) {
                String argument = value.substring(start, index).trim();
                if (argument.isEmpty()) return null;
                result.add(argument); start = index + 1;
            }
        }
        String last = value.substring(start).trim();
        if (depth != 0 || quote != 0 || last.isEmpty()) return null;
        result.add(last); return result;
    }

    private List<String> computedExpressions(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof Iterable<?>)) return text(value).isEmpty() ? List.of() : List.of(text(value));
        List<String> result = new ArrayList<>();
        for (Object item : (Iterable<?>) value) {
            String expression = text(item);
            if (expression.isEmpty()) throw new IllegalArgumentException("计算列不能为空");
            result.add(expression);
        }
        return result;
    }

    private Map<String, Object> tableConfigs(Object value) {
        return value == null ? Map.of() : objectMap(value, "每表私有配置格式不正确");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value, String message) {
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException(message);
        return (Map<String, Object>) value;
    }

    private List<String> strings(Object value, boolean rejectEmpty, String fieldName) {
        List<String> result = new ArrayList<>();
        if (value instanceof Iterable<?>) {
            for (Object item : (Iterable<?>) value) {
                String itemText = text(item);
                if (itemText.isEmpty()) { if (rejectEmpty) throw new IllegalArgumentException(fieldName + "不能包含空字段"); }
                else result.add(itemText);
            }
        } else if (!text(value).isEmpty()) {
            for (String item : text(value).split(",")) if (!item.trim().isEmpty()) result.add(item.trim());
        }
        if (rejectEmpty && result.isEmpty()) throw new IllegalArgumentException(fieldName + "不能为空");
        if (new LinkedHashSet<>(result).size() != result.size()) throw new IllegalArgumentException(fieldName + "不能包含重复字段");
        return result;
    }

    private boolean hasValue(Object value) {
        if (value instanceof Map<?, ?>) return !((Map<?, ?>) value).isEmpty();
        if (value instanceof Iterable<?>) return ((Iterable<?>) value).iterator().hasNext();
        return !text(value).isEmpty();
    }

    private boolean quoted(String value) {
        String content = value.trim();
        return content.length() >= 2 && ((content.startsWith("'") && content.endsWith("'"))
                || (content.startsWith("\"") && content.endsWith("\"")));
    }

    private Long longValue(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        try { return text(value).isEmpty() ? null : Long.parseLong(text(value)); }
        catch (NumberFormatException ex) { return null; }
    }

    private String safe(RuntimeException ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    private long elapsedMs(long started) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
