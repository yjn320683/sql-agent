package com.yjn.sqlagent.realtime.performance;

import com.yjn.sqlagent.realtime.common.RealtimeActorProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/** 仅供隔离性能复测使用：不启用 @EnableScheduling，也不加载离线任务调度器。 */
@SpringBootApplication(scanBasePackages = "com.yjn.sqlagent.realtime")
public class RealtimePerformanceHarnessApplication {

    public static void main(String[] args) {
        SpringApplication.run(RealtimePerformanceHarnessApplication.class, args);
    }

    @Bean
    RealtimeActorProvider performanceActorProvider() {
        return () -> "performance-test";
    }
}
