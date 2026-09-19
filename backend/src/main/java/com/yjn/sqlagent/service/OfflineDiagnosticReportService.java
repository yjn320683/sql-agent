package com.yjn.sqlagent.service;

import com.yjn.sqlagent.diagnostics.DiagnosticReport;
import com.yjn.sqlagent.diagnostics.DiagnosticReport.Action;
import com.yjn.sqlagent.diagnostics.DiagnosticReport.Evidence;
import com.yjn.sqlagent.diagnostics.DiagnosticReport.Finding;
import com.yjn.sqlagent.model.vo.SqlTaskStepVO;
import com.yjn.sqlagent.model.vo.TaskExecutionVO;
import com.yjn.sqlagent.realtime.service.DiagnosticReportStore;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class OfflineDiagnosticReportService {
    private static final Logger log = LoggerFactory.getLogger(OfflineDiagnosticReportService.class);
    private static final String TARGET_KIND = "OFFLINE_EXECUTION";
    private final TaskExecutionService executions;
    private final AgentProxyService agent;
    private final DiagnosticReportStore store;
    private final JdbcTemplate jdbc;

    public OfflineDiagnosticReportService(TaskExecutionService executions, AgentProxyService agent,
            DiagnosticReportStore store, JdbcTemplate jdbc) {
        this.executions = executions;
        this.agent = agent;
        this.store = store;
        this.jdbc = jdbc;
    }

    public DiagnosticReport get(long executionId, boolean refresh) {
        executions.get(executionId);
        if (!refresh) {
            DiagnosticReport existing = store.latest(TARGET_KIND, executionId);
            if (existing != null) return existing;
        }
        return store.save(generate(executionId));
    }

    /** 终态失败即使未打开详情，也会在后台生成首份报告。 */
    @Scheduled(initialDelayString = "${sql-agent.diagnostics.initial-delay-ms:30000}",
            fixedDelayString = "${sql-agent.diagnostics.scan-delay-ms:60000}")
    public void generateMissingFailedReports() {
        List<Long> ids;
        try {
            ids = jdbc.query("SELECT e.id FROM sql_task_execution e LEFT JOIN task_diagnostic_report r "
                            + "ON r.target_kind='OFFLINE_EXECUTION' AND r.target_id=e.id "
                            + "WHERE e.status='FAILED' AND r.id IS NULL ORDER BY e.update_time DESC LIMIT 10",
                    (rs, row) -> rs.getLong(1));
        } catch (RuntimeException ex) {
            log.debug("诊断表尚未就绪，跳过离线失败扫描：{}", ex.getMessage());
            return;
        }
        for (Long id : ids) {
            try { store.save(generate(id)); }
            catch (RuntimeException ex) { log.warn("生成离线诊断失败 executionId={}", id, ex); }
        }
    }

    private DiagnosticReport generate(long executionId) {
        TaskExecutionVO execution = executions.get(executionId);
        DiagnosticReport report = base(execution);
        List<Evidence> evidence = report.getEvidence();
        evidence.add(evidence("execution-status", "STATUS", "sql_task_execution", "执行状态",
                execution.getStatus(), "/tasks/" + execution.getTaskId() + "/executions/" + executionId));
        if (text(execution.getErrorMessage()).length() > 0) {
            evidence.add(evidence("execution-error", "ERROR", "sql_task_execution.error_message", "失败摘要",
                    execution.getErrorMessage(), "/tasks/" + execution.getTaskId() + "/executions/" + executionId));
        }
        SqlTaskStepVO failedStep = execution.getSteps() == null ? null : execution.getSteps().stream()
                .filter(step -> "FAILED".equalsIgnoreCase(step.getStatus())).findFirst().orElse(null);
        if (failedStep != null) {
            evidence.add(evidence("failed-step", "STEP", "sql_task_execution_step", "失败 Step",
                    failedStep.getStepNo() + " - " + text(failedStep.getStepName()),
                    "/tasks/" + execution.getTaskId() + "/executions/" + executionId + "?stepNo=" + failedStep.getStepNo()));
        }

        Map<String, Object> raw = Collections.emptyMap();
        try {
            raw = agent.getTaskExecutionDiagnostics(executionId);
            addRuntimeEvidence(report, raw);
        } catch (RuntimeException ex) {
            report.getMissingEvidence().add("JobHistory/YARN 运行事实不可用：" + safe(ex.getMessage()));
        }

        String message = text(execution.getErrorMessage()) + " " + text(failedStep == null ? null : failedStep.getErrorMessage());
        classify(report, execution, message.toLowerCase(Locale.ROOT));
        boolean rawComplete = Boolean.TRUE.equals(raw.get("complete"));
        report.setComplete(report.getMissingEvidence().isEmpty() && (raw.isEmpty() || rawComplete));
        report.setStatus(report.isComplete() ? "COMPLETE" : "PARTIAL");
        return report;
    }

    private DiagnosticReport base(TaskExecutionVO execution) {
        DiagnosticReport report = new DiagnosticReport();
        report.setTargetKind(TARGET_KIND);
        report.setTargetId(execution.getId());
        report.setGeneratedAt(LocalDateTime.now());
        report.setSummary("正在整理执行证据");
        return report;
    }

    @SuppressWarnings("unchecked")
    private void addRuntimeEvidence(DiagnosticReport report, Map<String, Object> raw) {
        Object warnings = raw.get("warnings");
        if (warnings instanceof Iterable<?>) for (Object item : (Iterable<?>) warnings) {
            if (!text(item).isEmpty()) report.getMissingEvidence().add(text(item));
        }
        Object aggregateValue = raw.get("aggregate");
        if (!(aggregateValue instanceof Map<?, ?>)) return;
        Map<String, Object> aggregate = (Map<String, Object>) aggregateValue;
        report.getEvidence().add(evidence("job-count", "METRIC", "JobHistory", "Job 数",
                text(aggregate.get("jobCount")), null));
        Object metricsValue = aggregate.get("metrics");
        if (metricsValue instanceof Map<?, ?>) {
            Map<?, ?> metrics = (Map<?, ?>) metricsValue;
            addMetric(report, metrics, "failedTaskCount", "失败 Task 数");
            addMetric(report, metrics, "SPILLED_RECORDS", "Spill 记录数");
            addMetric(report, metrics, "GC_TIME_MILLIS", "GC 时间");
        }
    }

    private void addMetric(DiagnosticReport report, Map<?, ?> metrics, String key, String label) {
        Object value = metrics.get(key);
        if (value != null) report.getEvidence().add(evidence("metric-" + key, "METRIC", "JobHistory", label, text(value), null));
    }

    private void classify(DiagnosticReport report, TaskExecutionVO execution, String message) {
        if (!"FAILED".equalsIgnoreCase(execution.getStatus())) {
            report.setFailureStage("NONE");
            report.setSummary("当前实例未处于失败状态，未发现可确认的故障根因。");
            return;
        }
        if (contains(message, "table not found", "no such table", "does not exist", "unknown table")) {
            addFinding(report, "DEPENDENCY_MISSING", "ERROR", "上游表或依赖缺失", "失败证据指向不存在的表或视图。", "DEPENDENCY", "execution-error", "核对数据库、表名和上游产出");
        } else if (contains(message, "parse", "syntax", "semanticexception", "sqlstate")) {
            addFinding(report, "SQL_ERROR", "ERROR", "SQL 解析或语义校验失败", "SQL 错误摘要中包含明确的解析或语义错误。", "SQL", "execution-error", "打开失败 Step 并修正 SQL");
        } else if (contains(message, "outofmemory", "oom", "container killed", "memory limit", "exceeding memory")) {
            addFinding(report, "RESOURCE_MEMORY", "ERROR", "作业内存资源不足", "失败证据命中内存超限或容器被终止。", "RESOURCE", "execution-error", "检查数据量、Shuffle 和执行资源");
        } else if (contains(message, "connection", "connect timed out", "socket", "network", "refused")) {
            addFinding(report, "CONNECTION_FAILURE", "ERROR", "外部服务连接失败", "失败证据包含连接超时或网络错误。", "CONNECTION", "execution-error", "检查 Hive、YARN 及依赖服务状态");
        } else {
            report.setFailureStage("UNKNOWN");
            report.setSummary("实例已失败，但当前证据不足以确定根因。");
            Finding finding = new Finding();
            finding.setCode("UNDETERMINED"); finding.setSeverity("WARNING"); finding.setTitle("根因尚未确定");
            finding.setCause("现有错误摘要未命中可验证的诊断规则，需结合原始日志继续分析。");
            finding.setImpact("不自动推断故障类型。");
            finding.setEvidenceRefs(report.getEvidence().isEmpty() ? List.of() : List.of(report.getEvidence().get(0).getId()));
            finding.setActions(List.of(action("VIEW_LOG", "查看原始日志", "根据完整错误栈和 Query ID 继续定位", null)));
            report.getFindings().add(finding);
        }
    }

    private void addFinding(DiagnosticReport report, String code, String severity, String title, String cause,
            String stage, String evidenceRef, String actionLabel) {
        report.setFailureStage(stage); report.setSummary(title);
        Finding finding = new Finding();
        finding.setCode(code); finding.setSeverity(severity); finding.setTitle(title); finding.setCause(cause);
        finding.setImpact("当前执行实例未能完成。"); finding.setEvidenceRefs(List.of(evidenceRef));
        finding.setActions(List.of(action("MANUAL", actionLabel, "确认证据后由用户手动执行，平台不会自动修改或重跑。", null)));
        report.getFindings().add(finding);
    }

    private boolean contains(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }
    private Evidence evidence(String id, String type, String source, String label, String value, String link) {
        Evidence result = new Evidence(); result.setId(id); result.setType(type); result.setSource(source);
        result.setLabel(label); result.setValue(safe(value)); result.setLink(link); return result;
    }
    private Action action(String type, String label, String description, String link) {
        Action result = new Action(); result.setType(type); result.setLabel(label); result.setDescription(description); result.setLink(link); return result;
    }
    private String safe(String value) { return value == null ? "" : value.replaceAll("(?i)(password|passwd|pwd)\\s*[=:]\\s*[^\\s,;]+", "$1=***"); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
}
