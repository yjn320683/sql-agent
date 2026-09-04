package com.yjn.sqlagent.realtime.model;

import java.util.LinkedHashMap;
import java.util.Map;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/** 统一任务接口请求；当前平台只接受 sync 类型。 */
public class UnifiedTaskRequest {
    private Long taskId;
    @NotBlank(message = "任务类型不能为空")
    private String taskType = "sync";
    @NotBlank(message = "任务名称不能为空")
    private String name;
    @NotBlank(message = "负责人不能为空")
    private String owner;
    private String description;
    @NotBlank(message = "Flink 版本不能为空")
    private String flinkVersion = "2.2.1";
    private String expectedUpdateTime;
    private String startType = "direct";
    private String statePath;
    @NotNull(message = "告警配置不能为空")
    private Map<String, Object> alarmConfig = new LinkedHashMap<>();
    @NotNull(message = "Flink 配置不能为空")
    private Map<String, Object> flinkConf = new LinkedHashMap<>();
    @NotNull(message = "同步任务配置不能为空")
    private Map<String, Object> taskConfig = new LinkedHashMap<>();

    public SyncTaskRequest toSyncTaskRequest() {
        if (!"sync".equalsIgnoreCase(taskType)) throw new IllegalArgumentException("当前仅支持实时同步任务");
        Map<String, Object> specific = copy(taskConfig);
        Map<String, Object> cdc = map(specific.get("cdcConfig"));
        cdc.put("mode", "combined");
        Long sourceServerId = number(specific.get("sourceServerId"));
        if (sourceServerId == null) sourceServerId = number(cdc.get("sourceServerId"));
        String targetDatabase = text(cdc.get("targetDatabase"));
        if (targetDatabase.isEmpty()) targetDatabase = text(specific.get("targetDatabase"));

        specific.put("sourceServerId", sourceServerId);
        copyIfPresent(flinkConf, specific, "parallelism", "parallelism");
        copyIfPresent(flinkConf, specific, "checkpointIntervalSeconds", "checkpointInterval");
        Object tm = flinkConf.get("taskManagerMemoryGb");
        if (tm != null) specific.put("taskManagerMemory", memory(tm));
        Object jm = flinkConf.get("jobManagerMemoryGb");
        if (jm != null) specific.put("jobManagerMemory", memory(jm));
        copyIfPresent(flinkConf, specific, "flinkConfOverrides", "flinkConfOverrides");
        copyIfPresent(alarmConfig, specific, "alarmType", "alarmType");
        copyIfPresent(alarmConfig, specific, "alarmGroup", "alarmGroup");
        specific.put("cdcConfig", cdc);

        SyncTaskRequest request = new SyncTaskRequest();
        request.setName(name); request.setOwner(owner); request.setDescription(description);
        request.setFlinkVersion(flinkVersion); request.setSourceServerId(sourceServerId);
        request.setSourceType("mysql-cdc"); request.setTargetDatabase(targetDatabase);
        request.setExpectedUpdateTime(expectedUpdateTime); request.setTaskConfig(specific);
        return request;
    }

    public TaskActionRequest toActionRequest() {
        TaskActionRequest request = new TaskActionRequest();
        request.setStartType(startType); request.setStatePath(statePath);
        request.setParallelism(integer(flinkConf.get("parallelism")));
        request.setCheckpointInterval(integer(flinkConf.get("checkpointIntervalSeconds")));
        request.setTaskManagerMemory(memoryOrNull(flinkConf.get("taskManagerMemoryGb")));
        request.setJobManagerMemory(memoryOrNull(flinkConf.get("jobManagerMemoryGb")));
        request.setFlinkConfOverrides(stringMap(flinkConf.get("flinkConfOverrides")));
        Map<String, Object> cdc = map(taskConfig.get("cdcConfig"));
        request.setMysqlConfOverrides(stringMap(cdc.get("mysqlConfOverrides")));
        request.setTableConfOverrides(stringMap(cdc.get("tableConfOverrides")));
        return request;
    }

    private static void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String from, String to) {
        if (source.containsKey(from)) target.put(to, source.get(from));
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) {
        return value instanceof Map ? new LinkedHashMap<>((Map<String, Object>) value) : new LinkedHashMap<>();
    }
    private static Map<String, Object> copy(Map<String, Object> value) { return value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value); }
    private static Map<String, String> stringMap(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        map(value).forEach((key, item) -> { if (item != null) result.put(key, String.valueOf(item)); });
        return result;
    }
    private static Long number(Object value) { try { return value == null ? null : Long.valueOf(String.valueOf(value)); } catch (NumberFormatException ex) { return null; } }
    private static Integer integer(Object value) { try { return value == null ? null : Integer.valueOf(String.valueOf(value)); } catch (NumberFormatException ex) { return null; } }
    private static String memory(Object value) { return String.valueOf(value).replaceAll("(?i)gb?$", "") + "GB"; }
    private static String memoryOrNull(Object value) { return value == null ? null : memory(value); }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }

    public Long getTaskId() { return taskId; } public void setTaskId(Long value) { taskId = value; }
    public String getTaskType() { return taskType; } public void setTaskType(String value) { taskType = value; }
    public String getName() { return name; } public void setName(String value) { name = value; }
    public String getOwner() { return owner; } public void setOwner(String value) { owner = value; }
    public String getDescription() { return description; } public void setDescription(String value) { description = value; }
    public String getFlinkVersion() { return flinkVersion; } public void setFlinkVersion(String value) { flinkVersion = value; }
    public String getExpectedUpdateTime() { return expectedUpdateTime; } public void setExpectedUpdateTime(String value) { expectedUpdateTime = value; }
    public String getStartType() { return startType; } public void setStartType(String value) { startType = value; }
    public String getStatePath() { return statePath; } public void setStatePath(String value) { statePath = value; }
    public Map<String, Object> getAlarmConfig() { return alarmConfig; } public void setAlarmConfig(Map<String, Object> value) { alarmConfig = copy(value); }
    public Map<String, Object> getFlinkConf() { return flinkConf; } public void setFlinkConf(Map<String, Object> value) { flinkConf = copy(value); }
    public Map<String, Object> getTaskConfig() { return taskConfig; } public void setTaskConfig(Map<String, Object> value) { taskConfig = copy(value); }
}
