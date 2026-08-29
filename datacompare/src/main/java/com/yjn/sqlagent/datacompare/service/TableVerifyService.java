package com.yjn.sqlagent.datacompare.service;

import com.yjn.sqlagent.datacompare.config.DataCompareProperties;
import com.yjn.sqlagent.datacompare.model.CompareRule;
import com.yjn.sqlagent.datacompare.model.TableColumn;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class TableVerifyService {
    private static final int PARTITION_LIMIT = 30;
    private static final int SAMPLE_LIMIT = 20;
    private static final int CRC_COLUMN_CHUNK = 40;
    private final HiveJdbcClient hive;
    private final String tempDatabase;

    public TableVerifyService(HiveJdbcClient hive, DataCompareProperties properties) {
        this.hive = hive;
        this.tempDatabase = properties.getHiveTempDatabase();
    }

    public Map<String, Object> verify(long verifyId, String baseline, String candidate, CompareRule rule,
                                      boolean onlySameColumns, Consumer<Statement> listener,
                                      Consumer<String> log) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        List<TableColumn> baselineColumns = hive.columns(baseline, listener);
        List<TableColumn> candidateColumns = hive.columns(candidate, listener);
        List<Map<String, Object>> metadataDiff = metadataDiff(baselineColumns, candidateColumns);
        result.put("metadataSame", metadataDiff.isEmpty());
        result.put("metadataDiff", metadataDiff);
        log.accept("元数据差异字段数=" + metadataDiff.size());

        List<TableColumn> baselinePartitions = baselineColumns.stream().filter(TableColumn::isPartitionKey)
                .collect(Collectors.toList());
        List<TableColumn> candidatePartitions = candidateColumns.stream().filter(TableColumn::isPartitionKey)
                .collect(Collectors.toList());
        if (!sameSchema(baselinePartitions, candidatePartitions)) {
            result.put("isPartTable", !baselinePartitions.isEmpty() || !candidatePartitions.isEmpty());
            result.put("partitionSetsSame", false);
            result.put("validationIssue", "两侧分区字段不一致，未扫描业务数据");
            return result;
        }
        boolean partitioned = !baselinePartitions.isEmpty();
        result.put("isPartTable", partitioned);
        List<String> baselineParts = partitioned ? hive.partitions(baseline, listener) : Collections.emptyList();
        List<String> candidateParts = partitioned ? hive.partitions(candidate, listener) : Collections.emptyList();
        result.put("partitionCounts", baselineParts.size() + "/" + candidateParts.size());
        Set<String> commonParts = new LinkedHashSet<>(baselineParts);
        commonParts.retainAll(candidateParts);
        List<String> scopedParts = commonParts.stream().sorted(Comparator.reverseOrder()).limit(PARTITION_LIMIT)
                .collect(Collectors.toList());
        Map<String, Object> partitionScope = new LinkedHashMap<>();
        partitionScope.put("baselineCount", baselineParts.size());
        partitionScope.put("candidateCount", candidateParts.size());
        boolean partitionSetsSame = new LinkedHashSet<>(baselineParts).equals(new LinkedHashSet<>(candidateParts));
        partitionScope.put("partitionSetsSame", partitionSetsSame);
        partitionScope.put("compared", scopedParts);
        partitionScope.put("limit", PARTITION_LIMIT);
        partitionScope.put("complete", !partitioned || (partitionSetsSame && commonParts.size() <= PARTITION_LIMIT));
        result.put("partitionScope", partitionScope);
        result.put("partitionSetsSame", partitionScope.get("partitionSetsSame"));
        if (partitioned && commonParts.isEmpty() && (!baselineParts.isEmpty() || !candidateParts.isEmpty())) {
            result.put("validationIssue", "两侧没有共同分区，未扫描业务数据");
            return result;
        }

        Map<String, TableColumn> baselineMap = mapColumns(baselineColumns);
        Map<String, TableColumn> candidateMap = mapColumns(candidateColumns);
        List<String> commonColumns = baselineMap.keySet().stream().filter(candidateMap::containsKey)
                .filter(name -> !baselineMap.get(name).isPartitionKey()).collect(Collectors.toList());
        Set<String> allCommonColumns = baselineMap.keySet().stream().filter(candidateMap::containsKey)
                .collect(Collectors.toSet());
        List<String> invalidRuleColumns = new ArrayList<>();
        invalidRuleColumns.addAll(invalidColumns(rule.getPrimaryKeyList(), allCommonColumns));
        invalidRuleColumns.addAll(invalidColumns(rule.getCompareColumnList(), commonColumns));
        invalidRuleColumns.addAll(invalidColumns(rule.getProbeColumnList(), commonColumns));
        if (!invalidRuleColumns.isEmpty()) {
            result.put("validationIssue", "验数规则包含不存在或非同名字段："
                    + String.join(",", new LinkedHashSet<>(invalidRuleColumns)));
            return result;
        }
        List<String> compareColumns = selectedColumns(rule.getCompareColumnList(), commonColumns);
        if (compareColumns.isEmpty()) compareColumns = new ArrayList<>(commonColumns);
        if (!onlySameColumns && !metadataDiff.isEmpty() && rule.getCompareColumnList().isEmpty()) {
            result.put("validationIssue", "字段元数据不一致且未启用同名列比较");
            return result;
        }
        String baselineFilter = partitionFilter(scopedParts);
        String candidateFilter = partitionFilter(scopedParts);
        Map<String, Map<String, Object>> aggregates = aggregatePair(
                baseline, candidate, compareColumns, baselineFilter, candidateFilter, listener);
        Map<String, Object> left = aggregates.get("baseline");
        Map<String, Object> right = aggregates.get("candidate");
        boolean rowSame = number(left.get("row_count")) == number(right.get("row_count"));
        boolean crcSame = left.get("crc_chunks").equals(right.get("crc_chunks"));
        result.put("rowCountSame", rowSame);
        result.put("rowCounts", pair(left.get("row_count"), right.get("row_count")));
        result.put("crcSame", crcSame);
        result.put("crcValues", pair(left.get("crc_chunks"), right.get("crc_chunks")));
        log.accept("行数=" + left.get("row_count") + "/" + right.get("row_count")
                + " CRC32字段组=" + ((List<?>) left.get("crc_chunks")).size());

        if (!rowSame || !crcSame) {
            List<String> probeColumns = selectedColumns(rule.getProbeColumnList(), compareColumns);
            if (probeColumns.isEmpty()) probeColumns = compareColumns.stream().limit(50).collect(Collectors.toList());
            result.put("columnProbe", probe(baseline, candidate, probeColumns, baselineMap,
                    baselineFilter, candidateFilter, listener));
            if (!rule.getPrimaryKeyList().isEmpty()) {
                try {
                    validateKeys(baseline, candidate, rule, baselineFilter, candidateFilter, listener);
                    result.putAll(diff(verifyId, baseline, candidate, rule, compareColumns,
                            baselineFilter, candidateFilter, listener));
                } catch (DataMismatchException mismatch) {
                    result.put("validationIssue", mismatch.getMessage());
                    result.put("difference", Collections.singletonMap("skippedReason", mismatch.getMessage()));
                }
            } else {
                result.put("difference", Collections.singletonMap("skippedReason", "未选择业务键，未执行逐行差异定位"));
            }
        } else {
            result.put("columnProbe", Collections.emptyList());
            result.put("difference", Collections.emptyMap());
            if (!rule.getPrimaryKeyList().isEmpty()) {
                try {
                    validateKeys(baseline, candidate, rule, baselineFilter, candidateFilter, listener);
                } catch (DataMismatchException mismatch) {
                    result.put("validationIssue", mismatch.getMessage());
                }
            }
        }
        return result;
    }

    private List<Map<String, Object>> metadataDiff(List<TableColumn> left, List<TableColumn> right) {
        Map<String, TableColumn> leftMap = mapColumns(left);
        Map<String, TableColumn> rightMap = mapColumns(right);
        Set<String> names = new LinkedHashSet<>(leftMap.keySet()); names.addAll(rightMap.keySet());
        List<Map<String, Object>> result = new ArrayList<>();
        for (String name : names) {
            TableColumn l = leftMap.get(name); TableColumn r = rightMap.get(name);
            if (l == null || r == null || !l.getType().equalsIgnoreCase(r.getType()) || l.isPartitionKey() != r.isPartitionKey()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("column", name); item.put("baselineType", l == null ? null : l.getType());
                item.put("candidateType", r == null ? null : r.getType());
                item.put("baselineExists", l != null); item.put("candidateExists", r != null);
                item.put("partitionKey", l != null ? l.isPartitionKey() : r != null && r.isPartitionKey());
                result.add(item);
            }
        }
        return result;
    }

    private Map<String, Map<String, Object>> aggregatePair(String baseline, String candidate, List<String> columns,
                                                           String baselineFilter, String candidateFilter,
                                                           Consumer<Statement> listener) throws Exception {
        StringBuilder select = new StringBuilder("SELECT compare_side,COUNT(*) row_count");
        List<List<String>> groups = new ArrayList<>();
        for (int start = 0; start < columns.size(); start += CRC_COLUMN_CHUNK) {
            List<String> group = new ArrayList<>(
                    columns.subList(start, Math.min(columns.size(), start + CRC_COLUMN_CHUNK)));
            groups.add(group);
            String values = java.util.stream.IntStream.range(start, start + group.size())
                    .mapToObj(index -> "COALESCE(v" + index + ",'\\\\N')")
                    .collect(Collectors.joining(","));
            select.append(",CAST(COALESCE(SUM(CAST(CRC32(CONCAT_WS('\\u0001',")
                    .append(values).append(")) AS DECIMAL(38,0))),0) AS STRING) crc_")
                    .append(groups.size() - 1);
        }
        String projected = java.util.stream.IntStream.range(0, columns.size())
                .mapToObj(index -> "CAST(" + HiveJdbcClient.quoteColumn(columns.get(index))
                        + " AS STRING) v" + index).collect(Collectors.joining(","));
        String suffix = projected.isEmpty() ? "" : "," + projected;
        String paired = "SELECT 0 compare_side" + suffix + " FROM " + HiveJdbcClient.quote(baseline)
                + baselineFilter + " UNION ALL SELECT 1 compare_side" + suffix + " FROM "
                + HiveJdbcClient.quote(candidate) + candidateFilter;
        List<Map<String, Object>> rows = hive.query(select.append(" FROM (").append(paired)
                .append(") p GROUP BY compare_side").toString(), listener);
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        result.put("baseline", aggregateResult(findSide(rows, 0), groups));
        result.put("candidate", aggregateResult(findSide(rows, 1), groups));
        return result;
    }

    private Map<String, Object> aggregateResult(Map<String, Object> aggregate, List<List<String>> groups) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("row_count", aggregate.getOrDefault("row_count", 0));
        List<Map<String, Object>> chunks = new ArrayList<>();
        for (int index = 0; index < groups.size(); index++) {
            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("columns", groups.get(index));
            chunk.put("crc32", string(aggregate.get("crc_" + index)));
            chunks.add(chunk);
        }
        if (groups.isEmpty()) {
            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("columns", Collections.emptyList()); chunk.put("crc32", "0"); chunks.add(chunk);
        }
        result.put("crc_chunks", chunks);
        return result;
    }

    private List<Map<String, Object>> probe(String baseline, String candidate, List<String> columns,
                                             Map<String, TableColumn> metadata, String baselineFilter,
                                             String candidateFilter, Consumer<Statement> listener) throws Exception {
        Map<String, Map<String, Map<String, Object>>> paired = probePair(
                baseline, candidate, columns, metadata, baselineFilter, candidateFilter, listener);
        Map<String, Map<String, Object>> baselineMetrics = paired.get("baseline");
        Map<String, Map<String, Object>> candidateMetrics = paired.get("candidate");
        List<Map<String, Object>> result = new ArrayList<>();
        for (String column : columns) {
            TableColumn definition = metadata.get(column.toLowerCase(Locale.ROOT));
            Map<String, Object> left = baselineMetrics.getOrDefault(column, Collections.emptyMap());
            Map<String, Object> right = candidateMetrics.getOrDefault(column, Collections.emptyMap());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("column", column); item.put("type", definition == null ? null : definition.getType());
            item.put("baseline", left); item.put("candidate", right); item.put("same", left.equals(right));
            result.add(item);
        }
        return result;
    }

    private Map<String, Map<String, Map<String, Object>>> probePair(
            String baseline, String candidate, List<String> columns, Map<String, TableColumn> metadata,
            String baselineFilter, String candidateFilter, Consumer<Statement> listener) throws Exception {
        Map<String, Map<String, Map<String, Object>>> empty = new LinkedHashMap<>();
        empty.put("baseline", Collections.emptyMap()); empty.put("candidate", Collections.emptyMap());
        if (columns.isEmpty()) return empty;
        List<Boolean> numericFlags = new ArrayList<>();
        List<String> projected = new ArrayList<>();
        for (int index = 0; index < columns.size(); index++) {
            TableColumn definition = metadata.get(columns.get(index).toLowerCase(Locale.ROOT));
            boolean numeric = definition != null && definition.getType().matches(
                    "(?i).*(tinyint|smallint|int|bigint|float|double|decimal).*");
            numericFlags.add(numeric);
            projected.add("CAST(" + HiveJdbcClient.quoteColumn(columns.get(index)) + " AS "
                    + (numeric ? "DOUBLE" : "STRING") + ") p" + index);
        }
        String projection = String.join(",", projected);
        String source = "SELECT 0 compare_side," + projection + " FROM " + HiveJdbcClient.quote(baseline)
                + baselineFilter + " UNION ALL SELECT 1 compare_side," + projection + " FROM "
                + HiveJdbcClient.quote(candidate) + candidateFilter;
        StringBuilder select = new StringBuilder("SELECT compare_side,");
        for (int index = 0; index < columns.size(); index++) {
            if (index > 0) select.append(',');
            String col = "p" + index;
            String prefix = "p" + index + "_";
            select.append("SUM(CASE WHEN ").append(col).append(" IS NULL THEN 1 ELSE 0 END) ")
                    .append(prefix).append("null_count,MIN(").append(col).append(") ")
                    .append(prefix).append("min_value,MAX(").append(col).append(") ")
                    .append(prefix).append("max_value");
            if (numericFlags.get(index)) select.append(",SUM(CASE WHEN ").append(col).append("=0 THEN 1 ELSE 0 END) ")
                    .append(prefix).append("zero_count,SUM(CASE WHEN ").append(col)
                    .append("<0 THEN 1 ELSE 0 END) ").append(prefix).append("negative_count,AVG(")
                    .append(col).append(") ").append(prefix).append("avg_value,PERCENTILE_APPROX(CAST(")
                    .append(col).append(" AS DOUBLE),0.5) ").append(prefix).append("median_value");
        }
        List<Map<String, Object>> rows = hive.query(select.append(" FROM (").append(source)
                .append(") p GROUP BY compare_side").toString(), listener);
        Map<String, Map<String, Map<String, Object>>> result = new LinkedHashMap<>();
        result.put("baseline", probeResult(findSide(rows, 0), columns));
        result.put("candidate", probeResult(findSide(rows, 1), columns));
        return result;
    }

    private Map<String, Map<String, Object>> probeResult(Map<String, Object> values, List<String> columns) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (int index = 0; index < columns.size(); index++) {
            String prefix = "p" + index + "_";
            Map<String, Object> metrics = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                if (entry.getKey().startsWith(prefix)) {
                    metrics.put(entry.getKey().substring(prefix.length()), entry.getValue());
                }
            }
            result.put(columns.get(index), metrics);
        }
        return result;
    }

    private Map<String, Object> findSide(List<Map<String, Object>> rows, int side) {
        for (Map<String, Object> row : rows) {
            if (number(row.get("compare_side")) == side) return row;
        }
        return Collections.emptyMap();
    }

    private void validateKeys(String baseline, String candidate, CompareRule rule, String baselineFilter,
                              String candidateFilter, Consumer<Statement> listener) throws Exception {
        String keys = rule.getPrimaryKeyList().stream().map(HiveJdbcClient::quoteColumn)
                .collect(Collectors.joining(","));
        String nullCondition = rule.getPrimaryKeyList().stream()
                .map(key -> "k." + HiveJdbcClient.quoteColumn(key) + " IS NULL")
                .collect(Collectors.joining(" OR "));
        String source = "SELECT 0 compare_side," + keys + " FROM " + HiveJdbcClient.quote(baseline)
                + baselineFilter + " UNION ALL SELECT 1 compare_side," + keys + " FROM "
                + HiveJdbcClient.quote(candidate) + candidateFilter;
        String grouped = "SELECT compare_side," + keys + ",COUNT(*) n FROM (" + source
                + ") s GROUP BY compare_side," + keys;
        String check = "SELECT compare_side,COALESCE(SUM(CASE WHEN " + nullCondition
                + " THEN n ELSE 0 END),0) null_count,COALESCE(SUM(CASE WHEN n>1 THEN 1 ELSE 0 END),0) duplicate_groups FROM ("
                + grouped + ") k GROUP BY compare_side";
        List<Map<String, Object>> rows = hive.query(check, listener);
        for (int side = 0; side < 2; side++) {
            String table = side == 0 ? baseline : candidate;
            Map<String, Object> counts = findSide(rows, side);
            if (!rule.isIgnoreNullPrimaryKey() && number(counts.get("null_count")) > 0) {
                throw new DataMismatchException("业务键存在NULL：" + table);
            }
            if (number(counts.get("duplicate_groups")) > 0) {
                throw new DataMismatchException("业务键不唯一：" + table);
            }
        }
    }

    private Map<String, Object> diff(long verifyId, String baseline, String candidate, CompareRule rule,
                                     List<String> compareColumns, String baselineFilter, String candidateFilter,
                                     Consumer<Statement> listener) throws Exception {
        // 每次重跑使用新表，避免覆盖或删除历史差异证据。
        String diffTable = tempDatabase + ".dc_diff_" + verifyId + "_" + Long.toString(System.nanoTime(), 36);
        List<String> keys = rule.getPrimaryKeyList();
        List<String> selected = new ArrayList<>(keys);
        for (String column : compareColumns) if (!selected.contains(column)) selected.add(column);
        String select = selected.stream().map(column -> "b." + HiveJdbcClient.quoteColumn(column) + " AS "
                + HiveJdbcClient.quoteColumn("baseline_" + column) + ",c." + HiveJdbcClient.quoteColumn(column)
                + " AS " + HiveJdbcClient.quoteColumn("candidate_" + column)).collect(Collectors.joining(","));
        String join = keys.stream().map(key -> "b." + HiveJdbcClient.quoteColumn(key) + " <=> c."
                + HiveJdbcClient.quoteColumn(key)).collect(Collectors.joining(" AND "));
        String changed = compareColumns.isEmpty() ? "FALSE" : compareColumns.stream()
                .map(column -> "NOT(b." + HiveJdbcClient.quoteColumn(column)
                        + " <=> c." + HiveJdbcClient.quoteColumn(column) + ")")
                .collect(Collectors.joining(" OR "));
        String firstKey = HiveJdbcClient.quoteColumn(keys.get(0));
        String joinType = rule.isOnlyCompareSamePrimaryKey() ? "JOIN" : "FULL OUTER JOIN";
        String create = "CREATE TABLE " + HiveJdbcClient.quote(diffTable) + " AS SELECT " + select + " FROM (SELECT * FROM "
                + HiveJdbcClient.quote(baseline) + baselineFilter + ") b " + joinType + " (SELECT * FROM "
                + HiveJdbcClient.quote(candidate) + candidateFilter + ") c ON " + join + " WHERE " + changed
                + (rule.isOnlyCompareSamePrimaryKey() ? "" : " OR b." + firstKey + " IS NULL OR c." + firstKey + " IS NULL");
        hive.execute(create, listener);
        String baselineKey = HiveJdbcClient.quoteColumn("baseline_" + keys.get(0));
        String candidateKey = HiveJdbcClient.quoteColumn("candidate_" + keys.get(0));
        Map<String, Object> counts = firstRow(hive.query("SELECT SUM(CASE WHEN " + candidateKey
                + " IS NULL THEN 1 ELSE 0 END) missing_candidate,SUM(CASE WHEN " + baselineKey
                + " IS NULL THEN 1 ELSE 0 END) missing_baseline,SUM(CASE WHEN " + baselineKey
                + " IS NOT NULL AND " + candidateKey + " IS NOT NULL THEN 1 ELSE 0 END) changed FROM "
                + HiveJdbcClient.quote(diffTable), listener));
        List<Map<String, Object>> samples = hive.query("SELECT * FROM " + HiveJdbcClient.quote(diffTable)
                + " LIMIT " + SAMPLE_LIMIT, listener);
        Map<String, Object> detail = new LinkedHashMap<>(); detail.put("counts", counts); detail.put("samples", samples);
        Map<String, Object> result = new LinkedHashMap<>(); result.put("difference", detail); result.put("diffTable", diffTable);
        return result;
    }

    private String partitionFilter(List<String> partitions) {
        if (partitions == null || partitions.isEmpty()) return "";
        return " WHERE " + partitions.stream().map(this::partitionCondition)
                .map(value -> "(" + value + ")").collect(Collectors.joining(" OR "));
    }

    private String partitionCondition(String partition) {
        return java.util.Arrays.stream(partition.split("/")).map(item -> {
            int equal = item.indexOf('=');
            if (equal <= 0) throw new IllegalArgumentException("非法Hive分区：" + partition);
            String key = item.substring(0, equal); String value = item.substring(equal + 1);
            return HiveJdbcClient.quoteColumn(key) + "='" + value.replace("'", "''") + "'";
        }).collect(Collectors.joining(" AND "));
    }

    private String appendCondition(String filter, String condition) {
        return filter == null || filter.isEmpty() ? " WHERE " + condition : filter + " AND (" + condition + ")";
    }

    private List<String> selectedColumns(Collection<String> requested, Collection<String> available) {
        Set<String> allow = available.stream().map(value -> value.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        return requested.stream().filter(value -> allow.contains(value.toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());
    }

    private List<String> invalidColumns(Collection<String> requested, Collection<String> available) {
        Set<String> allow = available.stream().map(value -> value.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        return requested.stream().filter(value -> !allow.contains(value.toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());
    }

    private Map<String, TableColumn> mapColumns(List<TableColumn> columns) {
        return columns.stream().collect(Collectors.toMap(column -> column.getName().toLowerCase(Locale.ROOT),
                column -> column, (left, right) -> left, LinkedHashMap::new));
    }

    private boolean sameSchema(List<TableColumn> left, List<TableColumn> right) {
        return left.size() == right.size() && metadataDiff(left, right).isEmpty();
    }

    private static Map<String, Object> pair(Object baseline, Object candidate) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("baseline", baseline); result.put("candidate", candidate); return result;
    }
    private static Map<String, Object> firstRow(List<Map<String, Object>> rows) { return rows.isEmpty() ? Collections.emptyMap() : rows.get(0); }
    private static Object first(List<Map<String, Object>> rows, String key) { return rows.isEmpty() ? null : rows.get(0).get(key); }
    private static long number(Object value) { return value == null ? 0 : Long.parseLong(value.toString()); }
    private static String string(Object value) { return value == null ? "0" : value.toString(); }

    public static class DataMismatchException extends Exception {
        public DataMismatchException(String message) { super(message); }
    }
}
