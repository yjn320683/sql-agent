package com.yjn.sqlagent.realtime.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yjn.sqlagent.parsesql.TableIdentifier;
import com.yjn.sqlagent.parsesql.TableSchema;
import com.yjn.sqlagent.realtime.repository.RealtimeTableRepository;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RealtimeTableMetadataProviderTest {
    @Test
    void onlyProvidesManagedPaimonTablesInDeclaredOrder() {
        RealtimeTableRepository repository = mock(RealtimeTableRepository.class);
        when(repository.findId("ods", "orders")).thenReturn(7L);
        when(repository.columns(7L)).thenReturn(Arrays.asList(
                column("id", "BIGINT", false), column("dt", "STRING", true)));
        RealtimeTableMetadataProvider provider = new RealtimeTableMetadataProvider(repository);

        TableSchema schema = provider.getTable(new TableIdentifier("paimon", "ods", "orders")).orElseThrow();

        assertEquals("id", schema.getColumns().get(0).getName());
        assertEquals(true, schema.getColumns().get(1).isPartitionKey());
        assertFalse(provider.getTable(new TableIdentifier("hive", "ods", "orders")).isPresent());
    }

    private Map<String, Object> column(String name, String type, boolean partition) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", name);
        value.put("dataType", type);
        value.put("partitionKey", partition);
        return value;
    }
}
