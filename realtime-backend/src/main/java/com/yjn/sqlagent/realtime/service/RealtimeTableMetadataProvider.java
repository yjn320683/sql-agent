package com.yjn.sqlagent.realtime.service;

import com.yjn.sqlagent.parsesql.TableColumnMetadata;
import com.yjn.sqlagent.parsesql.TableIdentifier;
import com.yjn.sqlagent.parsesql.TableMetadataProvider;
import com.yjn.sqlagent.parsesql.TableSchema;
import com.yjn.sqlagent.realtime.repository.RealtimeTableRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** 从实时表管理读取 Paimon Schema，不访问或修改物理表。 */
@Component
public class RealtimeTableMetadataProvider implements TableMetadataProvider {
    private final RealtimeTableRepository tables;

    public RealtimeTableMetadataProvider(RealtimeTableRepository tables) {
        this.tables = tables;
    }

    @Override
    public Optional<TableSchema> getTable(TableIdentifier table) {
        if (!table.getCatalog().isEmpty() && !"paimon".equalsIgnoreCase(table.getCatalog())) return Optional.empty();
        Long id = tables.findId(table.getDatabase(), table.getTable());
        if (id == null) return Optional.empty();
        List<TableColumnMetadata> columns = new ArrayList<>();
        for (Map<String, Object> column : tables.columns(id)) {
            String name = text(column.get("name"));
            if (!name.isEmpty()) {
                columns.add(new TableColumnMetadata(name, text(column.get("dataType")), bool(column.get("partitionKey"))));
            }
        }
        return columns.isEmpty() ? Optional.empty() : Optional.of(new TableSchema(table, columns));
    }

    private boolean bool(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(text(value)) || "1".equals(text(value));
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
}
