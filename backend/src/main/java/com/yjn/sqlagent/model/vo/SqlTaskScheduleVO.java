package com.yjn.sqlagent.model.vo;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;

@Data
public class SqlTaskScheduleVO {
    private Long id;
    private Long taskId;
    private String scheduleType;
    private String cronExpression;
    private String timezone;
    private Boolean enabled;
    private String concurrencyPolicy;
    private Integer maxRetries;
    private Integer retryIntervalSeconds;
    private Map<String, Object> parameters = new LinkedHashMap<>();
    private LocalDateTime nextTriggerTime;
    private LocalDateTime lastTriggerTime;
    private String lastRunStatus;
    private Long revision;
}
