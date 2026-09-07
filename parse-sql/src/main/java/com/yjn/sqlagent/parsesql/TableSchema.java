package com.yjn.sqlagent.parsesql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 一张物理表的有序字段结构。 */
public final class TableSchema {
    private final TableIdentifier table;
    private final List<TableColumnMetadata> columns;

    public TableSchema(TableIdentifier table, List<TableColumnMetadata> columns) {
        this.table = table;
        this.columns = Collections.unmodifiableList(new ArrayList<>(columns));
    }

    public TableIdentifier getTable() { return table; }
    public List<TableColumnMetadata> getColumns() { return columns; }
}
