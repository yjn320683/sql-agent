package com.yjn.sqlagent.realtime.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjn.sqlagent.diagnostics.DiagnosticReport;
import com.yjn.sqlagent.realtime.repository.RealtimeSyncRepository;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.mockito.ArgumentCaptor;

class RealtimeDiagnosticReportServiceTest {
    @Test
    void taskDdlEvidenceDoesNotChangeInstanceRootCauseAndRefreshesCachedReport() {
        RealtimeSyncRepository repository = mock(RealtimeSyncRepository.class);
        DiagnosticReportStore store = mock(DiagnosticReportStore.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(repository.requiredInstance(5L, 7L)).thenReturn(Map.of("id", 7L, "status", "canceled"));
        when(store.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(jdbc.queryForList(anyString(), anyLong())).thenReturn(java.util.List.of(Map.of(
                "id", 9L, "databaseName", "db", "tableName", "orders", "ddlSql", "ALTER TABLE orders DROP COLUMN price",
                "createTime", "2026-10-13 10:00:00")));
        RealtimeDiagnosticReportService service = new RealtimeDiagnosticReportService(repository,
                mock(RealtimeRuntimeService.class), store, jdbc, new ObjectMapper());
        DiagnosticReport report = service.get(5L, 7L, true);
        assertThat(report.getFailureStage()).isEqualTo("NONE");
        assertThat(report.getFindings()).singleElement().satisfies(finding -> {
            assertThat(finding.getCode()).isEqualTo("TASK_DDL_ACTIVE");
            assertThat(finding.getCause()).contains("未包含实例 ID");
            assertThat(finding.getActions().get(0).getType()).isEqualTo("VIEW_DDL");
        });
        when(store.latest("REALTIME_INSTANCE", 7L)).thenReturn(report);
        assertThat(service.get(5L, 7L, false).getFindings()).hasSize(1);
        when(jdbc.queryForList(anyString(), anyLong())).thenReturn(java.util.List.of());
        DiagnosticReport cleared = service.get(5L, 7L, false);
        assertThat(cleared.getFindings()).isEmpty();
        assertThat(cleared.getEvidence()).noneMatch(item -> item.getId().startsWith("task-ddl-"));
    }

    @Test
    void classifiesConnectionFailureAndMasksPassword() {
        RealtimeSyncRepository repository = mock(RealtimeSyncRepository.class);
        RealtimeRuntimeService runtime = mock(RealtimeRuntimeService.class);
        DiagnosticReportStore store = mock(DiagnosticReportStore.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(repository.requiredInstance(5L, 7L)).thenReturn(Map.of(
                "id", 7L, "status", "failed", "failureMessage", "connection refused password=secret"));
        when(store.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(jdbc.queryForList(anyString(), anyLong(), anyLong())).thenReturn(java.util.List.of());

        DiagnosticReport report = new RealtimeDiagnosticReportService(repository, runtime, store, jdbc,
                new ObjectMapper()).get(5L, 7L, true);

        assertThat(report.getFailureStage()).isEqualTo("CONNECTION");
        assertThat(report.getFindings()).singleElement().extracting(DiagnosticReport.Finding::getCode)
                .isEqualTo("CONNECTION_FAILURE");
        assertThat(report.getEvidence()).extracting(DiagnosticReport.Evidence::getValue)
                .allMatch(value -> !value.contains("secret"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), anyLong(), anyLong());
        assertThat(sql.getValue()).contains("event_type eventType").doesNotContain("alert_type");
    }
}
