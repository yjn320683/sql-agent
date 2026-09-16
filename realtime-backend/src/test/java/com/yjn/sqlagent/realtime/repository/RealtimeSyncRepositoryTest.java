package com.yjn.sqlagent.realtime.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

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

    @Test
    void rebuildMappingsPersistsOneHundredTablesWithStableOrderAndReferences() {
        JdbcTemplate jdbc = mappingJdbc("bulk");
        RealtimeSyncRepository repository = new RealtimeSyncRepository(
                jdbc, new ObjectMapper(), new RealtimeProperties());
        List<String> tables = IntStream.range(0, 100)
                .mapToObj(index -> "source_" + index).collect(Collectors.toList());

        repository.rebuildMappings(11L, 3L, "ods_real", mappingConfig(tables), "tester");

        assertEquals(100, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rt_realtime_table WHERE producer_task_id=11", Integer.class));
        assertEquals(100, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rt_sync_task_table_mapping WHERE task_id=11", Integer.class));
        assertEquals(100, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rt_task_table_reference WHERE task_id=11 AND reference_role='OUTPUT'", Integer.class));
        List<String> persisted = jdbc.query("SELECT source_table FROM rt_sync_task_table_mapping"
                        + " WHERE task_id=11 ORDER BY sort_order",
                (rs, rowNum) -> rs.getString(1));
        assertEquals(tables, persisted);
    }

    @Test
    void producerConflictIsReportedBeforeAnyMappingOrReferenceIsWritten() {
        JdbcTemplate jdbc = mappingJdbc("conflict");
        jdbc.update("INSERT INTO rt_realtime_table(catalog_name,database_name,table_name,table_type,creation_source,"
                        + "producer_task_id,physical_status,table_options_json,operator)"
                        + " VALUES('paimon','ods_real','prefix_source_1','primary_key','sync',99,'declared','{}','other')");
        RealtimeSyncRepository repository = new RealtimeSyncRepository(
                jdbc, new ObjectMapper(), new RealtimeProperties());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> repository.rebuildMappings(11L, 3L, "ods_real",
                        mappingConfig(List.of("source_0", "source_1")), "tester"));

        assertTrue(error.getMessage().contains("ods_real.prefix_source_1"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rt_sync_task_table_mapping WHERE task_id=11", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rt_task_table_reference WHERE task_id=11", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rt_realtime_table WHERE producer_task_id=11", Integer.class));
    }

    private JdbcTemplate mappingJdbc(String suffix) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:sync_mapping_" + suffix + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE rt_server(id BIGINT PRIMARY KEY,name VARCHAR(128),type VARCHAR(32),address VARCHAR(255),"
                + "database_name VARCHAR(128),database_prefix VARCHAR(32),account VARCHAR(128),password VARCHAR(255),"
                + "description VARCHAR(255),operator VARCHAR(64))");
        jdbc.execute("CREATE TABLE rt_realtime_table(id BIGINT AUTO_INCREMENT PRIMARY KEY,catalog_name VARCHAR(64),"
                + "database_name VARCHAR(128),table_name VARCHAR(128),table_type VARCHAR(32),creation_source VARCHAR(32),"
                + "producer_task_id BIGINT,physical_status VARCHAR(32),table_options_json CLOB,operator VARCHAR(64),"
                + "update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,UNIQUE(catalog_name,database_name,table_name))");
        jdbc.execute("CREATE TABLE rt_sync_task_table_mapping(id BIGINT AUTO_INCREMENT PRIMARY KEY,task_id BIGINT,"
                + "source_server_id BIGINT,source_database VARCHAR(128),source_table VARCHAR(128),target_database VARCHAR(128),"
                + "target_table VARCHAR(128),realtime_table_id BIGINT,sort_order INT,UNIQUE(task_id,sort_order),"
                + "UNIQUE(source_server_id,source_table))");
        jdbc.execute("CREATE TABLE rt_task_table_reference(id BIGINT AUTO_INCREMENT PRIMARY KEY,task_id BIGINT,"
                + "realtime_table_id BIGINT,reference_role VARCHAR(16),UNIQUE(task_id,realtime_table_id,reference_role))");
        jdbc.update("INSERT INTO rt_server(id,name,type,address,database_name,database_prefix,account,password,description,operator)"
                + " VALUES(3,'source','mysql','127.0.0.1:3306','source_db','','reader','secret','','tester')");
        return jdbc;
    }

    private Map<String, Object> mappingConfig(List<String> tables) {
        Map<String, Object> cdc = new LinkedHashMap<>();
        cdc.put("databaseName", "source_db");
        cdc.put("selectedTables", tables);
        cdc.put("tablePrefix", "prefix_");
        cdc.put("tableSuffix", "");
        return Map.of("cdcConfig", cdc);
    }
}
