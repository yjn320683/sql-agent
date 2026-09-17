package com.yjn.sqlagent.realtime.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjn.sqlagent.realtime.config.RealtimeProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 告警事件生命周期、内置规则配置以及运行实例的周期采样。 */
@Service
public class RealtimeAlertService {
    private static final Set<String> ACTIVE_STATUSES = Set.of("OPEN", "ACKNOWLEDGED", "MUTED");
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final ObjectMapper mapper;
    private final RealtimeRuntimeService runtime;
    private final RealtimeProperties properties;
    private final ExecutorService executor;

    public RealtimeAlertService(JdbcTemplate jdbc, ObjectMapper mapper, RealtimeRuntimeService runtime,
            RealtimeProperties properties, @Qualifier("realtimeAlertExecutor") ExecutorService executor) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.mapper = mapper;
        this.runtime = runtime;
        this.properties = properties;
        this.executor = executor;
    }

    public Map<String, Object> page(Map<String, String> query) {
        int page = positive(query.get("page"), 1);
        int pageSize = Math.min(100, positive(query.get("pageSize"), 20));
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        String view = text(query.get("view")).toUpperCase(Locale.ROOT);
        if ("RECOVERED".equals(view)) where.append(" AND a.status='RECOVERED'");
        else if (!view.isEmpty() && !"ACTIVE".equals(view)) {
            where.append(" AND a.status=:status"); params.addValue("status", view);
        } else where.append(" AND a.status IN ('OPEN','ACKNOWLEDGED','MUTED')");
        String keyword = text(query.get("keyword"));
        if (!keyword.isEmpty()) {
            where.append(" AND (CAST(a.id AS CHAR) LIKE :keyword OR t.task_name LIKE :keyword OR a.title LIKE :keyword OR a.detail LIKE :keyword)");
            params.addValue("keyword", "%" + keyword + "%");
        }
        if (!text(query.get("severity")).isEmpty()) {
            where.append(" AND a.severity=:severity"); params.addValue("severity", query.get("severity"));
        }
        if (!text(query.get("ruleCode")).isEmpty()) {
            where.append(" AND r.rule_code=:ruleCode"); params.addValue("ruleCode", query.get("ruleCode"));
        }
        if (!text(query.get("taskId")).isEmpty()) {
            where.append(" AND a.task_id=:taskId"); params.addValue("taskId", Long.parseLong(query.get("taskId")));
        }
        Long total = named.queryForObject("SELECT COUNT(*) FROM rt_alert a JOIN rt_task t ON t.id=a.task_id "
                + "LEFT JOIN rt_alert_rule r ON r.id=a.rule_id" + where, params, Long.class);
        params.addValue("limit", pageSize).addValue("offset", (page - 1) * pageSize);
        List<Map<String, Object>> records = named.queryForList(selectSql() + where
                + " ORDER BY a.last_occurred_at DESC,a.id DESC LIMIT :limit OFFSET :offset", params);
        records.forEach(this::normalizeAlert);
        return Map.of("records", records, "total", total == null ? 0L : total, "page", page, "pageSize", pageSize);
    }

    public Map<String, Object> detail(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(selectSql() + " WHERE a.id=?", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("告警不存在");
        Map<String, Object> result = new LinkedHashMap<>(rows.get(0));
        normalizeAlert(result);
        return result;
    }

    public List<Map<String, Object>> rules() {
        return jdbc.queryForList("SELECT id,rule_code ruleCode,rule_name ruleName,event_type eventType,severity,"
                + "enabled_flag enabled,threshold_value thresholdValue,consecutive_samples consecutiveSamples,"
                + "window_seconds windowSeconds,description,update_time updateTime FROM rt_alert_rule ORDER BY id");
    }

    public Map<String, Object> updateRule(long id, Map<String, Object> input) {
        String severity = text(input.get("severity")).toLowerCase(Locale.ROOT);
        if (!Set.of("info", "warning", "critical").contains(severity)) throw new IllegalArgumentException("告警级别不正确");
        long threshold = positiveLong(input.get("thresholdValue"), 1);
        int samples = Math.min(20, positiveObject(input.get("consecutiveSamples"), 1));
        int window = Math.min(86400, positiveObject(input.get("windowSeconds"), 300));
        boolean enabled = Boolean.TRUE.equals(input.get("enabled")) || "1".equals(text(input.get("enabled")));
        if (jdbc.update("UPDATE rt_alert_rule SET severity=?,enabled_flag=?,threshold_value=?,consecutive_samples=?,"
                + "window_seconds=?,update_time=NOW() WHERE id=?", severity, enabled ? 1 : 0, threshold, samples, window, id) != 1) {
            throw new IllegalArgumentException("告警规则不存在");
        }
        if (!enabled) {
            jdbc.update("UPDATE rt_alert SET status='RECOVERED',recovered_at=NOW(),active_fingerprint=NULL,update_time=NOW() "
                    + "WHERE rule_id=? AND status IN ('OPEN','ACKNOWLEDGED','MUTED')", id);
            jdbc.update("UPDATE rt_alert_rule_state SET consecutive_count=0,last_condition_met=0,update_time=NOW() WHERE rule_id=?", id);
        }
        return rules().stream().filter(rule -> number(rule.get("id")) == id).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("告警规则不存在"));
    }

    public void acknowledge(long id, String actor) {
        if (jdbc.update("UPDATE rt_alert SET status='ACKNOWLEDGED',acknowledged_by=?,acknowledged_at=NOW(),"
                + "update_time=NOW() WHERE id=? AND status='OPEN'", actor, id) != 1) {
            throw new IllegalStateException("告警不存在或已处理");
        }
    }

    public void mute(long id, LocalDateTime mutedUntil, String actor) {
        if (mutedUntil == null || !mutedUntil.isAfter(LocalDateTime.now())) throw new IllegalArgumentException("静默截止时间必须晚于当前时间");
        if (jdbc.update("UPDATE rt_alert SET status='MUTED',muted_until=?,acknowledged_by=?,"
                + "acknowledged_at=COALESCE(acknowledged_at,NOW()),update_time=NOW() WHERE id=? AND status IN ('OPEN','ACKNOWLEDGED','MUTED')",
                mutedUntil, actor, id) != 1) throw new IllegalStateException("告警不存在或已恢复");
    }

    public void unmute(long id) {
        if (jdbc.update("UPDATE rt_alert SET status='OPEN',muted_until=NULL,update_time=NOW() WHERE id=? AND status='MUTED'", id) != 1) {
            throw new IllegalStateException("告警不存在或未处于静默状态");
        }
    }

    @Scheduled(fixedDelayString = "${app.realtime.alert-evaluation-delay-ms:60000}",
            initialDelayString = "${app.realtime.alert-evaluation-initial-delay-ms:60000}")
    public void evaluateRules() {
        if (!properties.isEnabled()) return;
        jdbc.update("UPDATE rt_alert SET status='OPEN',muted_until=NULL,update_time=NOW() WHERE status='MUTED' AND muted_until<=NOW()");
        Map<String, Map<String, Object>> rules = enabledRules();
        List<Map<String, Object>> instances = jdbc.queryForList("SELECT i.id instanceId,i.task_id taskId FROM rt_task_instance i "
                + "WHERE i.managed_flag=1 AND i.execution_mode='PRODUCTION' AND i.status IN ('running','restarting') ORDER BY i.id");
        List<CompletableFuture<Void>> jobs = instances.stream()
                .map(instance -> CompletableFuture.runAsync(() -> evaluateInstance(instance, rules), executor)
                        .exceptionally(error -> null))
                .collect(Collectors.toList());
        CompletableFuture.allOf(jobs.toArray(new CompletableFuture<?>[0])).join();
        taskIdsRequiringSignalEvaluation(instances).forEach(taskId -> evaluateTaskSignals(taskId, rules));
        evaluateFailedInstances(rules.get("TASK_FAILURE"));
        recoverInactiveInstanceAlerts();
    }

    private void evaluateInstance(Map<String, Object> instance, Map<String, Map<String, Object>> rules) {
        long taskId = number(instance.get("taskId"));
        long instanceId = number(instance.get("instanceId"));
        Map<String, Object> runtimeData;
        try { runtimeData = map(runtime.runtime(taskId, instanceId)); }
        catch (RuntimeException ex) { return; }
        Map<String, Object> sync = map(runtimeData.get("sync"));
        sample(rules.get("FREQUENT_RESTART"), taskId, instanceId, decimal(runtimeData.get("restartCount")),
                "Flink 重启次数", runtimeData);
        sample(rules.get("BACKPRESSURE"), taskId, instanceId, decimal(sync.get("backpressuredMaxMsPerSecond")),
                "最大反压毫秒/秒", sync);
        sample(rules.get("SOURCE_LAG"), taskId, instanceId, decimal(sync.get("sourceLagMs")), "源端延迟毫秒", sync);

        Map<String, Object> checkpoint;
        try { checkpoint = map(runtime.checkpoints(taskId, instanceId)); }
        catch (RuntimeException ex) { checkpoint = Collections.emptyMap(); }
        Map<String, Object> latest = map(checkpoint.get("latest"));
        long failedAt = number(map(latest.get("failed")).get("trigger_timestamp"));
        long completedAt = number(map(latest.get("completed")).get("trigger_timestamp"));
        sampleCondition(rules.get("CHECKPOINT_FAILURE"), taskId, instanceId, failedAt > completedAt && failedAt > 0,
                failedAt, "最新 Checkpoint 失败", checkpoint);

        recover("TASK_FAILURE", taskId, null);
    }

    private void evaluateTaskSignals(long taskId, Map<String, Map<String, Object>> rules) {
        Long dirty = jdbc.queryForObject("SELECT COUNT(*) FROM rt_sync_dirty_record WHERE task_id=? AND resolved_flag=0", Long.class, taskId);
        sample(rules.get("DIRTY_DATA"), taskId, 0L, dirty == null ? 0D : dirty.doubleValue(), "未处理脏数据", Map.of("count", dirty == null ? 0 : dirty));
        Long schema = jdbc.queryForObject("SELECT COUNT(*) FROM rt_schema_change_event WHERE task_id=? AND status IN ('PENDING','BLOCKED')", Long.class, taskId);
        sample(rules.get("SCHEMA_CHANGE"), taskId, 0L, schema == null ? 0D : schema.doubleValue(), "待处理 Schema 变化", Map.of("count", schema == null ? 0 : schema));
    }

    private void evaluateFailedInstances(Map<String, Object> rule) {
        if (rule == null) return;
        for (Map<String, Object> row : jdbc.queryForList("SELECT i.task_id taskId,i.id instanceId,i.status,"
                + "i.failure_message failureMessage FROM rt_task_instance i "
                + "WHERE i.execution_mode='PRODUCTION' AND i.id=(SELECT MAX(latest.id) FROM rt_task_instance latest "
                + "WHERE latest.task_id=i.task_id AND latest.execution_mode='PRODUCTION')")) {
            long taskId = number(row.get("taskId")); long instanceId = number(row.get("instanceId"));
            if ("failed".equalsIgnoreCase(text(row.get("status")))) {
                raise(rule, taskId, instanceId, "实时任务运行失败", text(row.get("failureMessage")), row);
            } else {
                recover("TASK_FAILURE", taskId, null);
            }
        }
    }

    /**
     * 任务级信号不能只依赖活跃实例：任务停止后，脏数据或 Schema 事件仍可能被处理并需要恢复告警。
     */
    private Set<Long> taskIdsRequiringSignalEvaluation(List<Map<String, Object>> activeInstances) {
        Set<Long> result = activeInstances.stream().map(instance -> number(instance.get("taskId")))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (Map<String, Object> row : jdbc.queryForList("SELECT DISTINCT task_id taskId FROM rt_sync_dirty_record WHERE resolved_flag=0")) {
            result.add(number(row.get("taskId")));
        }
        for (Map<String, Object> row : jdbc.queryForList("SELECT DISTINCT task_id taskId FROM rt_schema_change_event WHERE status IN ('PENDING','BLOCKED')")) {
            result.add(number(row.get("taskId")));
        }
        for (Map<String, Object> row : jdbc.queryForList("SELECT DISTINCT a.task_id taskId FROM rt_alert a "
                + "JOIN rt_alert_rule r ON r.id=a.rule_id WHERE r.rule_code IN ('DIRTY_DATA','SCHEMA_CHANGE') "
                + "AND a.status IN ('OPEN','ACKNOWLEDGED','MUTED')")) {
            result.add(number(row.get("taskId")));
        }
        result.remove(0L);
        return result;
    }

    /** 活跃实例集合之外不再存在运行态条件，关闭其遗留运行告警并清空连续采样状态。 */
    private void recoverInactiveInstanceAlerts() {
        String instanceRules = "('FREQUENT_RESTART','CHECKPOINT_FAILURE','BACKPRESSURE','SOURCE_LAG')";
        jdbc.update("UPDATE rt_alert a SET a.status='RECOVERED',a.recovered_at=NOW(),a.active_fingerprint=NULL,a.update_time=NOW() "
                + "WHERE a.status IN ('OPEN','ACKNOWLEDGED','MUTED') AND a.task_instance_id IS NOT NULL "
                + "AND a.rule_id IN (SELECT r.id FROM rt_alert_rule r WHERE r.rule_code IN " + instanceRules + ") "
                + "AND NOT EXISTS (SELECT 1 FROM rt_task_instance i WHERE i.id=a.task_instance_id AND i.managed_flag=1 "
                + "AND i.execution_mode='PRODUCTION' AND i.status IN ('running','restarting'))");
        jdbc.update("UPDATE rt_alert_rule_state s SET s.consecutive_count=0,s.last_condition_met=0,s.update_time=NOW() "
                + "WHERE s.task_instance_id<>0 AND s.rule_id IN (SELECT r.id FROM rt_alert_rule r WHERE r.rule_code IN "
                + instanceRules + ") AND NOT EXISTS (SELECT 1 FROM rt_task_instance i WHERE i.id=s.task_instance_id "
                + "AND i.managed_flag=1 AND i.execution_mode='PRODUCTION' AND i.status IN ('running','restarting'))");
    }

    private void sample(Map<String, Object> rule, long taskId, long instanceId, Double value, String label,
            Map<String, Object> evidence) {
        if (rule == null || value == null) return;
        double threshold = decimal(rule.get("thresholdValue")) == null ? 1D : decimal(rule.get("thresholdValue"));
        sampleCondition(rule, taskId, instanceId, value >= threshold, value, label, evidence);
    }

    private void sampleCondition(Map<String, Object> rule, long taskId, long instanceId, boolean condition,
            Object value, String label, Map<String, Object> evidence) {
        if (rule == null) return;
        long ruleId = number(rule.get("id"));
        jdbc.update("INSERT INTO rt_alert_rule_state(rule_id,task_id,task_instance_id,consecutive_count,last_condition_met,"
                + "last_value,evidence_json,last_evaluated_at) VALUES(?,?,?,?,?,?,?,NOW()) ON DUPLICATE KEY UPDATE "
                + "consecutive_count=IF(VALUES(last_condition_met)=1,consecutive_count+1,0),last_condition_met=VALUES(last_condition_met),"
                + "last_value=VALUES(last_value),evidence_json=VALUES(evidence_json),last_evaluated_at=NOW(),update_time=NOW()",
                ruleId, taskId, instanceId, condition ? 1 : 0, condition ? 1 : 0, text(value), json(evidence));
        Integer count = jdbc.queryForObject("SELECT consecutive_count FROM rt_alert_rule_state WHERE rule_id=? AND task_id=? AND task_instance_id=?",
                Integer.class, ruleId, taskId, instanceId);
        if (condition && count != null && count >= positiveObject(rule.get("consecutiveSamples"), 1)) {
            raise(rule, taskId, instanceId, text(rule.get("ruleName")), label + "：" + text(value), evidence);
        } else if (!condition) recover(text(rule.get("ruleCode")), taskId, instanceId);
    }

    private void raise(Map<String, Object> rule, long taskId, long instanceId, String title, String detail,
            Map<String, Object> evidence) {
        String code = text(rule.get("ruleCode"));
        // 任务失败按任务聚合，避免运行时事件与规则轮询产生两条活动告警。
        long fingerprintInstanceId = "TASK_FAILURE".equals(code) ? 0L : instanceId;
        String fingerprint = sha256(taskId + "|" + fingerprintInstanceId + "|" + code);
        jdbc.update("INSERT INTO rt_alert(task_id,task_instance_id,rule_id,event_type,severity,status,title,detail,"
                + "fingerprint,active_fingerprint,occurrence_count,first_occurred_at,last_occurred_at,evidence_json) "
                + "VALUES(?,?,?,?,?,'OPEN',?,?,?, ?,1,NOW(),NOW(),?) ON DUPLICATE KEY UPDATE "
                + "task_instance_id=COALESCE(VALUES(task_instance_id),task_instance_id),"
                + "rule_id=COALESCE(VALUES(rule_id),rule_id),severity=VALUES(severity),title=VALUES(title),"
                + "detail=VALUES(detail),occurrence_count=occurrence_count+1,"
                + "last_occurred_at=NOW(),evidence_json=VALUES(evidence_json),update_time=NOW()",
                taskId, instanceId == 0 ? null : instanceId, number(rule.get("id")), text(rule.get("eventType")),
                text(rule.get("severity")), title, detail, fingerprint, fingerprint, json(evidence));
    }

    private void recover(String ruleCode, long taskId, Long instanceId) {
        String instanceClause = instanceId == null ? "" : " AND COALESCE(a.task_instance_id,0)=?";
        List<Object> args = new ArrayList<>(); args.add(taskId); args.add(ruleCode);
        if (instanceId != null) args.add(instanceId);
        jdbc.update("UPDATE rt_alert a JOIN rt_alert_rule r ON r.id=a.rule_id SET a.status='RECOVERED',a.recovered_at=NOW(),"
                + "a.active_fingerprint=NULL,a.update_time=NOW() WHERE a.task_id=? AND r.rule_code=? "
                + "AND a.status IN ('OPEN','ACKNOWLEDGED','MUTED')" + instanceClause, args.toArray());
    }

    private Map<String, Map<String, Object>> enabledRules() {
        Map<String, Map<String, Object>> result = new HashMap<>();
        for (Map<String, Object> rule : rules()) {
            if (Boolean.TRUE.equals(rule.get("enabled")) || number(rule.get("enabled")) == 1) {
                result.put(text(rule.get("ruleCode")), rule);
            }
        }
        return result;
    }

    private String selectSql() {
        return "SELECT a.id,a.task_id taskId,t.task_name taskName,t.task_type taskType,a.task_instance_id taskInstanceId,"
                + "a.rule_id ruleId,r.rule_code ruleCode,r.rule_name ruleName,a.event_type eventType,a.severity,a.status,"
                + "a.title,a.detail,a.occurrence_count occurrenceCount,a.first_occurred_at firstOccurredAt,"
                + "a.last_occurred_at lastOccurredAt,a.acknowledged_by acknowledgedBy,a.acknowledged_at acknowledgedAt,"
                + "a.muted_until mutedUntil,a.recovered_at recoveredAt,a.evidence_json evidenceJson,"
                + "a.create_time createTime,a.update_time updateTime FROM rt_alert a JOIN rt_task t ON t.id=a.task_id "
                + "LEFT JOIN rt_alert_rule r ON r.id=a.rule_id";
    }

    private void normalizeAlert(Map<String, Object> row) {
        row.put("evidence", jsonMap(row.remove("evidenceJson")));
        row.put("active", ACTIVE_STATUSES.contains(text(row.get("status")).toUpperCase(Locale.ROOT)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map ? new LinkedHashMap<>((Map<String, Object>) value) : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonMap(Object value) {
        if (value == null) return new LinkedHashMap<>();
        try { return mapper.readValue(String.valueOf(value), Map.class); }
        catch (Exception ignored) { return new LinkedHashMap<>(); }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception ex) { return "{}"; }
    }

    private int positive(String value, int fallback) {
        try { return Math.max(1, Integer.parseInt(value)); } catch (Exception ex) { return fallback; }
    }
    private int positiveObject(Object value, int fallback) {
        try { return Math.max(1, Integer.parseInt(String.valueOf(value))); } catch (Exception ex) { return fallback; }
    }
    private long positiveLong(Object value, long fallback) {
        try { return Math.max(1L, Long.parseLong(String.valueOf(value))); } catch (Exception ex) { return fallback; }
    }
    private long number(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        try { return value == null ? 0L : Long.parseLong(String.valueOf(value)); } catch (Exception ex) { return 0L; }
    }
    private Double decimal(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        try { return value == null ? null : Double.parseDouble(String.valueOf(value)); } catch (Exception ex) { return null; }
    }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte item : bytes) out.append(String.format("%02x", item));
            return out.toString();
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
