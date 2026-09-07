package com.yjn.sqlagent.realtime.config;

import com.yjn.sqlagent.parsesql.SqlLineageParser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SqlLineageConfig {
    @Bean
    @ConditionalOnMissingBean(SqlLineageParser.class)
    public SqlLineageParser sqlLineageParser() {
        return new SqlLineageParser();
    }
}
