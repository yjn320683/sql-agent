package com.yjn.sqlagent.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("sql_task")
public class SqlTask {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String description;
    private String taskType;
    private String executionFrequency;
    private String owner;
    private Boolean enabled;
    private String sqlContent;
    private String ddlContent;
    private String parameterSchema;
    private String sqlChecksum;
    private Long revision;
    private Integer effectiveVersionNo;
    private Boolean archived;
    private String archivedBy;
    private LocalDateTime archivedTime;
    private String createdBy;
    private String updatedBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableField(exist = false)
    private String lastExecutionStatus;

    @TableField(exist = false)
    private LocalDateTime lastExecutionAt;

    @TableField(exist = false)
    private Integer latestVersionNo;

    @TableField(exist = false)
    private Integer stepCount;
}
