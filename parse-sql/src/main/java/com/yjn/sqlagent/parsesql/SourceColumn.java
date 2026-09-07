package com.yjn.sqlagent.parsesql;

import java.util.Objects;

/** 已回溯到物理表的来源字段。 */
public final class SourceColumn {
    private final TableIdentifier table;
    private final String column;
    private final boolean direct;

    public SourceColumn(TableIdentifier table, String column, boolean direct) {
        this.table = table; this.column = column; this.direct = direct;
    }
    public TableIdentifier getTable() { return table; }
    public String getColumn() { return column; }
    public boolean isDirect() { return direct; }

    @Override public boolean equals(Object value) {
        if (!(value instanceof SourceColumn)) return false;
        SourceColumn other = (SourceColumn) value;
        return Objects.equals(table, other.table) && column.equalsIgnoreCase(other.column);
    }
    @Override public int hashCode() { return Objects.hash(table, column.toLowerCase()); }
}
