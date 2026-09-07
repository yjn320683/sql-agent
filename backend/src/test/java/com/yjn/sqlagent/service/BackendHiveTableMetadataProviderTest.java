package com.yjn.sqlagent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yjn.sqlagent.parsesql.TableIdentifier;
import com.yjn.sqlagent.parsesql.TableSchema;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BackendHiveTableMetadataProviderTest {
    @Test
    void preservesHiveColumnAndPartitionOrder() {
        AgentProxyService agent = mock(AgentProxyService.class);
        Map<String, Object> id = column("id", "bigint", false);
        Map<String, Object> dt = column("dt", "string", true);
        when(agent.getHiveColumns("ods", "orders")).thenReturn(Map.of("columns", Arrays.asList(id, dt)));

        TableSchema schema = new BackendHiveTableMetadataProvider(agent)
                .getTable(TableIdentifier.parse("ods.orders", "", "default")).orElseThrow();

        assertEquals("id", schema.getColumns().get(0).getName());
        assertEquals("dt", schema.getColumns().get(1).getName());
        assertEquals(true, schema.getColumns().get(1).isPartitionKey());
    }

    private Map<String, Object> column(String name, String type, boolean partition) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", name);
        value.put("dataType", type);
        value.put("partitionKey", partition);
        return value;
    }
}
