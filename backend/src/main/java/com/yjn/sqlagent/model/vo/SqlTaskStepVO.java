package com.yjn.sqlagent.model.vo;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class SqlTaskStepVO {
    private Long id;
    private Integer stepNo;
    private Integer stepOrder;
    private String stepName;
    private String sql;
    private String statementType;
    private List<String> inputTables = new ArrayList<>();
    private List<String> outputTables = new ArrayList<>();
    private String status;
    private String queryId;
    private List<String> applicationIds = new ArrayList<>();
    private List<String> jobIds = new ArrayList<>();
    private String errorMessage;
    private String logFile;
    private java.time.LocalDateTime startedAt;
    private java.time.LocalDateTime finishedAt;
    private Long durationMs;
}
