package com.yjn.sqlagent.datacompare.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yjn.sqlagent.datacompare.config.DataCompareProperties;
import com.yjn.sqlagent.datacompare.model.CompareRule;
import com.yjn.sqlagent.datacompare.model.TableColumn;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TableVerifyServiceTest {
    @Test
    void returnsPassedMetricsForEqualNonPartitionedTables() throws Exception {
        HiveJdbcClient hive = mock(HiveJdbcClient.class);
        List<TableColumn> columns = Arrays.asList(
                new TableColumn("id", "bigint", null, false),
                new TableColumn("amount", "decimal(18,2)", null, false));
        when(hive.columns(anyString(), any())).thenReturn(columns);
        when(hive.query(anyString(), any())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("COUNT(*) row_count")) {
                Map<String, Object> baseline = new LinkedHashMap<>();
                baseline.put("compare_side", 0L);
                baseline.put("row_count", 10L);
                baseline.put("crc_0", "12345");
                Map<String, Object> candidate = new LinkedHashMap<>();
                candidate.put("compare_side", 1L);
                candidate.put("row_count", 10L);
                candidate.put("crc_0", "12345");
                return Arrays.asList(baseline, candidate);
            }
            return Collections.emptyList();
        });
        TableVerifyService service = new TableVerifyService(hive, properties());

        Map<String, Object> result = service.verify(
                1, "dw.baseline", "dw.candidate", new CompareRule(), true, ignored -> { }, ignored -> { });

        assertTrue((Boolean) result.get("metadataSame"));
        assertTrue((Boolean) result.get("partitionSetsSame"));
        assertTrue((Boolean) result.get("rowCountSame"));
        assertTrue((Boolean) result.get("crcSame"));
        verify(hive, times(1)).query(anyString(), any());
    }

    @Test
    void stopsBeforeScanningWhenPartitionSchemasDiffer() throws Exception {
        HiveJdbcClient hive = mock(HiveJdbcClient.class);
        when(hive.columns(eq("dw.baseline"), any())).thenReturn(Collections.singletonList(
                new TableColumn("dt", "string", null, true)));
        when(hive.columns(eq("dw.candidate"), any())).thenReturn(Collections.singletonList(
                new TableColumn("day", "string", null, true)));
        TableVerifyService service = new TableVerifyService(hive, properties());

        Map<String, Object> result = service.verify(
                1, "dw.baseline", "dw.candidate", new CompareRule(), true, ignored -> { }, ignored -> { });

        assertFalse((Boolean) result.get("partitionSetsSame"));
        assertEquals("两侧分区字段不一致，未扫描业务数据", result.get("validationIssue"));
    }

    @Test
    void validatesBothPrimaryKeySidesWithOneHiveQuery() throws Exception {
        HiveJdbcClient hive = mock(HiveJdbcClient.class);
        when(hive.columns(anyString(), any())).thenReturn(Arrays.asList(
                new TableColumn("id", "bigint", null, false),
                new TableColumn("label", "string", null, false)));
        List<String> queries = new ArrayList<>();
        when(hive.query(anyString(), any())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            queries.add(sql);
            Map<String, Object> baseline = new LinkedHashMap<>();
            baseline.put("compare_side", 0L);
            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("compare_side", 1L);
            if (sql.contains("COUNT(*) row_count")) {
                baseline.put("row_count", 1L); baseline.put("crc_0", "12345");
                candidate.put("row_count", 1L); candidate.put("crc_0", "12345");
            } else {
                baseline.put("null_count", 0L); baseline.put("duplicate_groups", 0L);
                candidate.put("null_count", 0L); candidate.put("duplicate_groups", 0L);
            }
            return Arrays.asList(baseline, candidate);
        });
        CompareRule rule = new CompareRule();
        rule.setPrimaryKeyList(Collections.singletonList("id"));

        Map<String, Object> result = new TableVerifyService(hive, properties()).verify(
                2, "dw.baseline", "dw.candidate", rule, true, ignored -> { }, ignored -> { });

        assertTrue((Boolean) result.get("rowCountSame"));
        assertEquals(2, queries.size());
        assertTrue(queries.get(1).contains("SELECT 0 compare_side"));
        assertTrue(queries.get(1).contains("UNION ALL SELECT 1 compare_side"));
        verify(hive, times(2)).query(anyString(), any());
    }

    private DataCompareProperties properties() {
        DataCompareProperties properties = new DataCompareProperties();
        properties.setHiveTempDatabase("verify");
        return properties;
    }
}
