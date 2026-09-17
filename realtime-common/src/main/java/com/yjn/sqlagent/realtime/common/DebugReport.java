package com.yjn.sqlagent.realtime.common;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 计算、出仓无写入调试生成的结构化事实报告。 */
public class DebugReport {
    private String status = "PASSED";
    private String taskType;
    private String summary;
    private String generatedAt = Instant.now().toString();
    private String logicalPlan;
    private String physicalPlan;
    private List<String> inputs = new ArrayList<>();
    private List<String> outputs = new ArrayList<>();
    private List<Map<String, Object>> checks = new ArrayList<>();
    private List<Map<String, Object>> diagnostics = new ArrayList<>();

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(String generatedAt) { this.generatedAt = generatedAt; }
    public String getLogicalPlan() { return logicalPlan; }
    public void setLogicalPlan(String logicalPlan) { this.logicalPlan = logicalPlan; }
    public String getPhysicalPlan() { return physicalPlan; }
    public void setPhysicalPlan(String physicalPlan) { this.physicalPlan = physicalPlan; }
    public List<String> getInputs() { return new ArrayList<>(inputs); }
    public void setInputs(List<String> inputs) { this.inputs = inputs == null ? new ArrayList<>() : new ArrayList<>(inputs); }
    public List<String> getOutputs() { return new ArrayList<>(outputs); }
    public void setOutputs(List<String> outputs) { this.outputs = outputs == null ? new ArrayList<>() : new ArrayList<>(outputs); }
    public List<Map<String, Object>> getChecks() { return copy(checks); }
    public void setChecks(List<Map<String, Object>> checks) { this.checks = copy(checks); }
    public List<Map<String, Object>> getDiagnostics() { return copy(diagnostics); }
    public void setDiagnostics(List<Map<String, Object>> diagnostics) { this.diagnostics = copy(diagnostics); }

    public DebugReport check(String code, String subject, String message) {
        checks.add(item(code, "PASSED", subject, message));
        return this;
    }

    public DebugReport diagnostic(String code, String subject, String message) {
        diagnostics.add(item(code, "FAILED", subject, message));
        status = "FAILED";
        return this;
    }

    private Map<String, Object> item(String code, String status, String subject, String message) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("code", code); value.put("status", status);
        value.put("subject", subject); value.put("message", message);
        return value;
    }

    private List<Map<String, Object>> copy(List<Map<String, Object>> value) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (value != null) for (Map<String, Object> item : value) result.add(new LinkedHashMap<>(item));
        return result;
    }
}
