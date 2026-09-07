package com.yjn.sqlagent.model.vo;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;

@Data
public class TaskVersionCheckSummaryVO {
    private Long taskId;
    private Integer versionNo;
    private boolean allPassed;
    private boolean readyToActivate;
    private String blockingReason;
    private Map<String, CheckItemVO> checks = new LinkedHashMap<>();

    @Data
    public static class CheckItemVO {
        private String type;
        private String status;
        private Boolean passed;
        private Boolean complete;
        private Integer errorCount;
        private Integer warningCount;
        private Long durationMs;
        private String summary;
        private String checkedBy;
        private LocalDateTime checkedAt;
    }
}
