package com.yjn.sqlagent.diagnostics;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 离线执行与实时实例共用的诊断报告协议。 */
public class DiagnosticReport {
    private String targetKind;
    private Long targetId;
    private Integer revision;
    private String status;
    private boolean complete;
    private String failureStage;
    private String summary;
    private LocalDateTime generatedAt;
    private List<Finding> findings = new ArrayList<>();
    private List<Evidence> evidence = new ArrayList<>();
    private List<String> missingEvidence = new ArrayList<>();

    public String getTargetKind() { return targetKind; }
    public void setTargetKind(String value) { targetKind = value; }
    public Long getTargetId() { return targetId; }
    public void setTargetId(Long value) { targetId = value; }
    public Integer getRevision() { return revision; }
    public void setRevision(Integer value) { revision = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public boolean isComplete() { return complete; }
    public void setComplete(boolean value) { complete = value; }
    public String getFailureStage() { return failureStage; }
    public void setFailureStage(String value) { failureStage = value; }
    public String getSummary() { return summary; }
    public void setSummary(String value) { summary = value; }
    public LocalDateTime getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(LocalDateTime value) { generatedAt = value; }
    public List<Finding> getFindings() { return findings; }
    public void setFindings(List<Finding> value) { findings = value == null ? new ArrayList<>() : value; }
    public List<Evidence> getEvidence() { return evidence; }
    public void setEvidence(List<Evidence> value) { evidence = value == null ? new ArrayList<>() : value; }
    public List<String> getMissingEvidence() { return missingEvidence; }
    public void setMissingEvidence(List<String> value) { missingEvidence = value == null ? new ArrayList<>() : value; }

    public static class Finding {
        private String code;
        private String severity;
        private String title;
        private String cause;
        private String impact;
        private List<String> evidenceRefs = new ArrayList<>();
        private List<Action> actions = new ArrayList<>();

        public String getCode() { return code; }
        public void setCode(String value) { code = value; }
        public String getSeverity() { return severity; }
        public void setSeverity(String value) { severity = value; }
        public String getTitle() { return title; }
        public void setTitle(String value) { title = value; }
        public String getCause() { return cause; }
        public void setCause(String value) { cause = value; }
        public String getImpact() { return impact; }
        public void setImpact(String value) { impact = value; }
        public List<String> getEvidenceRefs() { return evidenceRefs; }
        public void setEvidenceRefs(List<String> value) { evidenceRefs = value == null ? new ArrayList<>() : value; }
        public List<Action> getActions() { return actions; }
        public void setActions(List<Action> value) { actions = value == null ? new ArrayList<>() : value; }
    }

    public static class Evidence {
        private String id;
        private String type;
        private String source;
        private String label;
        private String value;
        private String link;

        public String getId() { return id; }
        public void setId(String value) { id = value; }
        public String getType() { return type; }
        public void setType(String value) { type = value; }
        public String getSource() { return source; }
        public void setSource(String value) { source = value; }
        public String getLabel() { return label; }
        public void setLabel(String value) { label = value; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
        public String getLink() { return link; }
        public void setLink(String value) { link = value; }
    }

    public static class Action {
        private String type;
        private String label;
        private String description;
        private String link;

        public String getType() { return type; }
        public void setType(String value) { type = value; }
        public String getLabel() { return label; }
        public void setLabel(String value) { label = value; }
        public String getDescription() { return description; }
        public void setDescription(String value) { description = value; }
        public String getLink() { return link; }
        public void setLink(String value) { link = value; }
    }
}
