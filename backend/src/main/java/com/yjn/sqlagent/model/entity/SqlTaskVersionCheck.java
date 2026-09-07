package com.yjn.sqlagent.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("sql_task_version_check")
public class SqlTaskVersionCheck {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private Integer versionNo;
    private Long versionRevision;
    private String versionChecksum;
    private String checkType;
    private String status;
    private Boolean passed;
    private Boolean complete;
    private Integer errorCount;
    private Integer warningCount;
    private Long durationMs;
    private String resultSummary;
    private String resultPayload;
    private String checkedBy;
    private LocalDateTime checkedAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
