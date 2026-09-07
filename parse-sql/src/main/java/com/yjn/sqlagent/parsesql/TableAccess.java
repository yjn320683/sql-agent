package com.yjn.sqlagent.parsesql;

/** SQL 源码中的一次物理表访问。 */
public final class TableAccess {
    private final TableIdentifier table;
    private final TableAccessRole role;
    private final int startOffset;
    private final int endOffset;
    private final boolean dynamic;

    public TableAccess(TableIdentifier table, TableAccessRole role, int startOffset, int endOffset, boolean dynamic) {
        this.table = table; this.role = role; this.startOffset = startOffset; this.endOffset = endOffset; this.dynamic = dynamic;
    }
    public TableIdentifier getTable() { return table; }
    public TableAccessRole getRole() { return role; }
    public int getStartOffset() { return startOffset; }
    public int getEndOffset() { return endOffset; }
    public boolean isDynamic() { return dynamic; }
}
