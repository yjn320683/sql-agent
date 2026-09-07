package com.yjn.sqlagent.service;

import com.yjn.sqlagent.parsesql.TableColumnMetadata;
import com.yjn.sqlagent.parsesql.TableIdentifier;
import com.yjn.sqlagent.parsesql.TableMetadataProvider;
import com.yjn.sqlagent.parsesql.TableSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** 复用 Agent 的 Hive 元数据只读接口，支持任务 SQL 的星号展开与歧义判断。 */
@Component
public class BackendHiveTableMetadataProvider implements TableMetadataProvider {
    private final AgentProxyService agent;

    public BackendHiveTableMetadataProvider(AgentProxyService agent) {
        this.agent = agent;
    }

    @Override
    public Optional<TableSchema> getTable(TableIdentifier table) {
        try {
            Map<String, Object> response = agent.getHiveColumns(table.getDatabase(), table.getTable());
            Object raw = response == null ? null : response.get("columns");
            if (!(raw instanceof List)) return Optional.empty();
            List<TableColumnMetadata> columns = new ArrayList<>();
            for (Object item : (List<?>) raw) {
                if (!(item instanceof Map)) continue;
                Map<?, ?> value = (Map<?, ?>) item;
                String name = text(value.get("name"));
                if (name.isEmpty()) continue;
                String dataType = text(value.containsKey("dataType") ? value.get("dataType") : value.get("type"));
                columns.add(new TableColumnMetadata(name, dataType, Boolean.TRUE.equals(value.get("partitionKey"))));
            }
            return columns.isEmpty() ? Optional.empty() : Optional.of(new TableSchema(table, columns));
        } catch (RuntimeException error) {
            return Optional.empty();
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
