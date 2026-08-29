package com.yjn.sqlagent.realtime.model;

import java.util.Map;

public class TaskActionRequest {
    private String startType = "direct";
    private String statePath;
    private String stopType = "direct";
    private boolean dryRun;
    private Integer parallelism;
    private Integer checkpointInterval;
    private String taskManagerMemory;
    private String jobManagerMemory;
    private Map<String, String> flinkConfOverrides;
    private Map<String, String> mysqlConfOverrides;
    private Map<String, String> tableConfOverrides;

    public String getStartType() { return startType; }
    public void setStartType(String value) { startType = value; }
    public String getStatePath() { return statePath; }
    public void setStatePath(String value) { statePath = value; }
    public String getStopType() { return stopType; }
    public void setStopType(String value) { stopType = value; }
    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean value) { dryRun = value; }
    public Integer getParallelism() { return parallelism; }
    public void setParallelism(Integer value) { parallelism = value; }
    public Integer getCheckpointInterval() { return checkpointInterval; }
    public void setCheckpointInterval(Integer value) { checkpointInterval = value; }
    public String getTaskManagerMemory() { return taskManagerMemory; }
    public void setTaskManagerMemory(String value) { taskManagerMemory = value; }
    public String getJobManagerMemory() { return jobManagerMemory; }
    public void setJobManagerMemory(String value) { jobManagerMemory = value; }
    public Map<String, String> getFlinkConfOverrides() { return flinkConfOverrides; }
    public void setFlinkConfOverrides(Map<String, String> value) { flinkConfOverrides = value; }
    public Map<String, String> getMysqlConfOverrides() { return mysqlConfOverrides; }
    public void setMysqlConfOverrides(Map<String, String> value) { mysqlConfOverrides = value; }
    public Map<String, String> getTableConfOverrides() { return tableConfOverrides; }
    public void setTableConfOverrides(Map<String, String> value) { tableConfOverrides = value; }
}
