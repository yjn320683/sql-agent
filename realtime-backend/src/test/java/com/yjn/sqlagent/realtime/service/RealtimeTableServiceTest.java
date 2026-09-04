package com.yjn.sqlagent.realtime.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.yjn.sqlagent.realtime.repository.RealtimeTableRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RealtimeTableServiceTest {
    private final RealtimeTableService service = new RealtimeTableService(
            mock(RealtimeTableRepository.class), mock(RealtimePaimonCatalogService.class));

    @Test
    void createsCompletePaimonDdlFromManagedTableMetadata() {
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("catalogName", "paimon");
        table.put("databaseName", "ods_real");
        table.put("tableName", "orders");
        table.put("tableComment", "订单'明细");
        table.put("columns", List.of(
                Map.of("name", "id", "dataType", "BIGINT", "nullable", false,
                        "primaryKey", true, "partitionKey", false, "comment", "订单ID"),
                Map.of("name", "dt", "dataType", "STRING", "nullable", false,
                        "primaryKey", true, "partitionKey", true),
                Map.of("name", "payload", "dataType", "STRING", "nullable", true,
                        "primaryKey", false, "partitionKey", false)));
        table.put("options", Map.of("bucket", "2", "changelog-producer", "input"));

        assertEquals("CREATE TABLE `paimon`.`ods_real`.`orders` (\n"
                + "  `id` BIGINT NOT NULL COMMENT '订单ID',\n"
                + "  `dt` STRING NOT NULL,\n"
                + "  `payload` STRING,\n"
                + "  PRIMARY KEY (`id`, `dt`) NOT ENFORCED\n"
                + ")\nCOMMENT '订单''明细'\n"
                + "PARTITIONED BY (`dt`)\n"
                + "WITH (\n  'bucket' = '2',\n  'changelog-producer' = 'input'\n);",
                service.createTableDdl(table));
    }
}
