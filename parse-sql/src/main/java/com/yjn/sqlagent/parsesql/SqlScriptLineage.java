package com.yjn.sqlagent.parsesql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 多语句脚本结果。 */
public final class SqlScriptLineage {
    private final List<StatementLineage> statements;
    private final List<LineageDiagnostic> diagnostics;

    SqlScriptLineage(List<StatementLineage> statements, List<LineageDiagnostic> diagnostics) {
        this.statements = Collections.unmodifiableList(new ArrayList<>(statements));
        this.diagnostics = Collections.unmodifiableList(new ArrayList<>(diagnostics));
    }
    public List<StatementLineage> getStatements() { return statements; }
    public List<LineageDiagnostic> getDiagnostics() { return diagnostics; }
    public List<String> getInputTables() { return tables(true); }
    public List<String> getOutputTables() { return tables(false); }
    public boolean isPartial() { return !diagnostics.isEmpty() || statements.stream().anyMatch(StatementLineage::isPartial); }

    private List<String> tables(boolean input) {
        Map<String, String> result = new LinkedHashMap<>();
        for (StatementLineage statement : statements) {
            for (String name : input ? statement.getInputTables() : statement.getOutputTables()) {
                result.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(result.values()));
    }
}
