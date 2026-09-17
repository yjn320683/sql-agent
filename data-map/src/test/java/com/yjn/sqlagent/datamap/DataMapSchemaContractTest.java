package com.yjn.sqlagent.datamap;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DataMapSchemaContractTest {
    @Test
    void moduleSchemaContainsReliableProjectionAndGenerationContracts() throws Exception {
        String schema = resource("db/data_map_schema.sql");

        assertThat(schema).contains(
                "CREATE TABLE IF NOT EXISTS data_map_lineage_run",
                "CREATE TABLE IF NOT EXISTS data_map_lineage_task_state",
                "CREATE TABLE IF NOT EXISTS data_map_graph_outbox",
                "CREATE TABLE IF NOT EXISTS data_map_projection_generation",
                "UNIQUE KEY uk_data_map_outbox_snapshot (snapshot_id,event_type,generation_no)",
                "KEY idx_data_map_outbox_claim (status,available_at,id)",
                "UNIQUE KEY uk_data_map_generation_no (generation_no)");
    }

    private String resource(String path) throws Exception {
        try (java.io.InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("missing resource " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
