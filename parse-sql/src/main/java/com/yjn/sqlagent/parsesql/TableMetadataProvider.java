package com.yjn.sqlagent.parsesql;

import java.util.Optional;

/** 解析器的唯一外部依赖；实现必须只读。 */
@FunctionalInterface
public interface TableMetadataProvider {
    TableMetadataProvider NONE = table -> Optional.empty();

    Optional<TableSchema> getTable(TableIdentifier table);
}
