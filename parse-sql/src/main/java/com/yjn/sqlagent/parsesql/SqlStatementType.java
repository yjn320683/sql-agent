package com.yjn.sqlagent.parsesql;

/** SQL 顶层语句类型。 */
public enum SqlStatementType {
    SELECT,
    WITH,
    INSERT,
    CREATE_TABLE_AS_SELECT,
    SET,
    OTHER
}
