package com.yjn.sqlagent.service;

import com.yjn.sqlagent.parsesql.LineageDiagnostic;
import com.yjn.sqlagent.parsesql.SourceColumn;
import com.yjn.sqlagent.parsesql.StatementLineage;
import com.yjn.sqlagent.parsesql.TableAccess;
import com.yjn.sqlagent.parsesql.TableAccessRole;
import com.yjn.sqlagent.parsesql.TableIdentifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 与页面展示解耦的 Java parse-sql 事实结果。 */
public final class TaskLineageFacts {
    private final List<Map<String, Object>> inputs;
    private final List<Map<String, Object>> outputs;
    private final List<Map<String, Object>> diagnostics;
    private final List<Map<String, Object>> statements;
    private final int statementCount;
    private final boolean complete;

    private TaskLineageFacts(List<Map<String, Object>> inputs,
                             List<Map<String, Object>> outputs,
                             List<Map<String, Object>> diagnostics,
                             List<Map<String, Object>> statements,
                             int statementCount,
                             boolean complete) {
        this.inputs = immutable(inputs);
        this.outputs = immutable(outputs);
        this.diagnostics = immutable(diagnostics);
        this.statements = immutable(statements);
        this.statementCount = statementCount;
        this.complete = complete;
    }

    public static TaskLineageFacts from(List<StatementLineage> statements,
                                        List<LineageDiagnostic> scriptDiagnostics) {
        Map<String, Map<String, Object>> inputs = new LinkedHashMap<>();
        Map<String, Map<String, Object>> outputs = new LinkedHashMap<>();
        List<Map<String, Object>> diagnostics = new ArrayList<>();
        List<Map<String, Object>> statementFacts = new ArrayList<>();
        boolean complete = scriptDiagnostics == null || scriptDiagnostics.isEmpty();
        if (scriptDiagnostics != null) {
            for (LineageDiagnostic diagnostic : scriptDiagnostics) diagnostics.add(diagnostic(diagnostic, null));
        }
        int statementIndex = 0;
        for (StatementLineage statement : statements) {
            statementIndex++;
            statementFacts.add(statement(statement, statementIndex));
            for (TableAccess access : statement.getTableAccesses()) {
                if (access.isDynamic()) complete = false;
                if (access.getRole() == TableAccessRole.READ || access.getRole() == TableAccessRole.READ_WRITE) {
                    add(inputs, access.getTable(), access.isDynamic());
                }
                if (access.getRole() == TableAccessRole.WRITE || access.getRole() == TableAccessRole.READ_WRITE) {
                    add(outputs, access.getTable(), access.isDynamic());
                }
            }
            for (LineageDiagnostic diagnostic : statement.getDiagnostics()) {
                diagnostics.add(diagnostic(diagnostic, statementIndex));
                if (!"INFO".equals(diagnostic.getSeverity().name())) complete = false;
            }
        }
        return new TaskLineageFacts(new ArrayList<>(inputs.values()), new ArrayList<>(outputs.values()),
                diagnostics, statementFacts, statements.size(), complete);
    }

    @SuppressWarnings("unchecked")
    public static TaskLineageFacts fromJson(Map<String, Object> payload) {
        return new TaskLineageFacts(copyMaps(payload.get("inputs")), copyMaps(payload.get("outputs")),
                copyMaps(payload.get("diagnostics")), copyMaps(payload.get("statements")),
                number(payload.get("statementCount")),
                Boolean.TRUE.equals(payload.get("complete")));
    }

    public static TaskLineageFacts failure(String code, String message) {
        Map<String, Object> diagnostic = new LinkedHashMap<>();
        diagnostic.put("code", code);
        diagnostic.put("severity", "ERROR");
        diagnostic.put("message", message);
        diagnostic.put("line", 1);
        diagnostic.put("column", 0);
        diagnostic.put("startOffset", 0);
        diagnostic.put("endOffset", 0);
        return new TaskLineageFacts(Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList(diagnostic), Collections.emptyList(), 0, false);
    }

