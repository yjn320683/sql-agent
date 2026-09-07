package com.yjn.sqlagent.datacompare.service;

import com.yjn.sqlagent.datacompare.model.TableColumn;
import com.yjn.sqlagent.parsesql.TableColumnMetadata;
import com.yjn.sqlagent.parsesql.TableIdentifier;
import com.yjn.sqlagent.parsesql.TableMetadataProvider;
import com.yjn.sqlagent.parsesql.TableSchema;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** 通过现有 HiveServer2 只读接口为血缘解析提供有序字段。 */
@Component
public class HiveTableMetadataProvider implements TableMetadataProvider {
    private final HiveJdbcClient hive;

    public HiveTableMetadataProvider(HiveJdbcClient hive) {
        this.hive = hive;
    }

    @Override
    public Optional<TableSchema> getTable(TableIdentifier table) {
        try {
            List<TableColumnMetadata> columns = new ArrayList<>();
            for (TableColumn column : hive.columns(hiveName(table), ignored -> { })) {
                columns.add(new TableColumnMetadata(column.getName(), column.getType(), column.isPartitionKey()));
            }
            return columns.isEmpty() ? Optional.empty() : Optional.of(new TableSchema(table, columns));
        } catch (SQLException | IllegalArgumentException error) {
            return Optional.empty();
        }
    }

    private String hiveName(TableIdentifier table) {
        return table.getDatabase().isEmpty() ? table.getTable() : table.getDatabase() + "." + table.getTable();
    }
}
