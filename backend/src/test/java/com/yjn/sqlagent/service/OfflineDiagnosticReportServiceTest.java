package com.yjn.sqlagent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yjn.sqlagent.diagnostics.DiagnosticReport;
import com.yjn.sqlagent.model.vo.SqlTaskStepVO;
import com.yjn.sqlagent.model.vo.TaskExecutionVO;
import com.yjn.sqlagent.realtime.service.DiagnosticReportStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class OfflineDiagnosticReportServiceTest {
    @Test
    void classifiesSqlFailureAndKeepsEvidenceReference() {
        TaskExecutionService executions = mock(TaskExecutionService.class);
        AgentProxyService agent = mock(AgentProxyService.class);
        DiagnosticReportStore store = mock(DiagnosticReportStore.class);
        TaskExecutionVO execution = new TaskExecutionVO();
        execution.setId(12L); execution.setTaskId(8L); execution.setStatus("FAILED");
        execution.setErrorMessage("SemanticException: table not found");
        SqlTaskStepVO step = new SqlTaskStepVO(); step.setStepNo(2); step.setStepName("write"); step.setStatus("FAILED");
        execution.setSteps(List.of(step));
        when(executions.get(12L)).thenReturn(execution);
        when(agent.getTaskExecutionDiagnostics(12L)).thenReturn(Map.of("complete", true,
                "aggregate", Map.of("jobCount", 0, "metrics", Map.of())));
        when(store.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DiagnosticReport report = new OfflineDiagnosticReportService(executions, agent, store,
                mock(JdbcTemplate.class)).get(12L, true);

        assertThat(report.getFailureStage()).isEqualTo("DEPENDENCY");
        assertThat(report.getFindings()).singleElement().satisfies(finding -> {
            assertThat(finding.getCode()).isEqualTo("DEPENDENCY_MISSING");
            assertThat(finding.getEvidenceRefs()).containsExactly("execution-error");
        });
        assertThat(report.getEvidence()).extracting(DiagnosticReport.Evidence::getId)
                .contains("execution-error", "failed-step", "job-count");
    }

    @Test
    void reportsPartialInsteadOfGuessingWhenRuntimeEvidenceIsUnavailable() {
        TaskExecutionService executions = mock(TaskExecutionService.class);
        AgentProxyService agent = mock(AgentProxyService.class);
        DiagnosticReportStore store = mock(DiagnosticReportStore.class);
        TaskExecutionVO execution = new TaskExecutionVO();
        execution.setId(15L); execution.setTaskId(9L); execution.setStatus("FAILED"); execution.setSteps(List.of());
        when(executions.get(15L)).thenReturn(execution);
        when(agent.getTaskExecutionDiagnostics(15L)).thenThrow(new IllegalStateException("JobHistory unavailable"));
        when(store.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DiagnosticReport report = new OfflineDiagnosticReportService(executions, agent, store,
                mock(JdbcTemplate.class)).get(15L, true);

        assertThat(report.isComplete()).isFalse();
        assertThat(report.getStatus()).isEqualTo("PARTIAL");
        assertThat(report.getFailureStage()).isEqualTo("UNKNOWN");
        assertThat(report.getFindings()).singleElement().extracting(DiagnosticReport.Finding::getCode)
                .isEqualTo("UNDETERMINED");
    }
}