    public Map<String, Object> toJson() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("statementCount", statementCount);
        result.put("inputs", inputs);
        result.put("outputs", outputs);
        result.put("diagnostics", diagnostics);
        result.put("statements", statements);
        result.put("complete", complete);
        return result;
    }

    public List<Map<String, Object>> getInputs() { return inputs; }
    public List<Map<String, Object>> getOutputs() { return outputs; }
    public List<Map<String, Object>> getDiagnostics() { return diagnostics; }
    public List<Map<String, Object>> getStatements() { return statements; }
    public int getStatementCount() { return statementCount; }
    public boolean isComplete() { return complete; }

    private static void add(Map<String, Map<String, Object>> values, TableIdentifier table, boolean dynamic) {
        String key = table.normalizedName();
        if (values.containsKey(key)) return;
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("catalog", emptyToNull(table.getCatalog()));
        value.put("db", emptyToNull(table.getDatabase()));
        value.put("table", table.getTable());
        value.put("qualifiedName", table.qualifiedName());
        value.put("dynamic", dynamic);
        values.put(key, value);
    }

    private static Map<String, Object> diagnostic(LineageDiagnostic value, Integer statementIndex) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", value.getCode());
        result.put("severity", value.getSeverity().name());
        result.put("message", value.getMessage());
        result.put("line", value.getLine());
        result.put("column", value.getColumn());
        result.put("startOffset", value.getStartOffset());
        result.put("endOffset", value.getEndOffset());
        if (statementIndex != null) result.put("statementIndex", statementIndex);
        return result;
    }

    private static Map<String, Object> statement(StatementLineage value, int statementIndex) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("statementIndex", statementIndex);
        result.put("statementType", value.getStatementType().name());
        List<Map<String, Object>> accesses = new ArrayList<>();
        for (TableAccess access : value.getTableAccesses()) {
            Map<String, Object> item = table(access.getTable());
            item.put("role", access.getRole().name());
            item.put("dynamic", access.isDynamic());
            item.put("startOffset", access.getStartOffset());
            item.put("endOffset", access.getEndOffset());
            accesses.add(item);
        }
        result.put("tableAccesses", accesses);
        List<Map<String, Object>> columnLineages = new ArrayList<>();
        value.getColumnLineages().forEach(lineage -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("targetTable", lineage.getTargetTable() == null ? null : table(lineage.getTargetTable()));
            item.put("targetColumn", lineage.getTargetColumn());
            item.put("ordinal", lineage.getOrdinal());
            item.put("expression", lineage.getExpression());
            item.put("sources", columns(lineage.getSources()));
            columnLineages.add(item);
        });
        result.put("columnLineages", columnLineages);
        List<Map<String, Object>> usages = new ArrayList<>();
        value.getColumnUsages().forEach(usage -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", usage.getType().name());
            item.put("expression", usage.getExpression());
            item.put("columns", columns(usage.getColumns()));
            usages.add(item);
        });
        result.put("columnUsages", usages);
        List<Map<String, Object>> joins = new ArrayList<>();
        value.getJoins().forEach(join -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("joinType", join.getJoinType());
            item.put("condition", join.getCondition());
            item.put("leftColumns", columns(join.getLeftColumns()));
            item.put("rightColumns", columns(join.getRightColumns()));
            joins.add(item);
        });
        result.put("joins", joins);
        List<Map<String, Object>> statementDiagnostics = new ArrayList<>();
        value.getDiagnostics().forEach(item -> statementDiagnostics.add(diagnostic(item, statementIndex)));
        result.put("diagnostics", statementDiagnostics);
        return result;
    }

    private static List<Map<String, Object>> columns(List<SourceColumn> values) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (SourceColumn value : values) {
            Map<String, Object> item = table(value.getTable());
            item.put("column", value.getColumn());
            item.put("direct", value.isDirect());
            result.add(item);
        }
        return result;
    }

    private static Map<String, Object> table(TableIdentifier value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("catalog", emptyToNull(value.getCatalog()));
        result.put("database", emptyToNull(value.getDatabase()));
        result.put("table", value.getTable());
        result.put("qualifiedName", value.qualifiedName());
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> copyMaps(Object value) {
        if (!(value instanceof List)) return Collections.emptyList();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : (List<?>) value) {
            if (item instanceof Map) result.add(new LinkedHashMap<>((Map<String, Object>) item));
        }
        return result;
    }

    private static int number(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private static String emptyToNull(String value) { return value == null || value.isEmpty() ? null : value; }

    private static <T> List<T> immutable(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
