package com.yjn.sqlagent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjn.sqlagent.mapper.TaskLineageSnapshotMapper;
import com.yjn.sqlagent.model.entity.TaskLineageSnapshot;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class TaskLineageSnapshotServiceTest {
    @Test
    void savedSnapshotContainsFactsButNotSqlBodyAndIsIdempotent() {
        TaskLineageSnapshotMapper mapper = Mockito.mock(TaskLineageSnapshotMapper.class);
        TaskLineageSnapshotService service = new TaskLineageSnapshotService(mapper, new ObjectMapper());
        TaskLineageFacts facts = TaskLineageFacts.fromJson(Map.of(
                "statementCount", 1,
                "inputs", List.of(Map.of("qualifiedName", "ods.orders", "table", "orders", "db", "ods")),
                "outputs", List.of(), "diagnostics", List.of(), "complete", true));

        service.saveOffline(8L, 12L, 3, "checksum", "default", "SAVED", facts);

        ArgumentCaptor<TaskLineageSnapshot> captor = ArgumentCaptor.forClass(TaskLineageSnapshot.class);
        verify(mapper).insert(captor.capture());
        assertFalse(captor.getValue().getLineageJson().contains("SELECT"));
        assertEquals("parse-sql-v2", captor.getValue().getParserVersion());

        when(mapper.selectExact("OFFLINE", 8L, 3, "checksum", "default"))
                .thenReturn(captor.getValue());
        service.saveOffline(8L, 12L, 3, "checksum", "default", "SAVED", facts);
        verify(mapper, Mockito.times(1)).insert(any(TaskLineageSnapshot.class));
    }
}
