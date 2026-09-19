package com.yjn.sqlagent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjn.sqlagent.datamap.store.LineageOutboxService;
import com.yjn.sqlagent.realtime.repository.LineageRelationRepository;
import com.yjn.sqlagent.realtime.service.ManagedSqlAnalyzer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class HistoricalLineageSnapshotBootstrapperTest {
    @Test
    void backfillsMissingOfflineSnapshotWithoutTouchingTaskContent() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TaskSqlStructureService analyzer = mock(TaskSqlStructureService.class);
        TaskLineageSnapshotService snapshots = mock(TaskLineageSnapshotService.class);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("taskId", 27L);
        row.put("sqlContent", "insert into dwd.target select id from ods.source");
        row.put("sqlChecksum", "checksum");
        row.put("versionNo", 0);
        when(jdbc.queryForList(contains("FROM sql_task t"), anyInt())).thenReturn(List.of(row));
        when(jdbc.queryForList(contains("FROM rt_task t"), anyInt())).thenReturn(Collections.emptyList());
        TaskLineageFacts facts = TaskLineageFacts.failure("TEST", "partial");
        when(analyzer.analyzeLineage(eq((String) row.get("sqlContent")), eq("default"))).thenReturn(facts);
        HistoricalLineageSnapshotBootstrapper bootstrapper = new HistoricalLineageSnapshotBootstrapper(
                jdbc, new ObjectMapper(), analyzer, snapshots, mock(ManagedSqlAnalyzer.class),
                mock(LineageRelationRepository.class), mock(LineageOutboxService.class));

        int completed = bootstrapper.backfill(20);

        assertEquals(1, completed);
        verify(snapshots).saveOffline(27L, null, 0, "checksum", "default", "BACKFILLED", facts);
    }
}
