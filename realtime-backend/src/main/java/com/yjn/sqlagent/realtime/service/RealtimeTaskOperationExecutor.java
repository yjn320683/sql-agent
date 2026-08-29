package com.yjn.sqlagent.realtime.service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/** 同一任务的 Flink CLI 操作只允许串行执行，HTTP 请求只负责完成操作准备。 */
@Component
public class RealtimeTaskOperationExecutor {
    private static final Logger LOG = LoggerFactory.getLogger(RealtimeTaskOperationExecutor.class);

    private final Executor executor;
    private final Set<Long> runningTaskIds = ConcurrentHashMap.newKeySet();

    public RealtimeTaskOperationExecutor() {
        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setThreadNamePrefix("realtime-sync-op-");
        taskExecutor.setCorePoolSize(2);
        taskExecutor.setMaxPoolSize(4);
        taskExecutor.setQueueCapacity(64);
        taskExecutor.initialize();
        this.executor = taskExecutor;
    }

    RealtimeTaskOperationExecutor(Executor executor) {
        this.executor = executor;
    }

    public boolean submit(long taskId, String operationName, Runnable action) {
        if (!runningTaskIds.add(taskId)) return false;
        try {
            executor.execute(() -> {
                try {
                    action.run();
                } catch (RuntimeException ex) {
                    LOG.error("实时同步任务后台操作失败: taskId={}, operation={}", taskId, operationName, ex);
                } finally {
                    runningTaskIds.remove(taskId);
                }
            });
        } catch (RuntimeException ex) {
            runningTaskIds.remove(taskId);
            throw ex;
        }
        return true;
    }

    public boolean isRunning(long taskId) {
        return runningTaskIds.contains(taskId);
    }
}
