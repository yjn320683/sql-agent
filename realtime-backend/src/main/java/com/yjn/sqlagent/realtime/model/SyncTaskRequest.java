package com.yjn.sqlagent.realtime.model;

import com.yjn.sqlagent.realtime.config.RealtimeProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

public class SyncTaskRequest {
    private Long projectId;
    @NotBlank(message = "任务名称不能为空")
    private String name;
    @NotBlank(message = "负责人不能为空")
    private String owner;
    private String description;
    private String flinkVersion = "2.2.1";
    @NotNull(message = "请选择 MySQL Server")
    private Long sourceServerId;
    private String sourceType = "mysql-cdc";
    private Map<String, Object> taskConfig = new LinkedHashMap<>();
    private String expectedUpdateTime;

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long value) { projectId = value; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getFlinkVersion() { return flinkVersion; }
    public void setFlinkVersion(String value) { flinkVersion = value; }
    public Long getSourceServerId() { return sourceServerId; }
    public void setSourceServerId(Long value) { sourceServerId = value; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String value) { sourceType = value; }
    public String getTargetDatabase() { return RealtimeProperties.SYNC_TASK_TARGET_DATABASE; }
    public void setTargetDatabase(String value) { /* 平台固定目标库，兼容旧请求字段。 */ }
    public Map<String, Object> getTaskConfig() { return taskConfig; }
    public void setTaskConfig(Map<String, Object> value) { taskConfig = value == null ? new LinkedHashMap<>() : value; }
    public String getExpectedUpdateTime() { return expectedUpdateTime; }
    public void setExpectedUpdateTime(String value) { expectedUpdateTime = value; }
}
