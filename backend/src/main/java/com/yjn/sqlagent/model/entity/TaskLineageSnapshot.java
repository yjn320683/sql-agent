package com.yjn.sqlagent.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 任务版本的不可变血缘事实快照。 */
@Data
@TableName("task_lineage_snapshot")
public class TaskLineageSnapshot {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String taskScope;
    private Long taskId;
    private Long versionId;
    private Integer versionNo;
    private String sqlChecksum;
    private String dialect;
    private String defaultDatabase;
    private String parserVersion;
    private String snapshotSource;
    private Boolean completeFlag;
    private String lineageJson;
    private String diagnosticsJson;
    private LocalDateTime createTime;
}
