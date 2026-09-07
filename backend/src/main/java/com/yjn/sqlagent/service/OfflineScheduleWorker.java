package com.yjn.sqlagent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OfflineScheduleWorker {
    private static final Logger log = LoggerFactory.getLogger(OfflineScheduleWorker.class);
    private final OfflineSchedulingService schedulingService;

    public OfflineScheduleWorker(OfflineSchedulingService schedulingService) {
        this.schedulingService = schedulingService;
    }

    @Scheduled(fixedDelayString = "${sql-agent.scheduler.poll-interval-ms:10000}")
    public void triggerDueSchedules() {
        try { schedulingService.processDueSchedules(); }
        catch (RuntimeException ex) { log.error("离线调度轮询失败", ex); }
    }

    @Scheduled(fixedDelayString = "${sql-agent.scheduler.reconcile-interval-ms:15000}")
    public void reconcileRuns() {
        try { schedulingService.reconcileAndRetry(); }
        catch (RuntimeException ex) { log.error("离线调度实例状态同步失败", ex); }
    }
}
