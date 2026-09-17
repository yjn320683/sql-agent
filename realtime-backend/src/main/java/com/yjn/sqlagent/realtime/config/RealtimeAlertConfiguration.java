package com.yjn.sqlagent.realtime.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RealtimeAlertConfiguration {
    @Bean(name = "realtimeAlertExecutor", destroyMethod = "shutdown")
    public ExecutorService realtimeAlertExecutor() {
        return Executors.newFixedThreadPool(6, runnable -> {
            Thread thread = new Thread(runnable, "realtime-alert-evaluator");
            thread.setDaemon(true);
            return thread;
        });
    }
}
