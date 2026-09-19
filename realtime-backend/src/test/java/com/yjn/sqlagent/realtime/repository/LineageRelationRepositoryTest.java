package com.yjn.sqlagent.realtime.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class LineageRelationRepositoryTest {
    private JdbcTemplate jdbc;
    private LineageRelationRepository repository;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:lineage_index;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS task_lineage_relation");
        jdbc.execute("CREATE TABLE task_lineage_relation(id BIGINT AUTO_INCREMENT PRIMARY KEY,snapshot_id BIGINT NOT NULL,"
                + "relation_signature VARCHAR(64) NOT NULL,task_scope VARCHAR(16),task_id BIGINT,version_id BIGINT,"
                + "version_no INT,statement_index INT,relation_kind VARCHAR(32),source_catalog VARCHAR(64),"
                + "source_database VARCHAR(128),source_table VARCHAR(128),source_column VARCHAR(128),"
                + "target_catalog VARCHAR(64),target_database VARCHAR(128),target_table VARCHAR(128),"
                + "target_column VARCHAR(128),usage_type VARCHAR(32),direct_flag BOOLEAN,UNIQUE(snapshot_id,relation_signature))");
        repository = new LineageRelationRepository(jdbc, new ObjectMapper());
    }

    @Test
    void projectsTablesColumnsAndUsageIdempotently() {
        Map<String, Object> source = Map.of("catalog", "hive", "db", "ods", "table", "orders", "column", "id");
        Map<String, Object> target = Map.of("catalog", "paimon", "db", "dwd", "table", "orders", "column", "order_id");
        Map<String, Object> facts = Map.of(
                "inputs", List.of(source), "outputs", List.of(target),
                "statements", List.of(Map.of("statementIndex", 0,
                        "columnLineages", List.of(Map.of("targetTable", target, "targetColumn", "order_id",
                                "sources", List.of(Map.of("catalog", "hive", "db", "ods", "table", "orders", "column", "id", "direct", true)))),
                        "columnUsages", List.of(Map.of("type", "JOIN", "columns", List.of(source))))));

        repository.index(10L, "OFFLINE", 20L, 30L, 2, facts);
        repository.index(10L, "OFFLINE", 20L, 30L, 2, facts);

        assertEquals(5L, jdbc.queryForObject("SELECT COUNT(*) FROM task_lineage_relation", Long.class));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM task_lineage_relation WHERE relation_kind='COLUMN_DERIVATION'", Long.class));
        assertEquals("JOIN", jdbc.queryForObject("SELECT usage_type FROM task_lineage_relation WHERE relation_kind='COLUMN_USAGE'", String.class));
    }
}
