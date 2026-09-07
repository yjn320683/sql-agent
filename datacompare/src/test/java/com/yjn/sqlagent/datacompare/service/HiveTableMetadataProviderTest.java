package com.yjn.sqlagent.datacompare.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yjn.sqlagent.datacompare.model.TableColumn;
import com.yjn.sqlagent.parsesql.TableIdentifier;
import com.yjn.sqlagent.parsesql.TableSchema;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class HiveTableMetadataProviderTest {
    @Test
    void readsOrderedColumnsThroughHiveJdbcClient() throws Exception {
        HiveJdbcClient hive = mock(HiveJdbcClient.class);
        when(hive.columns(org.mockito.ArgumentMatchers.eq("ods.orders"), any())).thenReturn(Arrays.asList(
                new TableColumn("id", "bigint", "", false),
                new TableColumn("dt", "string", "", true)));

        TableSchema schema = new HiveTableMetadataProvider(hive)
                .getTable(TableIdentifier.parse("ods.orders", "", "default")).orElseThrow();

        assertEquals("id", schema.getColumns().get(0).getName());
        assertEquals(true, schema.getColumns().get(1).isPartitionKey());
    }
}
