package com.yjn.sqlagent.datamap.store;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class LineageOutboxServiceTest {
    @Test
    void missingBackfillSkipsSnapshotsAlreadyQueuedInTheActiveGeneration() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(3L);
        when(jdbc.update(anyString())).thenReturn(1);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        LineageOutboxService service = new LineageOutboxService(jdbc);

        service.enqueueMissing(1000);

        verify(jdbc).update(org.mockito.ArgumentMatchers.<String>argThat(sql ->
                sql.contains("LEFT JOIN data_map_graph_outbox go")
                        && sql.contains("go.generation_no=3")
                        && sql.contains("WHERE go.id IS NULL")
                        && sql.contains("ORDER BY s.id LIMIT 1000")));
    }
}
