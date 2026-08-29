package com.yjn.sqlagent.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("sql_task_execution_step")
public class SqlTaskExecutionStep {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long executionId;
    private Long taskId;
    private Integer stepNo;
    private Integer stepOrder;
    private String stepName;
    private String sourceSqlSnapshot;
    private String renderedSqlSnapshot;
    private String status;
    private String queryId;
    private String applicationIds;
    private String jobIds;
    private String errorMessage;
    private String logFile;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime updateTime;
}
