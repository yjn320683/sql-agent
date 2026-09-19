package com.yjn.sqlagent.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 全局搜索中的远端 Hive 检索使用独立小线程池，避免占用请求线程。 */
@Configuration
public class GlobalSearchConfiguration {
    @Bean(name = "globalSearchExecutor", destroyMethod = "shutdown")
    public ExecutorService globalSearchExecutor() {
        return Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "global-search-hive");
            thread.setDaemon(true);
            return thread;
        });
    }
}
