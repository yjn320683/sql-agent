package com.yjn.sqlagent.parsesql;

/** 物理表在语句中的访问角色。 */
public enum TableAccessRole {
    READ,
    WRITE,
    READ_WRITE,
    SCHEMA_SOURCE
}
