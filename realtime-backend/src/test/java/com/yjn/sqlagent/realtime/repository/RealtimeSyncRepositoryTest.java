package com.yjn.sqlagent.realtime.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjn.sqlagent.realtime.config.RealtimeProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class RealtimeSyncRepositoryTest {

    @Test
    void mysqlBitBooleanIsNormalizedAsOne() {
        assertEquals(1, RealtimeSyncRepository.integer(Boolean.TRUE));
        assertEquals(0, RealtimeSyncRepository.integer(Boolean.FALSE));
        assertEquals(1, RealtimeSyncRepository.integer((byte) 1));
    }

    @Test
    void flinkMemoryValuesAreConvertedToMegabytes() {
        assertEquals(2048, RealtimeSyncRepository.memoryMb("2g"));
        assertEquals(1536, RealtimeSyncRepository.memoryMb("1.5GB"));
        assertEquals(512, RealtimeSyncRepository.memoryMb("512mb"));
        assertEquals(null, RealtimeSyncRepository.memoryMb("unknown"));
    }

    @Test
    void missingOrUnknownSortUsesSafeDefault() {
        RealtimeSyncRepository repository = new RealtimeSyncRepository(
                mock(JdbcTemplate.class), new ObjectMapper(), new RealtimeProperties());
        assertEquals("COALESCE(l.create_time,t.update_time)", repository.sortColumn(null));
        assertEquals("COALESCE(l.create_time,t.update_time)", repository.sortColumn("not-a-column"));
        assertEquals("t.task_name", repository.sortColumn("name"));
    }

    @Test
    void activeProductionChecksExcludeDebugInstances() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(8L))).thenReturn(1);
        RealtimeSyncRepository repository = new RealtimeSyncRepository(
                jdbc, new ObjectMapper(), new RealtimeProperties());

        assertTrue(repository.hasActiveManagedInstance(8L));
        assertTrue(repository.hasActiveImportedInstance(8L));
        assertTrue(repository.hasActiveProductionInstance(8L));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(3))
                .queryForObject(sql.capture(), eq(Integer.class), eq(8L));
        assertTrue(sql.getAllValues().stream().allMatch(value -> value.contains("execution_mode='PRODUCTION'")));
    }

    @Test
    void changeActionsMatchReferenceProjectLabels() {
        assertEquals("创建", RealtimeSyncRepository.normalizeChangeAction("CREATE"));
        assertEquals("编辑", RealtimeSyncRepository.normalizeChangeAction("EDIT"));
        assertEquals("删除", RealtimeSyncRepository.normalizeChangeAction("DELETE"));
        assertEquals("启动", RealtimeSyncRepository.normalizeChangeAction("START"));
        assertEquals("DEBUG_START", RealtimeSyncRepository.normalizeChangeAction("DEBUG_START"));
        assertEquals("DEBUG_STOP", RealtimeSyncRepository.normalizeChangeAction("DEBUG_STOP"));
        assertEquals("状态同步", RealtimeSyncRepository.normalizeChangeAction("REFRESH"));
        assertEquals("编辑", RealtimeSyncRepository.normalizeChangeAction("编辑任务"));
    }

    @Test
    void debugOperationsAreNotTaskChangeLogs() {
        assertTrue(RealtimeSyncRepository.isTaskChangeLogRow(Map.of("action", "启动")));
        assertTrue(!RealtimeSyncRepository.isTaskChangeLogRow(Map.of(
                "action", "停止", "jobExecutionMode", "DEBUG")));
        assertTrue(!RealtimeSyncRepository.isTaskChangeLogRow(Map.of("action", "DEBUG_START")));
        assertTrue(!RealtimeSyncRepository.isTaskChangeLogRow(Map.of("action", "确认告警")));
    }

    @Test
    void changeListFiltersDebugAndAlertOperations() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Map<String, Object> edit = new LinkedHashMap<>();
        edit.put("id", 1L);
        edit.put("action", "EDIT");
        Map<String, Object> debug = new LinkedHashMap<>();
        debug.put("id", 2L);
        debug.put("action", "DEBUG_START");
        debug.put("jobExecutionMode", "DEBUG");
        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("id", 3L);
        alert.put("action", "ALERT_ACK");
        when(jdbc.queryForList(anyString(), eq(8L))).thenReturn(List.of(edit, debug, alert));
        RealtimeSyncRepository repository = new RealtimeSyncRepository(
                jdbc, new ObjectMapper(), new RealtimeProperties());

        List<Map<String, Object>> rows = repository.changes(8L);

        assertEquals(1, rows.size());
        assertEquals("编辑", rows.get(0).get("action"));
    }

    @Test
    void changeListIncludesReferenceDetailKindAndSummary() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Map<String, Object> edit = new LinkedHashMap<>();
        edit.put("id", 9L);
        edit.put("action", "EDIT");
        edit.put("operator", "admin");
        edit.put("beforeVersionId", 10L);
        edit.put("afterVersionId", 11L);
        edit.put("detail", "编辑任务配置");
        when(jdbc.queryForList(anyString(), eq(8L))).thenReturn(List.of(edit));
        RealtimeSyncRepository repository = new RealtimeSyncRepository(
                jdbc, new ObjectMapper(), new RealtimeProperties());

        Map<String, Object> row = repository.changes(8L).get(0);

        assertEquals("编辑", row.get("action"));
        assertEquals("edit", row.get("detailKind"));
        assertEquals("编辑任务配置", row.get("summary"));
    }
}
