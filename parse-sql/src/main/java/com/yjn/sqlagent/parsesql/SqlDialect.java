package com.yjn.sqlagent.parsesql;

/** SQL 方言。语法前端共享，方言值用于限制顶层语句及标识符补全规则。 */
public enum SqlDialect {
    HIVE,
    TRINO,
    FLINK
}
