package com.yjn.sqlagent.realtime.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjn.sqlagent.diagnostics.DiagnosticReport;
import com.yjn.sqlagent.diagnostics.DiagnosticReport.Action;
import com.yjn.sqlagent.diagnostics.DiagnosticReport.Evidence;
import com.yjn.sqlagent.diagnostics.DiagnosticReport.Finding;
import com.yjn.sqlagent.realtime.repository.RealtimeSyncRepository;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class RealtimeDiagnosticReportService {
    private static final Logger log = LoggerFactory.getLogger(RealtimeDiagnosticReportService.class);
    private static final String TARGET_KIND = "REALTIME_INSTANCE";
    private final RealtimeSyncRepository repository;
    private final RealtimeRuntimeService runtime;
    private final DiagnosticReportStore store;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public RealtimeDiagnosticReportService(RealtimeSyncRepository repository, RealtimeRuntimeService runtime,
            DiagnosticReportStore store, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.repository = repository;
        this.runtime = runtime;
        this.store = store;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public DiagnosticReport get(long taskId, long instanceId, boolean refresh) {
        repository.requiredInstance(taskId, instanceId);
        if (!refresh) {
            DiagnosticReport existing = store.latest(TARGET_KIND, instanceId);
            if (existing != null) return existing;
        }
        return store.save(generate(taskId, instanceId));
    }

    @Scheduled(initialDelayString = "${app.realtime.diagnostics-initial-delay-ms:45000}",
            fixedDelayString = "${app.realtime.diagnostics-scan-delay-ms:60000}")
    public void generateMissingFailedReports() {
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList("SELECT i.task_id taskId,i.id instanceId FROM rt_task_instance i "
                    + "LEFT JOIN task_diagnostic_report r ON r.target_kind='REALTIME_INSTANCE' AND r.target_id=i.id "
                    + "WHERE LOWER(i.status)='failed' AND r.id IS NULL ORDER BY i.update_time DESC LIMIT 10");
        } catch (RuntimeException ex) {
            log.debug("诊断表尚未就绪，跳过实时失败扫描：{}", ex.getMessage());
            return;
        }
        for (Map<String, Object> row : rows) {
            long taskId = ((Number) row.get("taskId")).longValue();
            long instanceId = ((Number) row.get("instanceId")).longValue();
            try { store.save(generate(taskId, instanceId)); }
            catch (RuntimeException ex) { log.warn("生成实时诊断失败 taskId={} instanceId={}", taskId, instanceId, ex); }
        }
    }

    private DiagnosticReport generate(long taskId, long instanceId) {
        Map<String, Object> instance = repository.requiredInstance(taskId, instanceId);
        DiagnosticReport report = new DiagnosticReport();
        report.setTargetKind(TARGET_KIND); report.setTargetId(instanceId);
        String status = text(instance.get("status"));
        add(report, "instance-status", "STATUS", "rt_task_instance", "实例状态", status, null);
        addIfPresent(report, "job-id", "IDENTIFIER", "Flink", "Job ID", instance.get("jobId"));
        addIfPresent(report, "application-id", "IDENTIFIER", "YARN", "Application ID", instance.get("yarnApplicationId"));
        addIfPresent(report, "savepoint", "STATE", "Flink", "Savepoint", instance.get("savepointPath"));
        addIfPresent(report, "failure-message", "ERROR", "rt_task_instance.failure_message", "失败摘要", instance.get("failureMessage"));
        addStartupEvidence(report, instance.get("startupLog"));
        collectRuntime(taskId, instanceId, instance, report);
        collectAlerts(taskId, instanceId, report);
        classify(report, status, joinedEvidence(report).toLowerCase(Locale.ROOT));
        report.setComplete(report.getMissingEvidence().isEmpty());
        report.setStatus(report.isComplete() ? "COMPLETE" : "PARTIAL");
        return report;
    }

    private void collectRuntime(long taskId, long instanceId, Map<String, Object> instance, DiagnosticReport report) {
        if (text(instance.get("jobId")).isEmpty()) {
            report.getMissingEvidence().add("实例没有 Job ID，无法采集 Flink 指标和 Checkpoint。");
            return;
        }
        try {
            Object value = runtime.runtime(taskId, instanceId);
            add(report, "runtime", "METRIC", "Flink REST", "运行指标摘要", compact(value), null);
        } catch (RuntimeException ex) {
            report.getMissingEvidence().add("Flink 运行指标不可用：" + safe(ex.getMessage()));
        }
        try {
            Object value = runtime.checkpoints(taskId, instanceId);
            add(report, "checkpoints", "CHECKPOINT", "Flink REST", "Checkpoint 摘要", compact(value), null);
        } catch (RuntimeException ex) {
            report.getMissingEvidence().add("Checkpoint 证据不可用：" + safe(ex.getMessage()));
        }
    }

    private void collectAlerts(long taskId, long instanceId, DiagnosticReport report) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT id,event_type eventType,severity,title,detail FROM rt_alert "
                    + "WHERE task_id=? AND (task_instance_id=? OR task_instance_id IS NULL) ORDER BY create_time DESC LIMIT 10",
                    taskId, instanceId);
            for (Map<String, Object> row : rows) {
                add(report, "alert-" + row.get("id"), "ALERT", "rt_alert", text(row.get("title")),
                        text(row.get("severity")) + " / " + safe(text(row.get("detail"))),
                        "/realtime/alerts?alertId=" + row.get("id"));
            }
        } catch (RuntimeException ex) {
            report.getMissingEvidence().add("告警证据不可用：" + safe(ex.getMessage()));
        }
    }

    private void addStartupEvidence(DiagnosticReport report, Object value) {
        String startup = safe(text(value));
        if (startup.isEmpty()) return;
        String tail = startup.length() > 4000 ? startup.substring(startup.length() - 4000) : startup;
        add(report, "startup-log", "LOG", "rt_task_instance.startup_log", "启动日志末尾", tail, null);
    }

    private void classify(DiagnosticReport report, String status, String evidence) {
        if (!"failed".equalsIgnoreCase(status)) {
            report.setFailureStage("NONE");
            report.setSummary("当前实例未处于失败状态，未发现可确认的故障根因。");
            return;
        }
        if (contains(evidence, "checkpoint", "savepoint") && contains(evidence, "fail", "error", "declin", "expire")) {
            finding(report, "CHECKPOINT_FAILURE", "CHECKPOINT", "Checkpoint/Savepoint 失败", "Checkpoint 或 Savepoint 证据包含失败状态。", "checkpoints", "检查状态存储、超时和作业反压");
        } else if (contains(evidence, "backpressure", "back pressured", "busytime") && contains(evidence, "high", "true", "critical")) {
            finding(report, "BACKPRESSURE", "RUNTIME", "Flink 作业持续反压", "Flink 运行指标或告警显示高反压。", "runtime", "检查下游 Sink、并行度和数据倾斜");
        } else if (contains(evidence, "schema", "column", "type mismatch", "incompatible")) {
            finding(report, "SCHEMA_MISMATCH", "SCHEMA", "Schema 不兼容", "失败日志或告警命中字段或类型不匹配。", "failure-message", "刷新实时表 Schema 并核对映射");
        } else if (contains(evidence, "connection", "refused", "timed out", "communications link")) {
            finding(report, "CONNECTION_FAILURE", "CONNECTION", "数据源或目标连接失败", "失败证据命中连接或网络错误。", "failure-message", "检查 Server、Catalog、网络和账号可用性");
        } else if (contains(evidence, "submit_error", "submission", "application failed")) {
            finding(report, "SUBMISSION_FAILURE", "SUBMISSION", "Flink/YARN 提交失败", "启动日志显示提交阶段失败。", "startup-log", "核对提交命令、Jar、YARN 资源和客户端配置");
        } else {
            report.setFailureStage("UNKNOWN"); report.setSummary("实例已失败，但当前证据不足以确定根因。");
            Finding value = new Finding(); value.setCode("UNDETERMINED"); value.setSeverity("WARNING"); value.setTitle("根因尚未确定");
            value.setCause("现有证据未命中可验证规则，不自动猜测。"); value.setImpact("需结合原始日志继续分析。");
            value.setEvidenceRefs(report.getEvidence().isEmpty() ? List.of() : List.of(report.getEvidence().get(0).getId()));
            value.setActions(List.of(action("MANUAL", "查看启动与运行日志", "核对完整错误栈后再选择恢复方式"),
                    action("RECOVER", "查看恢复选项", "使用来源实例的不可变快照创建新实例")));
            report.getFindings().add(value);
        }
    }

    private void finding(DiagnosticReport report, String code, String stage, String title, String cause,
            String evidenceRef, String actionLabel) {
        report.setFailureStage(stage); report.setSummary(title);
        Finding value = new Finding(); value.setCode(code); value.setSeverity("ERROR"); value.setTitle(title);
        value.setCause(cause); value.setImpact("当前实时实例未能正常运行。"); value.setEvidenceRefs(List.of(evidenceRef));
        value.setActions(List.of(action("MANUAL", actionLabel, "由用户核对证据后手动执行，平台不会自动修改。"),
                action("RECOVER", "查看恢复选项", "使用来源实例的不可变快照创建新实例")));
        report.getFindings().add(value);
    }

    private String joinedEvidence(DiagnosticReport report) {
        StringBuilder value = new StringBuilder();
        for (Evidence item : report.getEvidence()) value.append(item.getLabel()).append(' ').append(item.getValue()).append(' ');
        return value.toString();
    }
    private boolean contains(String value, String... terms) { for (String term : terms) if (value.contains(term)) return true; return false; }
    private void addIfPresent(DiagnosticReport report, String id, String type, String source, String label, Object value) { if (!text(value).isEmpty()) add(report, id, type, source, label, text(value), null); }
    private void add(DiagnosticReport report, String id, String type, String source, String label, String value, String link) { Evidence item = new Evidence(); item.setId(id); item.setType(type); item.setSource(source); item.setLabel(label); item.setValue(safe(value)); item.setLink(link); report.getEvidence().add(item); }
    private Action action(String type, String label, String description) { Action value = new Action(); value.setType(type); value.setLabel(label); value.setDescription(description); return value; }
    private String compact(Object value) { try { String json = mapper.writeValueAsString(value); return json.length() > 4000 ? json.substring(0, 4000) + "..." : json; } catch (Exception ex) { return text(value); } }
    private String safe(String value) { return value == null ? "" : value.replaceAll("(?i)(password|passwd|pwd)\\s*[=:]\\s*[^\\s,;]+", "$1=***"); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
}
