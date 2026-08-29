package com.yjn.sqlagent.config;

import org.apache.ibatis.plugin.Interceptor;
import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MybatisPlusConfig {

    @Bean
    public ConfigurationCustomizer configurationCustomizer(SqlLogInterceptor sqlLogInterceptor) {
        return configuration -> configuration.addInterceptor((Interceptor) sqlLogInterceptor);
    }
}
