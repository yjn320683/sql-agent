package com.yjn.sqlagent.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("sql_task_backfill_batch")
public class SqlTaskBackfillBatch {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private LocalDate startDate;
    private LocalDate endDate;
    private String status;
    private Integer totalCount;
    private Integer submittedCount;
    private Integer succeededCount;
    private Integer failedCount;
    private Integer maxConcurrency;
    private String parameterValues;
    private String requestedBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
