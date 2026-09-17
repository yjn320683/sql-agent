package com.yjn.sqlagent.datamap.project;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjn.sqlagent.datamap.graph.GraphStoreClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DataMapGraphProjectorTest {
    @Test
    void projectsOneSnapshotWithAValidSingleCypherPipeline() throws Exception {
        RecordingGraphStore graph = new RecordingGraphStore();
        DataMapGraphProjector projector = new DataMapGraphProjector(graph, new ObjectMapper(), List.of());
        Map<String, Object> facts = Map.of(
                "complete", true,
                "diagnostics", List.of(),
                "inputs", List.of(Map.of("catalog", "hive", "db", "ods", "table", "orders")),
                "outputs", List.of(Map.of("catalog", "paimon", "db", "dwd", "table", "orders")),
                "statements", List.of());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("task_scope", "OFFLINE");
        row.put("task_id", 12L);
        row.put("version_id", 34L);
        row.put("version_no", 3);
        row.put("task_type", "offline");
        row.put("task_name", "订单加工");
        row.put("snapshot_id", 56L);
        row.put("parser_version", "parse-sql-v2");
        row.put("sql_checksum", "abc");
        row.put("lineage_json", new ObjectMapper().writeValueAsString(facts));

        projector.project(row, 7L);

        assertThat(graph.cyphers).hasSize(1);
        assertThat(graph.cyphers.get(0)).doesNotContain("WITH v WITH v");
        assertThat(graph.cyphers.get(0)).contains("MERGE (t)-[:HAS_VERSION]->(v) WITH v OPTIONAL MATCH");
        assertThat(graph.parameters.get(0))
                .containsEntry("taskKey", "offline:12")
                .containsEntry("versionKey", "offline:12:3:g7")
                .containsEntry("generation", 7L);
    }

    @Test
    void retiresOnlyRelationshipsFromOlderGenerationsUsingDirectedMatch() {
        RecordingGraphStore graph = new RecordingGraphStore();
        DataMapGraphProjector projector = new DataMapGraphProjector(graph, new ObjectMapper(), List.of());

        projector.retireOlderGenerations(8L);

        assertThat(graph.cyphers).hasSize(4);
        assertThat(graph.cyphers.get(0)).contains("-[r:DERIVES_TO|USED_BY|JOINED_WITH]->()")
                .doesNotContain("-[r:DERIVES_TO|USED_BY|JOINED_WITH]-()");
        assertThat(graph.parameters.get(0)).containsEntry("generation", 8L);
    }

    private static final class RecordingGraphStore implements GraphStoreClient {
        private final List<String> cyphers = new ArrayList<>();
        private final List<Map<String, Object>> parameters = new ArrayList<>();

        @Override public boolean isConfigured() { return true; }
        @Override public boolean ping() { return true; }
        @Override public List<Map<String, Object>> query(String cypher, Map<String, Object> values) {
            cyphers.add(cypher);
            parameters.add(new LinkedHashMap<>(values));
            return List.of();
        }
    }
}
