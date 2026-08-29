package com.yjn.sqlagent.model.vo;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

@Data
public class TaskExecutionVO {
    private Long id;
    private Long taskId;
    private String taskName;
    private String sourceType;
    private Integer taskVersionNo;
    private Long taskRevision;
    private String status;
    private Integer currentStepNo;
    private Integer totalSteps;
    private Integer succeededSteps;
    private Integer failedStepNo;
    private java.time.LocalDate businessDate;
    private java.util.Map<String, Object> parameters = new java.util.LinkedHashMap<>();
    private String requestedBy;
    private String queryId;
    private List<String> applicationIds;
    private List<String> jobIds;
    private String errorMessage;
    private LocalDateTime submittedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;
    private List<SqlTaskStepVO> steps = new java.util.ArrayList<>();
}
