package com.yjn.sqlagent.parsesql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 单条 SQL 的类型及表级读写关系。 */
public final class SqlParseResult {
    private final String sql;
    private final SqlStatementType statementType;
    private final List<TableReference> tableReferences;

    SqlParseResult(String sql, SqlStatementType statementType, List<TableReference> tableReferences) {
        this.sql = sql;
        this.statementType = statementType;
        this.tableReferences = Collections.unmodifiableList(new ArrayList<>(tableReferences));
    }

    public String getSql() {
        return sql;
    }

    public SqlStatementType getStatementType() {
        return statementType;
    }

    public List<TableReference> getTableReferences() {
        return tableReferences;
    }

    public List<String> getInputTables() {
        return tableNames(TableRole.INPUT);
    }

    public List<String> getOutputTables() {
        return tableNames(TableRole.OUTPUT);
    }

    public List<String> getUnresolvedTableReferences() {
        Set<String> result = new LinkedHashSet<>();
        for (TableReference reference : tableReferences) {
            if (reference.isDynamic()) result.add(reference.getName());
        }
        return Collections.unmodifiableList(new ArrayList<>(result));
    }

    public boolean hasUnresolvedTableReferences() {
        return !getUnresolvedTableReferences().isEmpty();
    }

    private List<String> tableNames(TableRole role) {
        Map<String, String> result = new LinkedHashMap<>();
        for (TableReference reference : tableReferences) {
            if (reference.getRole() == role && !reference.isDynamic()) {
                result.putIfAbsent(reference.getName().toLowerCase(Locale.ROOT), reference.getName());
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(result.values()));
    }
}
