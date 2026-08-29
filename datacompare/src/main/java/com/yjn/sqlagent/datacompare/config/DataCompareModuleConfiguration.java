package com.yjn.sqlagent.datacompare.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 验数模块由宿主 Backend 扫描并装配，不提供独立应用入口。
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(DataCompareProperties.class)
public class DataCompareModuleConfiguration {
}
