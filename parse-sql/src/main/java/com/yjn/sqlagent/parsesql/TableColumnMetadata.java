package com.yjn.sqlagent.parsesql;

/** 元数据库返回的字段，列表顺序即物理字段顺序。 */
public final class TableColumnMetadata {
    private final String name;
    private final String dataType;
    private final boolean partitionKey;

    public TableColumnMetadata(String name, String dataType, boolean partitionKey) {
        this.name = name;
        this.dataType = dataType == null ? "" : dataType;
        this.partitionKey = partitionKey;
    }

    public String getName() { return name; }
    public String getDataType() { return dataType; }
    public boolean isPartitionKey() { return partitionKey; }
}
