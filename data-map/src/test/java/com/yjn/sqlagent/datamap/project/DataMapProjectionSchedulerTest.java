package com.yjn.sqlagent.datamap.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yjn.sqlagent.datamap.config.DataMapProperties;
import com.yjn.sqlagent.datamap.graph.GraphStoreClient;
import com.yjn.sqlagent.datamap.store.LineageOutboxService;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.KeyHolder;

class DataMapProjectionSchedulerTest {
    @Test
    void retriesSchemaInitializationAfterNeo4jRecovers() {
        LineageOutboxService outbox = mock(LineageOutboxService.class);
        DataMapGraphProjector projector = mock(DataMapGraphProjector.class);
        GraphStoreClient graph = mock(GraphStoreClient.class);
        LineageSnapshotBootstrapper bootstrapper = mock(LineageSnapshotBootstrapper.class);
        DataMapProperties properties = new DataMapProperties();
        properties.setEnabled(true);
        when(graph.isConfigured()).thenReturn(true);
        when(graph.ping()).thenReturn(true);
        doThrow(new IllegalStateException("not ready")).doNothing().when(projector).initializeSchema();
        when(outbox.pending(isNull(), anyInt())).thenReturn(Collections.emptyList());
        when(outbox.buildingGenerations()).thenReturn(Collections.emptyList());
        when(bootstrapper.backfill(20)).thenReturn(2);
        DataMapProjectionScheduler scheduler = new DataMapProjectionScheduler(outbox, projector, graph,
                properties, mock(JdbcTemplate.class), List.of(bootstrapper));

        scheduler.initialize();
        int projected = scheduler.projectPendingNow();

        assertEquals(0, projected);
        verify(projector, times(2)).initializeSchema();
        verify(bootstrapper).backfill(20);
        verify(outbox).enqueueMissing(1000);
    }

    @Test
    void releasesSchedulerGuardWhenRunRecordCannotBeCreated() {
        LineageOutboxService outbox = mock(LineageOutboxService.class);
        DataMapGraphProjector projector = mock(DataMapGraphProjector.class);
        GraphStoreClient graph = mock(GraphStoreClient.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DataMapProperties properties = new DataMapProperties();
        properties.setEnabled(true);
        when(graph.isConfigured()).thenReturn(true);
        when(graph.ping()).thenReturn(true);
        when(outbox.activeGeneration()).thenReturn(1L);
        when(jdbc.update(any(PreparedStatementCreator.class), any(KeyHolder.class)))
                .thenThrow(new IllegalStateException("database unavailable"));
        DataMapProjectionScheduler scheduler = new DataMapProjectionScheduler(outbox, projector, graph,
                properties, jdbc, Collections.emptyList());
        scheduler.initialize();

        scheduler.incremental();
        scheduler.incremental();

        verify(outbox, times(2)).activeGeneration();
    }
}
