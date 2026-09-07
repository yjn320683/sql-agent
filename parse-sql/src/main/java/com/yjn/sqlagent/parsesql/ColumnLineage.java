package com.yjn.sqlagent.parsesql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/** 一个输出列到物理来源字段的关系。 */
public final class ColumnLineage {
    private final TableIdentifier targetTable;
    private final String targetColumn;
    private final int ordinal;
    private final String expression;
    private final List<SourceColumn> sources;

    public ColumnLineage(TableIdentifier targetTable, String targetColumn, int ordinal,
                         String expression, List<SourceColumn> sources) {
        this.targetTable = targetTable; this.targetColumn = targetColumn; this.ordinal = ordinal;
        this.expression = expression;
        this.sources = Collections.unmodifiableList(new ArrayList<>(new LinkedHashSet<>(sources)));
    }
    public TableIdentifier getTargetTable() { return targetTable; }
    public String getTargetColumn() { return targetColumn; }
    public int getOrdinal() { return ordinal; }
    public String getExpression() { return expression; }
    public List<SourceColumn> getSources() { return sources; }
}
