package com.yjn.sqlagent.config;

import javax.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "sql-agent.agent")
public class AgentProperties {

    /** agent SSE 服务地址，例如 http://localhost:8284 */
    @NotBlank
    private String baseUrl;

    /** Claude 会话 JSONL 所在目录，由不同环境分别配置 */
    @NotBlank
    private String historyDir;

    /** backend 调用 agent 时使用的共享服务令牌 */
    @NotBlank
    private String serviceToken;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getHistoryDir() {
        return historyDir;
    }

    public void setHistoryDir(String historyDir) {
        this.historyDir = historyDir;
    }

    public String getServiceToken() {
        return serviceToken;
    }

    public void setServiceToken(String serviceToken) {
        this.serviceToken = serviceToken;
    }
}
