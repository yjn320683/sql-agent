package com.yjn.sqlagent.parsesql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 单条 SQL 的完整血缘结果。 */
public final class StatementLineage {
    private final String sql;
    private final SqlStatementType statementType;
    private final List<TableAccess> tableAccesses;
    private final List<ColumnLineage> columnLineages;
    private final List<ColumnUsage> columnUsages;
    private final List<JoinRelation> joins;
    private final List<LineageDiagnostic> diagnostics;

    StatementLineage(String sql, SqlStatementType statementType, List<TableAccess> tableAccesses,
                     List<ColumnLineage> columnLineages, List<ColumnUsage> columnUsages,
                     List<JoinRelation> joins, List<LineageDiagnostic> diagnostics) {
        this.sql = sql; this.statementType = statementType;
        this.tableAccesses = immutable(tableAccesses); this.columnLineages = immutable(columnLineages);
        this.columnUsages = immutable(columnUsages); this.joins = immutable(joins); this.diagnostics = immutable(diagnostics);
    }

    private static <T> List<T> immutable(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    public String getSql() { return sql; }
    public SqlStatementType getStatementType() { return statementType; }
    public List<TableAccess> getTableAccesses() { return tableAccesses; }
    public List<ColumnLineage> getColumnLineages() { return columnLineages; }
    public List<ColumnUsage> getColumnUsages() { return columnUsages; }
    public List<JoinRelation> getJoins() { return joins; }
    public List<LineageDiagnostic> getDiagnostics() { return diagnostics; }

    public List<String> getInputTables() { return tableNames(true); }
    public List<String> getOutputTables() { return tableNames(false); }
    public boolean isPartial() {
        return diagnostics.stream().anyMatch(item -> item.getSeverity() != DiagnosticSeverity.INFO);
    }

    private List<String> tableNames(boolean input) {
        Map<String, String> result = new LinkedHashMap<>();
        for (TableAccess access : tableAccesses) {
            boolean match = input
                    ? access.getRole() == TableAccessRole.READ || access.getRole() == TableAccessRole.READ_WRITE
                    : access.getRole() == TableAccessRole.WRITE || access.getRole() == TableAccessRole.READ_WRITE;
            if (match && !access.isDynamic()) {
                String name = access.getTable().qualifiedName();
                result.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(result.values()));
    }

    public List<String> getUnresolvedTableReferences() {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (TableAccess access : tableAccesses) if (access.isDynamic()) result.add(access.getTable().qualifiedName());
        return Collections.unmodifiableList(new ArrayList<>(result));
    }
}
