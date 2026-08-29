package com.yjn.sqlagent.datacompare.config;

import com.yjn.sqlagent.parsesql.HiveSqlParser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SqlParserConfig {
    @Bean
    public HiveSqlParser hiveSqlParser() {
        return new HiveSqlParser();
    }
}
