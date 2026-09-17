package com.yjn.sqlagent.datamap.project;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 将不可变快照 JSON 规范化为 Neo4j 批量参数。 */
final class LineageProjectionMapper {
    LineageProjection map(Map<String, Object> facts) {
        LineageProjection result = new LineageProjection();
        result.complete = Boolean.TRUE.equals(facts.get("complete"));
        result.diagnostics = maps(facts.get("diagnostics"));
        for (Map<String, Object> value : maps(facts.get("inputs"))) result.inputs.add(asset(value));
        for (Map<String, Object> value : maps(facts.get("outputs"))) result.outputs.add(asset(value));
        for (Map<String, Object> statement : maps(facts.get("statements"))) {
            int statementIndex = number(statement.get("statementIndex"));
            for (Map<String, Object> lineage : maps(statement.get("columnLineages"))) {
                Map<String, Object> targetTable = map(lineage.get("targetTable"));
                String targetColumn = text(lineage.get("targetColumn"));
                if (targetTable.isEmpty() || targetColumn.isEmpty()) continue;
                Map<String, Object> target = column(targetTable, targetColumn);
                for (Map<String, Object> source : maps(lineage.get("sources"))) {
                    String sourceColumn = text(source.get("column"));
                    if (text(source.get("table")).isEmpty() || sourceColumn.isEmpty()) continue;
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("source", column(source, sourceColumn));
                    item.put("target", target);
                    item.put("statementIndex", statementIndex);
                    item.put("ordinal", number(lineage.get("ordinal")));
                    item.put("expression", limited(text(lineage.get("expression")), 2000));
                    item.put("direct", Boolean.TRUE.equals(source.get("direct")));
                    result.derivations.add(item);
                }
            }
            for (Map<String, Object> usage : maps(statement.get("columnUsages"))) {
                for (Map<String, Object> source : maps(usage.get("columns"))) {
                    String sourceColumn = text(source.get("column"));
                    if (text(source.get("table")).isEmpty() || sourceColumn.isEmpty()) continue;
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("column", column(source, sourceColumn));
                    item.put("statementIndex", statementIndex);
                    item.put("usageType", text(usage.get("type")));
                    item.put("expression", limited(text(usage.get("expression")), 2000));
                    result.usages.add(item);
                }
            }
            for (Map<String, Object> join : maps(statement.get("joins"))) {
                for (Map<String, Object> left : maps(join.get("leftColumns"))) {
                    for (Map<String, Object> right : maps(join.get("rightColumns"))) {
                        if (text(left.get("table")).isEmpty() || text(left.get("column")).isEmpty()
                                || text(right.get("table")).isEmpty() || text(right.get("column")).isEmpty()) continue;
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("left", column(left, text(left.get("column"))));
                        item.put("right", column(right, text(right.get("column"))));
                        item.put("statementIndex", statementIndex);
                        item.put("joinType", text(join.get("joinType")));
                        item.put("condition", limited(text(join.get("condition")), 2000));
                        result.joins.add(item);
                    }
                }
            }
        }
        return result;
    }

    private Map<String, Object> asset(Map<String, Object> source) {
        String catalog = text(source.get("catalog"));
        if (catalog.isEmpty()) catalog = "hive";
        String database = text(source.containsKey("database") ? source.get("database") : source.get("db"));
        String table = text(source.get("table"));
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("assetKey", key(catalog, database, table));
        value.put("catalog", catalog);
        value.put("database", database);
        value.put("table", table);
        value.put("qualifiedName", qualified(catalog, database, table));
        value.put("assetType", catalog.toUpperCase(Locale.ROOT));
        return value;
    }

    private Map<String, Object> column(Map<String, Object> source, String name) {
        Map<String, Object> asset = asset(source);
        Map<String, Object> value = new LinkedHashMap<>(asset);
        value.put("column", name);
        value.put("columnKey", asset.get("assetKey") + "|" + name.toLowerCase(Locale.ROOT));
        return value;
    }

    private String key(String catalog, String database, String table) {
        return (catalog + "|" + database + "|" + table).toLowerCase(Locale.ROOT);
    }
    private String qualified(String catalog, String database, String table) {
        List<String> values = new ArrayList<>();
        if (!catalog.isEmpty()) values.add(catalog);
        if (!database.isEmpty()) values.add(database);
        values.add(table);
        return String.join(".", values);
    }
    @SuppressWarnings("unchecked") private Map<String,Object> map(Object value) { return value instanceof Map ? new LinkedHashMap<>((Map<String,Object>) value) : new LinkedHashMap<>(); }
    @SuppressWarnings("unchecked") private List<Map<String,Object>> maps(Object value) { return value instanceof List ? (List<Map<String,Object>>) value : List.of(); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private int number(Object value) { return value instanceof Number ? ((Number) value).intValue() : 0; }
    private String limited(String value, int max) { return value.length() <= max ? value : value.substring(0, max); }
}
