package com.yjn.sqlagent.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("sql_task_dependency")
public class SqlTaskDependency {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private Long upstreamTaskId;
    private String dependencyType;
    private String createdBy;
    private LocalDateTime createTime;
}
