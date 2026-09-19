package com.yjn.sqlagent.realtime.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjn.sqlagent.diagnostics.DiagnosticReport;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 统一保存诊断快照，不修改原执行实例。 */
@Service
public class DiagnosticReportStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public DiagnosticReportStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public DiagnosticReport latest(String targetKind, long targetId) {
        List<String> rows = jdbc.query(
                "SELECT report_json FROM task_diagnostic_report WHERE target_kind=? AND target_id=? ORDER BY revision DESC LIMIT 1",
                (rs, row) -> rs.getString(1), targetKind, targetId);
        if (rows.isEmpty()) return null;
        try { return mapper.readValue(rows.get(0), DiagnosticReport.class); }
        catch (Exception ex) { throw new IllegalStateException("诊断报告无法反序列化", ex); }
    }

    @Transactional
    public DiagnosticReport save(DiagnosticReport report) {
        Integer revision = jdbc.queryForObject(
                "SELECT COALESCE(MAX(revision),0)+1 FROM task_diagnostic_report WHERE target_kind=? AND target_id=?",
                Integer.class, report.getTargetKind(), report.getTargetId());
        report.setRevision(revision == null ? 1 : revision);
        report.setGeneratedAt(LocalDateTime.now());
        try {
            jdbc.update("INSERT INTO task_diagnostic_report(target_kind,target_id,revision,report_status,complete_flag,failure_stage,summary,report_json,generated_at) VALUES(?,?,?,?,?,?,?,?,?)",
                    report.getTargetKind(), report.getTargetId(), report.getRevision(), report.getStatus(),
                    report.isComplete(), report.getFailureStage(), report.getSummary(),
                    mapper.writeValueAsString(report), report.getGeneratedAt());
            return report;
        } catch (Exception ex) {
            throw new IllegalStateException("诊断报告保存失败", ex);
        }
    }
}
