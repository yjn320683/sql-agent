package com.yjn.sqlagent.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("sql_task_backfill_item")
public class SqlTaskBackfillItem {
    @TableId(type = IdType.AUTO) private Long id;
    private Long batchId;
    private Long taskId;
    private LocalDate businessDate;
    private String status;
    private Long executionId;
    private Integer attemptNo;
    private String message;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
