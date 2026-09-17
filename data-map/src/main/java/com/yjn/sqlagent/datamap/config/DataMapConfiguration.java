package com.yjn.sqlagent.datamap.config;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
@EnableConfigurationProperties(DataMapProperties.class)
public class DataMapConfiguration {
    @Bean("dataMapRestTemplate")
    RestTemplate dataMapRestTemplate(RestTemplateBuilder builder, DataMapProperties properties) {
        return builder
                .setConnectTimeout(Duration.ofMillis(properties.getNeo4j().getConnectTimeoutMs()))
                .setReadTimeout(Duration.ofMillis(properties.getNeo4j().getReadTimeoutMs()))
                .build();
    }
}
