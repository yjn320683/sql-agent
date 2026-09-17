package com.yjn.sqlagent.realtime.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class RealtimeTableSchemaVersionRepositoryTest {
    private JdbcTemplate jdbc;
    private RealtimeTableSchemaVersionRepository repository;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:schema_history;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS rt_realtime_table_schema_version");
        jdbc.execute("CREATE TABLE rt_realtime_table_schema_version(id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "realtime_table_id BIGINT NOT NULL,version_no INT NOT NULL,schema_fingerprint VARCHAR(64) NOT NULL,"
                + "change_source VARCHAR(32) NOT NULL,compatibility VARCHAR(16) NOT NULL,schema_json CLOB NOT NULL,"
                + "diff_json CLOB NOT NULL,source_event_id BIGINT,operator VARCHAR(64) NOT NULL,"
                + "first_seen_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,last_seen_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                + "UNIQUE(realtime_table_id,version_no),UNIQUE(realtime_table_id,schema_fingerprint))");
        repository = new RealtimeTableSchemaVersionRepository(jdbc, new ObjectMapper());
    }

    @Test
    void identicalSchemaOnlyRefreshesTheExistingVersion() {
        Map<String, Object> schema = schema(true, "BIGINT");
        repository.record(7L, schema, "CREATE", "tester", null);
        repository.record(7L, schema, "MANUAL_REFRESH", "tester", null);

        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rt_realtime_table_schema_version WHERE realtime_table_id=7", Long.class));
    }

    @Test
    void narrowingNullabilityCreatesAnIncompatibleDiff() {
        repository.record(8L, schema(true, "BIGINT"), "CREATE", "tester", null);
        Map<String, Object> version = repository.record(8L, schema(false, "BIGINT"), "MANUAL_REFRESH", "tester", null);

        assertEquals("INCOMPATIBLE", version.get("compatibility"));
        assertEquals(2, jdbc.queryForObject("SELECT MAX(version_no) FROM rt_realtime_table_schema_version WHERE realtime_table_id=8", Integer.class));
    }

    private Map<String, Object> schema(boolean nullable, String type) {
        return Map.of("comment", "订单表", "options", Map.of("bucket", "2"), "columns", List.of(
                Map.of("name", "id", "dataType", type, "nullable", nullable,
                        "primaryKey", true, "partitionKey", false, "comment", "主键", "sortOrder", 0)));
    }
}
