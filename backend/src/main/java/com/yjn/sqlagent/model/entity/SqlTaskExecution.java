package com.yjn.sqlagent.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("sql_task_execution")
public class SqlTaskExecution {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private String taskNameSnapshot;
    private String sqlSnapshot;
    private String parameterSchemaSnapshot;
    private String renderedSqlSnapshot;
    private String parameterValues;
    private java.time.LocalDate businessDate;
    private String sourceType;
    private Integer taskVersionNo;
    private Long taskRevision;
    private Long sourceExecutionId;
    private String replayStrategy;
    private String status;
    private Integer currentStepNo;
    private Integer totalSteps;
    private Integer succeededSteps;
    private Integer failedStepNo;
    private String requestedBy;
    private String executorInstanceId;
    private String queryId;
    private String applicationIds;
    private String jobIds;
    private String errorMessage;
    private LocalDateTime submittedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime heartbeatAt;
    private LocalDateTime updateTime;
}
