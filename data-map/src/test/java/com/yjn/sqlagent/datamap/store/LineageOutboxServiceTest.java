package com.yjn.sqlagent.datamap.store;

import com.yjn.sqlagent.datamap.config.DataMapProperties;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        LineageOutboxService service = new LineageOutboxService(jdbc, properties());

        service.enqueueMissing(1000);

        verify(jdbc).update(org.mockito.ArgumentMatchers.<String>argThat(sql ->
                sql.contains("LEFT JOIN data_map_graph_outbox go")
                        && sql.contains("go.generation_no=3")
                        && sql.contains("WHERE go.id IS NULL")
                        && sql.contains("ORDER BY s.id LIMIT 1000")));
    }

    @Test
    void staleProcessingEventsAreRequeuedOrFailedByAttemptCount() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), eq(5), eq(600), eq(5))).thenReturn(1);
        when(jdbc.update(anyString(), eq(600), eq(5))).thenReturn(1);
        LineageOutboxService service = new LineageOutboxService(jdbc, properties());

        service.recoverStaleProcessing();

        verify(jdbc).update(org.mockito.ArgumentMatchers.<String>argThat(sql ->
                sql.contains("status='FAILED'") && sql.contains("locked_at<TIMESTAMPADD")), eq(5), eq(600), eq(5));
        verify(jdbc).update(org.mockito.ArgumentMatchers.<String>argThat(sql ->
                sql.contains("status='PENDING'") && sql.contains("locked_at<TIMESTAMPADD")), eq(600), eq(5));
        verify(jdbc).update(org.mockito.ArgumentMatchers.<String>argThat(sql ->
                sql.contains("s.snapshot_id=o.snapshot_id") && sql.contains("s.generation_no=o.generation_no")));
    }

    @Test
    void duplicateSuccessDoesNotIncrementGenerationOrOverwriteNewerTaskState() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), eq(42L))).thenReturn(0);
        LineageOutboxService service = new LineageOutboxService(jdbc, properties());

        service.succeeded(42L, "OFFLINE", 7L, 9L);

        verify(jdbc, never()).update(org.mockito.ArgumentMatchers.<String>argThat(sql ->
                sql.contains("projected_count=projected_count+1")), any(Object[].class));
        verify(jdbc, never()).update(org.mockito.ArgumentMatchers.<String>argThat(sql ->
                sql.contains("projection_status='PROJECTED'")), any(Object[].class));
    }

    @Test
    void successUpdatesOnlyTheStateForTheClaimedSnapshotAndGeneration() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), eq(42L))).thenReturn(1);
        LineageOutboxService service = new LineageOutboxService(jdbc, properties());

        service.succeeded(42L, "REALTIME", 7L, 9L);

        verify(jdbc).update(org.mockito.ArgumentMatchers.<String>argThat(sql ->
                        sql.contains("s.snapshot_id=o.snapshot_id") && sql.contains("s.generation_no=o.generation_no")
                                && sql.contains("projection_status='PROJECTED'")),
                eq(42L), eq(9L), eq("REALTIME"), eq(7L));
    }

    private DataMapProperties properties() {
        DataMapProperties properties = new DataMapProperties();
        properties.setMaxAttempts(5);
        properties.setProcessingLeaseSeconds(600);
        return properties;
    }
}
