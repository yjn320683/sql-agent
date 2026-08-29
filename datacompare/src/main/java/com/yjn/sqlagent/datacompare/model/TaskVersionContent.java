package com.yjn.sqlagent.datacompare.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class TaskVersionContent {
    private long taskId;
    /** 0 表示尚未版本化的任务初始生效代码。 */
    private int versionNo;
    private String status;
    private String name;
    private String description;
    private String note;
    private String sql;
    private String ddl;
    private String parameterSchema;
    private String checksum;
    private long revision;
    private Integer baseEffectiveVersionNo;
    private String baseEffectiveChecksum;
}
