package com.yjn.sqlagent.datamap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 数据地图调度与 Neo4j 连接配置。默认关闭图连接，避免未部署 Neo4j 时影响主业务启动。 */
@ConfigurationProperties(prefix = "app.data-map")
public class DataMapProperties {
    private boolean enabled = true;
    private int projectionBatchSize = 50;
    private int maxAttempts = 5;
    private int processingLeaseSeconds = 600;
    private final Neo4j neo4j = new Neo4j();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getProjectionBatchSize() { return projectionBatchSize; }
    public void setProjectionBatchSize(int projectionBatchSize) { this.projectionBatchSize = projectionBatchSize; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public int getProcessingLeaseSeconds() { return processingLeaseSeconds; }
    public void setProcessingLeaseSeconds(int processingLeaseSeconds) {
        this.processingLeaseSeconds = Math.max(30, processingLeaseSeconds);
    }
    public Neo4j getNeo4j() { return neo4j; }

    public static class Neo4j {
        private boolean enabled;
        private String baseUrl = "http://127.0.0.1:7474";
        private String database = "neo4j";
        private String username = "neo4j";
        private String password = "";
        private int connectTimeoutMs = 2000;
        private int readTimeoutMs = 10000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getDatabase() { return database; }
        public void setDatabase(String database) { this.database = database; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
        public int getReadTimeoutMs() { return readTimeoutMs; }
        public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    }
}
