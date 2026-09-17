package com.yjn.sqlagent.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("sql_task_schedule")
public class SqlTaskSchedule {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private String scheduleType;
    private String cronExpression;
    private String timezone;
    private Boolean enabled;
    private String concurrencyPolicy;
    private Integer maxRetries;
    private Integer retryIntervalSeconds;
    private Integer executionTimeoutSeconds;
    private Integer slaDurationMinutes;
    private String timeoutPolicy;
    private String parameterValues;
    private LocalDateTime nextTriggerTime;
    private LocalDateTime lastTriggerTime;
    private String lastRunStatus;
    private Long revision;
    private String createdBy;
    private String updatedBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
