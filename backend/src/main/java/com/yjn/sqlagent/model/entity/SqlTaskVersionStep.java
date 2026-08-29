package com.yjn.sqlagent.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("sql_task_version_step")
public class SqlTaskVersionStep {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private Integer versionNo;
    private Integer stepNo;
    private Integer stepOrder;
    private String stepName;
    private String stepSql;
    private String statementType;
    private String inputTables;
    private String outputTables;
    private String sqlChecksum;
    private LocalDateTime createTime;
}
