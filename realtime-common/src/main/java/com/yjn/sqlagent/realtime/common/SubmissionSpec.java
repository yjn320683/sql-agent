package com.yjn.sqlagent.realtime.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 后端与 Flink 提交 Jar 之间的不可变任务快照。 */
public class SubmissionSpec {

    private Long taskId;
    private Long versionId;
    private Long taskInstanceId;
    private String jobName;
    private String startType = "direct";
    private String statePath;
    private String executionMode = "PRODUCTION";
    private String configHash;
    private TaskSpec task = new TaskSpec();
    private RuntimeConfig runtimeConfig = new RuntimeConfig();
    private List<ServerSnapshot> servers = new ArrayList<>();

    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Long getVersionId() { return versionId; }
    public void setVersionId(Long versionId) { this.versionId = versionId; }
    public Long getTaskInstanceId() { return taskInstanceId; }
    public void setTaskInstanceId(Long value) { taskInstanceId = value; }
    public String getJobName() { return jobName; }
    public void setJobName(String jobName) { this.jobName = jobName; }
    public String getStartType() { return startType; }
    public void setStartType(String startType) { this.startType = startType; }
    public String getStatePath() { return statePath; }
    public void setStatePath(String statePath) { this.statePath = statePath; }
    public String getExecutionMode() { return executionMode; }
    public void setExecutionMode(String executionMode) { this.executionMode = executionMode; }
    public String getConfigHash() { return configHash; }
    public void setConfigHash(String configHash) { this.configHash = configHash; }
    public TaskSpec getTask() { return task; }
    public void setTask(TaskSpec task) { this.task = task == null ? new TaskSpec() : task; }
    public RuntimeConfig getRuntimeConfig() { return runtimeConfig; }
    public void setRuntimeConfig(RuntimeConfig runtimeConfig) {
        this.runtimeConfig = runtimeConfig == null ? new RuntimeConfig() : runtimeConfig;
    }
    public List<ServerSnapshot> getServers() { return new ArrayList<>(servers); }
    public void setServers(List<ServerSnapshot> servers) {
        this.servers = servers == null ? new ArrayList<>() : new ArrayList<>(servers);
    }

    public static class TaskSpec {
        private long id;
        private String taskType = "sync";
        private String sourceType = "mysql-cdc";
        private String targetType = "paimon";
        private Integer checkpointInterval;
        private Integer parallelism;
        private Map<String, Object> taskConfig = new LinkedHashMap<>();

        public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public String getTaskType() { return taskType; }
        public void setTaskType(String taskType) { this.taskType = taskType; }
        public String getSourceType() { return sourceType; }
        public void setSourceType(String sourceType) { this.sourceType = sourceType; }
        public String getTargetType() { return targetType; }
        public void setTargetType(String targetType) { this.targetType = targetType; }
        public Integer getCheckpointInterval() { return checkpointInterval; }
        public void setCheckpointInterval(Integer checkpointInterval) { this.checkpointInterval = checkpointInterval; }
        public Integer getParallelism() { return parallelism; }
        public void setParallelism(Integer parallelism) { this.parallelism = parallelism; }
        public Map<String, Object> getTaskConfig() { return new LinkedHashMap<>(taskConfig); }
        public void setTaskConfig(Map<String, Object> taskConfig) {
            this.taskConfig = taskConfig == null ? new LinkedHashMap<>() : new LinkedHashMap<>(taskConfig);
        }
    }

    public static class RuntimeConfig {
        private String paimonActionJarPath;
        private String paimonWarehouse;
        private String paimonDebugWarehouse;
        private String targetDatabase;
        private Map<String, String> catalogConf = new LinkedHashMap<>();
        private Map<String, String> defaultTableConf = new LinkedHashMap<>();
        private Map<String, String> mysqlDefaultConf = new LinkedHashMap<>();

        public String getPaimonActionJarPath() { return paimonActionJarPath; }
        public void setPaimonActionJarPath(String value) { this.paimonActionJarPath = value; }
        public String getPaimonWarehouse() { return paimonWarehouse; }
        public void setPaimonWarehouse(String value) { this.paimonWarehouse = value; }
        public String getPaimonDebugWarehouse() { return paimonDebugWarehouse; }
        public void setPaimonDebugWarehouse(String value) { this.paimonDebugWarehouse = value; }
        public String getTargetDatabase() { return targetDatabase; }
        public void setTargetDatabase(String value) { this.targetDatabase = value; }
        public Map<String, String> getCatalogConf() { return new LinkedHashMap<>(catalogConf); }
        public void setCatalogConf(Map<String, String> value) { catalogConf = copy(value); }
        public Map<String, String> getDefaultTableConf() { return new LinkedHashMap<>(defaultTableConf); }
        public void setDefaultTableConf(Map<String, String> value) { defaultTableConf = copy(value); }
        public Map<String, String> getMysqlDefaultConf() { return new LinkedHashMap<>(mysqlDefaultConf); }
        public void setMysqlDefaultConf(Map<String, String> value) { mysqlDefaultConf = copy(value); }
        private Map<String, String> copy(Map<String, String> value) {
            return value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
        }
    }

    public static class ServerSnapshot {
        private Long id;
        private String name;
        private String address;
        private String databaseName;
        private String databasePrefix;
        private String account;
        private String password;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getAddress() { return address; }
        public void setAddress(String address) { this.address = address; }
        public String getDatabaseName() { return databaseName; }
        public void setDatabaseName(String value) { databaseName = value; }
        public String getDatabasePrefix() { return databasePrefix; }
        public void setDatabasePrefix(String value) { databasePrefix = value; }
        public String getAccount() { return account; }
        public void setAccount(String account) { this.account = account; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }
}
