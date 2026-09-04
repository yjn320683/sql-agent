package com.yjn.sqlagent.realtime.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.realtime")
public class RealtimeProperties {
    private boolean enabled = true;
    private long defaultProjectId = 1L;
    private String flinkBin = "flink";
    private String hadoopBin = "hadoop";
    private String hadoopClasspath = "";
    private String yarnBin = "yarn";
    private String yarnWebUrl = "";
    private String submitJar = "../realtime-task-submit/target/realtime-task-submit.jar";
    private String submissionDir = "/tmp/sql-agent-realtime/submissions";
    private String submissionUriPrefix = "";
    private String paimonActionJarPath = "";
    private String paimonWarehouse = "";
    private String paimonDebugWarehouse = "";
    private String paimonDebugTargetDatabase = "paimon_debug";
    private String targetDatabase = "";
    private String checkpointDir = "";
    private String savepointDir = "";
    private long syncDelayMs = 10000L;
    private boolean stateSyncEnabled = true;
    private int debugSuccessMinRunningMinutes = 2;
    private Map<String, String> catalogConf = new LinkedHashMap<>();
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
    public String getPaimonDebugTargetDatabase() { return paimonDebugTargetDatabase; }
    public void setPaimonDebugTargetDatabase(String value) { paimonDebugTargetDatabase = value; }
    public String getTargetDatabase() { return targetDatabase; }
    public void setTargetDatabase(String value) { targetDatabase = value; }
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
}
