package com.yjn.sqlagent.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("sql_task_version")
public class SqlTaskVersion {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private Integer versionNo;
    private Integer baseEffectiveVersionNo;
    private String baseEffectiveChecksum;
    private String name;
    private String description;
    private String sqlContent;
    private String ddlContent;
    private String parameterSchema;
    private String sqlChecksum;
    private String versionNote;
    private String status;
    private Long revision;
    private String createdBy;
    private String updatedBy;
    private String effectiveBy;
    private LocalDateTime effectiveTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
