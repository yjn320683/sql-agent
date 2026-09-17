package com.yjn.sqlagent.realtime.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.realtime")
public class RealtimeProperties {
    public static final String SYNC_TASK_TARGET_DATABASE = "ods_rt";
    public static final String SYNC_TASK_DEBUG_TARGET_DATABASE = "paimon_debug";

    private boolean enabled = true;
    private long defaultProjectId = 1L;
    private String flinkBin = "flink";
    private String hadoopBin = "hadoop";
    private String hadoopClasspath = "";
    private String yarnBin = "yarn";
    private String yarnWebUrl = "";
    private String yarnQueue = "root.default";
    private String submitJar = "../realtime-task-submit/target/realtime-task-submit.jar";
    private String submissionDir = "/tmp/sql-agent-realtime/submissions";
    private String submissionUriPrefix = "";
    private String paimonActionJarPath = "";
    private String paimonWarehouse = "";
    private String paimonDebugWarehouse = "";
    private String checkpointDir = "";
    private String savepointDir = "";
    private long syncDelayMs = 10000L;
    private boolean stateSyncEnabled = true;
    private int debugSuccessMinRunningMinutes = 2;
    private Map<String, String> catalogConf = defaultCatalogConf();
    private Map<String, String> defaultTableConf = new LinkedHashMap<>();
    private Map<String, String> mysqlDefaultConf = new LinkedHashMap<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getDefaultProjectId() { return defaultProjectId; }
    public void setDefaultProjectId(long value) { defaultProjectId = value; }
    public String getFlinkBin() { return flinkBin; }
    public void setFlinkBin(String value) { flinkBin = value; }
    public String getHadoopBin() { return hadoopBin; }
    public void setHadoopBin(String value) { hadoopBin = value; }
    public String getHadoopClasspath() { return hadoopClasspath; }
    public void setHadoopClasspath(String value) { hadoopClasspath = value; }
    public String getYarnBin() { return yarnBin; }
    public void setYarnBin(String value) { yarnBin = value; }
    public String getYarnWebUrl() { return yarnWebUrl; }
    public void setYarnWebUrl(String value) { yarnWebUrl = value; }
    public String getYarnQueue() { return yarnQueue; }
    public void setYarnQueue(String value) { yarnQueue = value; }
    public String resolveYarnQueue() {
        String value = yarnQueue == null ? "" : yarnQueue.trim();
        if (value.isEmpty()) throw new IllegalStateException("app.realtime.yarn-queue 未配置");
        return value;
    }
    public String getSubmitJar() { return submitJar; }
    public void setSubmitJar(String value) { submitJar = value; }
    public String getSubmissionDir() { return submissionDir; }
    public void setSubmissionDir(String value) { submissionDir = value; }
    public String getSubmissionUriPrefix() { return submissionUriPrefix; }
    public void setSubmissionUriPrefix(String value) { submissionUriPrefix = value; }
    public String getPaimonActionJarPath() { return paimonActionJarPath; }
    public void setPaimonActionJarPath(String value) { paimonActionJarPath = value; }
    public String getPaimonWarehouse() { return paimonWarehouse; }
    public void setPaimonWarehouse(String value) { paimonWarehouse = value; }
    public String getPaimonDebugWarehouse() { return paimonDebugWarehouse; }
    public void setPaimonDebugWarehouse(String value) { paimonDebugWarehouse = value; }
    public String getPaimonDebugTargetDatabase() { return SYNC_TASK_DEBUG_TARGET_DATABASE; }
    public void setPaimonDebugTargetDatabase(String value) { /* 平台固定值，保留绑定兼容。 */ }
    public String getTargetDatabase() { return SYNC_TASK_TARGET_DATABASE; }
    public void setTargetDatabase(String value) { /* 平台固定值，保留绑定兼容。 */ }
    public String getCheckpointDir() { return checkpointDir; }
    public void setCheckpointDir(String value) { checkpointDir = value; }
    public String getSavepointDir() { return savepointDir; }
    public void setSavepointDir(String value) { savepointDir = value; }
    public long getSyncDelayMs() { return syncDelayMs; }
    public void setSyncDelayMs(long value) { syncDelayMs = value; }
    public boolean isStateSyncEnabled() { return stateSyncEnabled; }
    public void setStateSyncEnabled(boolean value) { stateSyncEnabled = value; }
    public int getDebugSuccessMinRunningMinutes() { return debugSuccessMinRunningMinutes; }
    public void setDebugSuccessMinRunningMinutes(int value) { debugSuccessMinRunningMinutes = value; }
    public Map<String, String> getCatalogConf() { return catalogConf; }
    public void setCatalogConf(Map<String, String> value) { catalogConf = value; }
    public Map<String, String> getDefaultTableConf() { return defaultTableConf; }
    public void setDefaultTableConf(Map<String, String> value) { defaultTableConf = value; }
    public Map<String, String> getMysqlDefaultConf() { return mysqlDefaultConf; }
    public void setMysqlDefaultConf(Map<String, String> value) { mysqlDefaultConf = value; }

    private static Map<String, String> defaultCatalogConf() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("metastore", "hive");
        result.put("hive.metastore.uri.selection", "SEQUENTIAL");
        return result;
    }
}
