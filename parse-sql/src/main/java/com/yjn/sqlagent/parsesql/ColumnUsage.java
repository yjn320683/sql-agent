package com.yjn.sqlagent.parsesql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/** 一个子句使用到的物理字段集合。 */
public final class ColumnUsage {
    private final ColumnUsageType type;
    private final String expression;
    private final List<SourceColumn> columns;

    public ColumnUsage(ColumnUsageType type, String expression, List<SourceColumn> columns) {
        this.type = type; this.expression = expression;
        this.columns = Collections.unmodifiableList(new ArrayList<>(new LinkedHashSet<>(columns)));
    }
    public ColumnUsageType getType() { return type; }
    public String getExpression() { return expression; }
    public List<SourceColumn> getColumns() { return columns; }
}
