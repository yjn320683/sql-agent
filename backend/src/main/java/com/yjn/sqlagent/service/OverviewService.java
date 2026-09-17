package com.yjn.sqlagent.service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 聚合离线与实时状态；任何单一区域失败都不影响其他区域返回。 */
@Service
public class OverviewService {
    private final JdbcTemplate jdbc;

    public OverviewService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> overview(String actor) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedAt", LocalDateTime.now());
        Map<String, Object> sections = new LinkedHashMap<>();
        Map<String, Object> summary = new LinkedHashMap<>();

        section(sections, "offline", () -> {
            summary.putAll(offlineSummary());
            return Map.of("available", true);
        });
        section(sections, "realtime", () -> {
            summary.putAll(realtimeSummary());
            return Map.of("available", true);
        });
        result.put("summary", summary);
        result.put("sections", sections);
        result.put("attention", sectionValue(sections, "attention", this::attention, List.of()));
        result.put("recentTasks", sectionValue(sections, "recentTasks", () -> recentTasks(actor), List.of()));
        result.put("frequentTasks", sectionValue(sections, "frequentTasks", this::frequentTasks, List.of()));
        result.put("realtimeTableIssues", sectionValue(sections, "realtimeTableIssues", this::tableIssues, List.of()));
        return result;
    }

    private Map<String, Object> offlineSummary() {
        Map<String, Object> row = jdbc.queryForMap("SELECT "
                + "SUM(status IN ('PENDING','QUEUED','RUNNING','CANCELLING')) offlineActive," 
                + "SUM(status='FAILED' AND submitted_at>=DATE_SUB(NOW(),INTERVAL 24 HOUR)) offlineFailed24h "
                + "FROM sql_task_execution");
        row.put("offlineFailedSchedules24h", scalar("SELECT COUNT(*) FROM sql_task_schedule_run "
                + "WHERE status='FAILED' AND update_time>=DATE_SUB(NOW(),INTERVAL 24 HOUR)"));
        return row;
    }

    private Map<String, Object> realtimeSummary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("realtimeActive", scalar("SELECT COUNT(*) FROM rt_task_instance WHERE execution_mode='PRODUCTION' "
                + "AND status IN ('submitting','running','stopping','restarting')"));
        result.put("realtimeFailed24h", scalar("SELECT COUNT(*) FROM rt_task_instance WHERE execution_mode='PRODUCTION' "
                + "AND status='failed' AND update_time>=DATE_SUB(NOW(),INTERVAL 24 HOUR)"));
        result.put("openAlerts", scalar("SELECT COUNT(*) FROM rt_alert WHERE LOWER(status) IN ('open','acknowledged','muted')"));
        result.put("realtimeTableIssues", scalar("SELECT COUNT(*) FROM rt_realtime_table WHERE physical_status<>'active'"));
        return result;
    }

    private List<Map<String, Object>> attention() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.addAll(jdbc.queryForList("SELECT CONCAT('offline-execution-',e.id) itemKey,'OFFLINE_EXECUTION' objectType,"
                + "e.id objectId,e.task_name_snapshot title,COALESCE(e.error_message,'离线实例执行失败') subtitle,"
                + "'FAILED' status,e.update_time occurredAt,CONCAT('/tasks/',e.task_id,'/executions/',e.id) route "
                + "FROM sql_task_execution e WHERE e.status='FAILED' ORDER BY e.update_time DESC LIMIT 8"));
        rows.addAll(jdbc.queryForList("SELECT CONCAT('realtime-instance-',i.id) itemKey,'REALTIME_INSTANCE' objectType,"
                + "i.id objectId,t.task_name title,COALESCE(i.failure_message,'实时实例运行失败') subtitle,"
                + "i.status,i.update_time occurredAt,CONCAT(CASE t.task_type WHEN 'sync' THEN '/realtime/sync-tasks' "
                + "WHEN 'compute' THEN '/realtime/compute' ELSE '/realtime/export' END,'?taskId=',t.id,"
                + "'&tab=instances&instanceId=',i.id) route FROM rt_task_instance i JOIN rt_task t ON t.id=i.task_id "
                + "WHERE i.execution_mode='PRODUCTION' AND i.status='failed' ORDER BY i.update_time DESC LIMIT 8"));
        rows.addAll(jdbc.queryForList("SELECT CONCAT('alert-',a.id) itemKey,'REALTIME_ALERT' objectType,a.id objectId,"
                + "a.title,CONCAT(t.task_name,' · ',COALESCE(a.detail,'')) subtitle,a.status,a.update_time occurredAt,"
                + "CONCAT('/realtime/alerts?alertId=',a.id) route FROM rt_alert a JOIN rt_task t ON t.id=a.task_id "
                + "WHERE LOWER(a.status) IN ('open','acknowledged','muted') ORDER BY a.update_time DESC LIMIT 8"));
        rows.addAll(jdbc.queryForList("SELECT CONCAT('schedule-',r.id) itemKey,'OFFLINE_SCHEDULE' objectType,r.id objectId,"
                + "t.name title,COALESCE(r.message,'离线调度失败') subtitle,r.status,r.update_time occurredAt,"
                + "CONCAT('/tasks/',r.task_id,'/executions') route FROM sql_task_schedule_run r "
                + "JOIN sql_task t ON t.id=r.task_id WHERE r.status='FAILED' ORDER BY r.update_time DESC LIMIT 8"));
        rows.sort(Comparator.comparing(this::timeOf, Comparator.nullsLast(Comparator.reverseOrder())));
        return rows.subList(0, Math.min(20, rows.size()));
    }

    private List<Map<String, Object>> recentTasks(String actor) {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.addAll(jdbc.queryForList("SELECT CONCAT('offline-task-',id) itemKey,'OFFLINE_TASK' objectType,id objectId,"
                + "name title,CONCAT('负责人：',owner) subtitle,IF(archived=1,'ARCHIVED',IF(enabled=1,'ENABLED','DISABLED')) status,"
                + "update_time occurredAt,CONCAT('/tasks/',id,'/edit') route FROM sql_task "
                + "WHERE archived=0 AND (updated_by=? OR created_by=?) ORDER BY update_time DESC LIMIT 8", actor, actor));
        rows.addAll(jdbc.queryForList("SELECT CONCAT('realtime-task-',t.id) itemKey,'REALTIME_TASK' objectType,t.id objectId,"
                + "t.task_name title,CONCAT(CASE t.task_type WHEN 'sync' THEN '同步' WHEN 'compute' THEN '计算' ELSE '出仓' END,"
                + "' · 负责人：',t.owner) subtitle,t.status,t.update_time occurredAt,CONCAT(CASE t.task_type WHEN 'sync' "
                + "THEN '/realtime/sync-tasks' WHEN 'compute' THEN '/realtime/compute' ELSE '/realtime/export' END,"
                + "'?taskId=',t.id) route FROM rt_task t WHERE t.status<>'deleted' AND (t.owner=? OR EXISTS "
                + "(SELECT 1 FROM rt_task_change_log l WHERE l.task_id=t.id AND l.operator=?)) "
                + "ORDER BY t.update_time DESC LIMIT 8", actor, actor));
        rows.sort(Comparator.comparing(this::timeOf, Comparator.nullsLast(Comparator.reverseOrder())));
        return rows.subList(0, Math.min(10, rows.size()));
    }

    private List<Map<String, Object>> frequentTasks() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.addAll(jdbc.queryForList("SELECT CONCAT('offline-task-',t.id) itemKey,'OFFLINE_TASK' objectType,t.id objectId,"
                + "t.name title,CONCAT(COUNT(e.id),' 次执行') subtitle,IF(t.enabled=1,'ENABLED','DISABLED') status,"
                + "MAX(e.submitted_at) occurredAt,CONCAT('/tasks/',t.id,'/edit') route,COUNT(e.id) frequency "
                + "FROM sql_task t JOIN sql_task_execution e ON e.task_id=t.id AND e.submitted_at>=DATE_SUB(NOW(),INTERVAL 30 DAY) "
                + "WHERE t.archived=0 GROUP BY t.id,t.name,t.enabled ORDER BY frequency DESC,occurredAt DESC LIMIT 8"));
        rows.addAll(jdbc.queryForList("SELECT CONCAT('realtime-task-',t.id) itemKey,'REALTIME_TASK' objectType,t.id objectId,"
                + "t.task_name title,CONCAT(COUNT(i.id),' 次运行') subtitle,t.status,MAX(i.create_time) occurredAt,"
                + "CONCAT(CASE t.task_type WHEN 'sync' THEN '/realtime/sync-tasks' WHEN 'compute' THEN '/realtime/compute' "
                + "ELSE '/realtime/export' END,'?taskId=',t.id) route,COUNT(i.id) frequency FROM rt_task t "
                + "JOIN rt_task_instance i ON i.task_id=t.id AND i.execution_mode='PRODUCTION' "
                + "AND i.create_time>=DATE_SUB(NOW(),INTERVAL 30 DAY) WHERE t.status<>'deleted' "
                + "GROUP BY t.id,t.task_name,t.task_type,t.status ORDER BY frequency DESC,occurredAt DESC LIMIT 8"));
        rows.sort(Comparator.comparingLong(this::frequencyOf).reversed()
                .thenComparing(this::timeOf, Comparator.nullsLast(Comparator.reverseOrder())));
        return rows.subList(0, Math.min(10, rows.size()));
    }

    private List<Map<String, Object>> tableIssues() {
        return jdbc.queryForList("SELECT id objectId,CONCAT(database_name,'.',table_name) title,"
                + "COALESCE(last_error,IF(physical_status='declared','物理表尚未创建','物理状态异常')) subtitle,"
                + "physical_status status,update_time occurredAt,CONCAT('/realtime/paimon-tables?tableId=',id) route "
                + "FROM rt_realtime_table WHERE physical_status<>'active' ORDER BY update_time DESC LIMIT 10");
    }

    private long scalar(String sql) {
        Number value = jdbc.queryForObject(sql, Number.class);
        return value == null ? 0L : value.longValue();
    }

    private void section(Map<String, Object> sections, String name, Supplier<?> supplier) {
        try {
            supplier.get();
            sections.put(name, Map.of("available", true));
        } catch (RuntimeException ex) {
            sections.put(name, Map.of("available", false, "error", safe(ex)));
        }
    }

    private Object sectionValue(Map<String, Object> sections, String name, Supplier<?> supplier, Object fallback) {
        try {
            Object value = supplier.get();
            sections.put(name, Map.of("available", true));
            return value;
        } catch (RuntimeException ex) {
            sections.put(name, Map.of("available", false, "error", safe(ex)));
            return fallback;
        }
    }

    private LocalDateTime timeOf(Map<String, Object> row) {
        Object value = row.get("occurredAt");
        if (value instanceof Timestamp) return ((Timestamp) value).toLocalDateTime();
        if (value instanceof LocalDateTime) return (LocalDateTime) value;
        return null;
    }

    private long frequencyOf(Map<String, Object> row) {
        Object value = row.get("frequency");
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private String safe(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.trim().isEmpty() ? ex.getClass().getSimpleName() : message;
    }
}
