package com.yjn.sqlagent.parsesql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Join 条件及其涉及的物理字段。 */
public final class JoinRelation {
    private final String joinType;
    private final String condition;
    private final List<SourceColumn> leftColumns;
    private final List<SourceColumn> rightColumns;
    private final List<SourceColumn> columns;

    public JoinRelation(String joinType, String condition, List<SourceColumn> columns) {
        this(joinType, condition, columns, Collections.emptyList());
    }

    public JoinRelation(String joinType, String condition, List<SourceColumn> leftColumns,
                        List<SourceColumn> rightColumns) {
        this.joinType = joinType; this.condition = condition;
        this.leftColumns = immutable(leftColumns);
        this.rightColumns = immutable(rightColumns);
        List<SourceColumn> combined = new ArrayList<>(leftColumns);
        combined.addAll(rightColumns);
        this.columns = immutable(new ArrayList<>(new java.util.LinkedHashSet<>(combined)));
    }
    private static List<SourceColumn> immutable(List<SourceColumn> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
    public String getJoinType() { return joinType; }
    public String getCondition() { return condition; }
    public List<SourceColumn> getLeftColumns() { return leftColumns; }
    public List<SourceColumn> getRightColumns() { return rightColumns; }
    public List<SourceColumn> getColumns() { return columns; }
}
