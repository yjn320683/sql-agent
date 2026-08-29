package com.yjn.sqlagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "sql-agent.auth")
public class AuthProperties {

    private boolean localLoginEnabled;

    public boolean isLocalLoginEnabled() {
        return localLoginEnabled;
    }

    public void setLocalLoginEnabled(boolean localLoginEnabled) {
        this.localLoginEnabled = localLoginEnabled;
    }
}
