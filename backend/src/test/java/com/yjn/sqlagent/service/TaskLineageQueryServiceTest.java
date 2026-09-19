package com.yjn.sqlagent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjn.sqlagent.mapper.SqlTaskMapper;
import com.yjn.sqlagent.mapper.SqlTaskVersionMapper;
import com.yjn.sqlagent.model.entity.SqlTask;
import com.yjn.sqlagent.parsesql.TableColumnMetadata;
import com.yjn.sqlagent.parsesql.TableSchema;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskLineageQueryServiceTest {
    private SqlTaskMapper tasks;
    private SqlTaskVersionMapper versions;
    private TaskLineageSnapshotService snapshots;
    private AgentProxyService agent;
    private TaskLineageQueryService service;

    @BeforeEach
    void setUp() {
        tasks = mock(SqlTaskMapper.class);
        versions = mock(SqlTaskVersionMapper.class);
        snapshots = mock(TaskLineageSnapshotService.class);
        agent = mock(AgentProxyService.class);
        TaskSqlStructureService structures = new TaskSqlStructureService(new ObjectMapper(),
                table -> Optional.of(new TableSchema(table,
                        List.of(new TableColumnMetadata("id", "BIGINT", false)))));
        service = new TaskLineageQueryService(tasks, versions, structures, snapshots, agent);
    }

    @Test
    void lineageUsesJavaParserAndBackfillsImmutableSnapshot() {
        SqlTask task = task(7L, "汇总", "INSERT INTO dw.summary SELECT id FROM ods.orders", "sum-1");
        task.setEffectiveVersionNo(3);
        when(tasks.selectById(7L)).thenReturn(task);
        when(agent.getHiveTable(anyString(), anyString())).thenReturn(Map.of(
                "table", Map.of("tableType", "MANAGED_TABLE", "owner", "data")));

        Map<String, Object> result = service.lineage(7L, null, "default");

        assertEquals("java-parse-sql+hive-metastore", result.get("source"));
        assertEquals(List.of("ods.orders"), qualified(result, "inputs"));
        assertEquals(List.of("dw.summary"), qualified(result, "outputs"));
        assertEquals(true, result.get("complete"));
        verify(snapshots).saveOffline(anyLong(), any(), anyInt(), anyString(),
                anyString(), anyString(), any(TaskLineageFacts.class));
    }

    @Test
    void dependenciesAreBuiltFromSameJavaFacts() {
        SqlTask producer = task(1L, "生产订单", "INSERT INTO dw.orders SELECT * FROM ods.raw_orders", "p1");
        SqlTask consumer = task(2L, "消费订单", "INSERT INTO ads.orders SELECT * FROM dw.orders", "c1");
        when(tasks.selectById(2L)).thenReturn(consumer);
        when(tasks.countTasks("", "", "")).thenReturn(2L);
        when(tasks.listTasks("", "", "", 0, 500)).thenReturn(List.of(producer, consumer));

        Map<String, Object> result = service.dependencies(2L, null, "default");

        @SuppressWarnings("unchecked") List<Map<String, Object>> upstream =
                (List<Map<String, Object>>) result.get("directUpstream");
        assertEquals(1, upstream.size());
        assertEquals(1L, upstream.get(0).get("taskId"));
        assertEquals(List.of("dw.orders"), upstream.get(0).get("tables"));
        assertEquals("sql-agent-db+java-parse-sql", result.get("source"));
        assertTrue(((List<?>) result.get("parseFailures")).isEmpty());
    }

    @SuppressWarnings("unchecked")
    private List<String> qualified(Map<String, Object> result, String key) {
        return ((List<Map<String, Object>>) result.get(key)).stream()
                .map(item -> String.valueOf(item.get("qualifiedName"))).collect(java.util.stream.Collectors.toList());
    }

    private SqlTask task(long id, String name, String sql, String checksum) {
        SqlTask task = new SqlTask();
        task.setId(id); task.setName(name); task.setSqlContent(sql); task.setSqlChecksum(checksum);
        task.setEffectiveVersionNo(1); task.setArchived(false); task.setUpdateTime(LocalDateTime.now());
        return task;
    }
}
