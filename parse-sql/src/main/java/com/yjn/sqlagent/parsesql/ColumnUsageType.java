package com.yjn.sqlagent.parsesql;

/** 字段在 SQL 子句中的用途。 */
public enum ColumnUsageType {
    SELECT,
    JOIN,
    FILTER,
    GROUP_BY,
    HAVING,
    ORDER_BY,
    WINDOW_PARTITION,
    WINDOW_ORDER,
    PARTITION_WRITE,
    UPDATE_SET
}
