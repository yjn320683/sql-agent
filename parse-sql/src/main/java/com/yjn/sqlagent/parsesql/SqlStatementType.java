package com.yjn.sqlagent.parsesql;

/** SQL 顶层语句类型。 */
public enum SqlStatementType {
    SELECT,
    WITH,
    INSERT,
    REPLACE,
    CREATE_TABLE_AS_SELECT,
    CREATE_VIEW,
    CREATE_TEMPORARY_VIEW,
    UPDATE,
    DELETE,
    SET,
    USE,
    OTHER
}
