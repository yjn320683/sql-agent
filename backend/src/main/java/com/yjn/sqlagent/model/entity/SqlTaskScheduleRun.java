package com.yjn.sqlagent.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("sql_task_schedule_run")
public class SqlTaskScheduleRun {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long scheduleId;
    private Long taskId;
    private String triggerType;
    private LocalDateTime scheduledTime;
    private LocalDate businessDate;
    private String status;
    private Integer attemptNo;
    private Long executionId;
    private Long backfillBatchId;
    private String parameterValues;
    private String message;
    private String createdBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
