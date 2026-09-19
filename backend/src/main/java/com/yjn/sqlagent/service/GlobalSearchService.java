package com.yjn.sqlagent.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 搜索本地任务、实例、会话、告警以及远端 Hive 表，并对远端故障做局部降级。 */
@Service
public class GlobalSearchService {
    private static final int HIVE_TIMEOUT_MILLIS = 1500;
    private static final List<String> ALL_TYPES = Arrays.asList(
            "OFFLINE_TASK", "REALTIME_TASK", "HIVE_TABLE", "REALTIME_TABLE",
            "OFFLINE_EXECUTION", "REALTIME_INSTANCE", "CHAT_SESSION", "REALTIME_ALERT");
    private static final Set<String> VALID_TYPES = Set.copyOf(ALL_TYPES);

    private final JdbcTemplate jdbc;
    private final AgentProxyService agentProxyService;
    private final ExecutorService executor;

    public GlobalSearchService(JdbcTemplate jdbc, AgentProxyService agentProxyService,
            @Qualifier("globalSearchExecutor") ExecutorService executor) {
        this.jdbc = jdbc;
        this.agentProxyService = agentProxyService;
        this.executor = executor;
    }

    public Map<String, Object> search(String rawKeyword, String rawType, int rawPage, int rawPageSize, String actor) {
        String keyword = rawKeyword == null ? "" : rawKeyword.trim();
        if (keyword.length() < 2) {
            throw new IllegalArgumentException("搜索关键词至少需要 2 个字符");
        }
        String type = normalizeType(rawType);
        int page = Math.max(1, rawPage);
        int pageSize = Math.max(1, Math.min(20, rawPageSize));
        if (type == null) {
            page = 1;
            pageSize = Math.min(pageSize, 5);
        }

        List<String> selectedTypes = type == null ? ALL_TYPES : Collections.singletonList(type);
        List<Map<String, Object>> groups = new ArrayList<>();
        for (String selectedType : selectedTypes) {
            groups.add(group(selectedType, keyword, page, pageSize, actor));
        }
        List<String> partialFailures = groups.stream()
                .filter(group -> group.get("error") != null)
                .map(group -> String.valueOf(group.get("type")))
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("keyword", keyword);
        result.put("generatedAt", LocalDateTime.now());
        result.put("groups", groups);
        result.put("partialFailures", partialFailures);
        return result;
    }

    private Map<String, Object> group(String type, String keyword, int page, int pageSize, String actor) {
        if ("HIVE_TABLE".equals(type)) return hiveGroup(keyword, page, pageSize);
        try {
            return localGroup(type, keyword, page, pageSize, actor);
        } catch (RuntimeException ex) {
            return failedGroup(type, page, pageSize, safe(ex));
        }
    }

    private Map<String, Object> localGroup(String type, String keyword, int page, int pageSize, String actor) {
        int offset = (page - 1) * pageSize;
        String lower = keyword.toLowerCase(Locale.ROOT);
        String id = keyword.matches("\\d+") ? keyword : "";
        QuerySpec spec = querySpec(type, actor);
        List<Object> conditionArgs = new ArrayList<>();
        conditionArgs.add(id);
        conditionArgs.add(lower);
        if (spec.actorScoped) conditionArgs.add(actor);

        Number totalValue = jdbc.queryForObject(spec.countSql, conditionArgs.toArray(), Number.class);
        List<Object> itemArgs = new ArrayList<>(conditionArgs);
        itemArgs.add(id);
        itemArgs.add(lower);
        itemArgs.add(lower);
        itemArgs.add(pageSize);
        itemArgs.add(offset);
        List<Map<String, Object>> items = jdbc.queryForList(spec.itemsSql, itemArgs.toArray());
        return successGroup(type, page, pageSize, totalValue == null ? 0L : totalValue.longValue(), items);
    }

    private QuerySpec querySpec(String type, String actor) {
        String ranking = " ORDER BY CASE WHEN CAST(search_id AS CHAR)=? OR LOWER(search_name)=? THEN 0 "
                + "WHEN LOWER(search_name) LIKE CONCAT(?,'%') THEN 1 ELSE 2 END,updatedAt DESC LIMIT ? OFFSET ?";
        String searchCondition = "(CAST(search_id AS CHAR)=? OR LOCATE(?,LOWER(search_name))>0)";
        switch (type) {
            case "OFFLINE_TASK": {
                String base = " FROM (SELECT id search_id,name search_name,name title,COALESCE(description,'') subtitle,"
                        + "IF(archived=1,'ARCHIVED',IF(enabled=1,'ENABLED','DISABLED')) status,'offline' mode,"
                        + "CONCAT('/tasks/',id,'/edit') route,update_time updatedAt FROM sql_task WHERE archived=0) s WHERE ";
                return new QuerySpec("SELECT 'OFFLINE_TASK' type,search_id id,title,subtitle,status,mode,route,updatedAt" + base
                        + searchCondition + ranking, "SELECT COUNT(*)" + base + searchCondition, false);
            }
            case "REALTIME_TASK": {
                String base = " FROM (SELECT id search_id,task_name search_name,task_name title,"
                        + "CONCAT(CASE task_type WHEN 'sync' THEN '实时同步' WHEN 'compute' THEN '实时计算' ELSE '实时出仓' END,"
                        + "' · ',COALESCE(description,'')) subtitle,status,'realtime' mode,"
                        + "CONCAT(CASE task_type WHEN 'sync' THEN '/realtime/sync-tasks' WHEN 'compute' THEN '/realtime/compute' "
                        + "ELSE '/realtime/export' END,'?taskId=',id) route,update_time updatedAt FROM rt_task "
                        + "WHERE status<>'deleted') s WHERE ";
                return new QuerySpec("SELECT 'REALTIME_TASK' type,search_id id,title,subtitle,status,mode,route,updatedAt" + base
                        + searchCondition + ranking, "SELECT COUNT(*)" + base + searchCondition, false);
            }
            case "REALTIME_TABLE": {
                String base = " FROM (SELECT id search_id,CONCAT(database_name,'.',table_name) search_name,"
                        + "CONCAT(database_name,'.',table_name) title,COALESCE(table_comment,'Paimon 实时表') subtitle,"
                        + "physical_status status,'realtime' mode,CONCAT('/realtime/paimon-tables?tableId=',id) route,"
                        + "update_time updatedAt FROM rt_realtime_table) s WHERE ";
                return new QuerySpec("SELECT 'REALTIME_TABLE' type,search_id id,title,subtitle,status,mode,route,updatedAt" + base
                        + searchCondition + ranking, "SELECT COUNT(*)" + base + searchCondition, false);
            }
            case "OFFLINE_EXECUTION": {
                String base = " FROM (SELECT id search_id,task_name_snapshot search_name,task_name_snapshot title,"
                        + "CONCAT('离线实例 #',id,' · 任务 #',task_id) subtitle,status,'offline' mode,"
                        + "CONCAT('/tasks/',task_id,'/executions/',id) route,update_time updatedAt FROM sql_task_execution) s WHERE ";
                return new QuerySpec("SELECT 'OFFLINE_EXECUTION' type,search_id id,title,subtitle,status,mode,route,updatedAt" + base
                        + searchCondition + ranking, "SELECT COUNT(*)" + base + searchCondition, false);
            }
            case "REALTIME_INSTANCE": {
                String base = " FROM (SELECT i.id search_id,t.task_name search_name,t.task_name title,"
                        + "CONCAT('实时实例 #',i.id,' · ',i.execution_mode) subtitle,i.status,'realtime' mode,"
                        + "CONCAT(CASE t.task_type WHEN 'sync' THEN '/realtime/sync-tasks' WHEN 'compute' THEN '/realtime/compute' "
                        + "ELSE '/realtime/export' END,'?taskId=',t.id,'&tab=instances&instanceId=',i.id) route,"
                        + "i.update_time updatedAt FROM rt_task_instance i JOIN rt_task t ON t.id=i.task_id) s WHERE ";
                return new QuerySpec("SELECT 'REALTIME_INSTANCE' type,search_id id,title,subtitle,status,mode,route,updatedAt" + base
                        + searchCondition + ranking, "SELECT COUNT(*)" + base + searchCondition, false);
            }
            case "CHAT_SESSION": {
                String base = " FROM (SELECT session_id search_id,COALESCE(title,'未命名会话') search_name,"
                        + "COALESCE(title,'未命名会话') title,'SQL Agent 会话' subtitle,"
                        + "IF(archived=1,'ARCHIVED','ACTIVE') status,'offline' mode,CONCAT('/chat/',session_id) route,"
                        + "last_active_at updatedAt,ob_id FROM chat_session) s WHERE " + searchCondition + " AND ob_id=?";
                return new QuerySpec("SELECT 'CHAT_SESSION' type,search_id id,title,subtitle,status,mode,route,updatedAt" + base
                        + ranking, "SELECT COUNT(*)" + base, true);
            }
            case "REALTIME_ALERT": {
                String base = " FROM (SELECT a.id search_id,a.title search_name,a.title,"
                        + "CONCAT(t.task_name,' · ',COALESCE(a.detail,'')) subtitle,a.status,'realtime' mode,"
                        + "CONCAT('/realtime/alerts?alertId=',a.id) route,a.update_time updatedAt "
                        + "FROM rt_alert a JOIN rt_task t ON t.id=a.task_id) s WHERE ";
                return new QuerySpec("SELECT 'REALTIME_ALERT' type,search_id id,title,subtitle,status,mode,route,updatedAt" + base
                        + searchCondition + ranking, "SELECT COUNT(*)" + base + searchCondition, false);
            }
            default: throw new IllegalArgumentException("不支持的搜索类型: " + type);
        }
    }

    private Map<String, Object> hiveGroup(String keyword, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        CompletableFuture<Map<String, Object>> future = CompletableFuture.supplyAsync(
                () -> agentProxyService.searchHiveTables(keyword, null, pageSize, offset), executor);
        try {
            Map<String, Object> response = future.get(HIVE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            List<Map<String, Object>> items = hiveItems(response);
            long total = number(response.get("total"));
            return successGroup("HIVE_TABLE", page, pageSize, total, items);
        } catch (TimeoutException ex) {
            future.cancel(true);
            return failedGroup("HIVE_TABLE", page, pageSize, "Hive 搜索超过 1.5 秒，已跳过本次远端结果");
        } catch (Exception ex) {
            return failedGroup("HIVE_TABLE", page, pageSize, safe(ex));
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> hiveItems(Map<String, Object> response) {
        Object rawItems = response.get("items");
        if (!(rawItems instanceof List)) return Collections.emptyList();
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object raw : (List<?>) rawItems) {
            if (!(raw instanceof Map)) continue;
            Map<String, Object> hive = (Map<String, Object>) raw;
            String db = String.valueOf(hive.getOrDefault("db", ""));
            String table = String.valueOf(hive.getOrDefault("table", ""));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "HIVE_TABLE");
            item.put("id", db + "." + table);
            item.put("title", db + "." + table);
            item.put("subtitle", String.valueOf(hive.getOrDefault("comment", hive.getOrDefault("tableType", "Hive 表"))));
            item.put("status", hive.get("tableType"));
            item.put("mode", "offline");
            item.put("route", "/data-map/catalog?db=" + encode(db) + "&table=" + encode(table));
            item.put("updatedAt", null);
            items.add(item);
        }
        return items;
    }

    private Map<String, Object> successGroup(String type, int page, int pageSize, long total,
            List<Map<String, Object>> items) {
        Map<String, Object> group = baseGroup(type, page, pageSize);
        group.put("total", total);
        group.put("items", items);
        return group;
    }

    private Map<String, Object> failedGroup(String type, int page, int pageSize, String error) {
        Map<String, Object> group = baseGroup(type, page, pageSize);
        group.put("total", 0L);
        group.put("items", Collections.emptyList());
        group.put("error", error);
        return group;
    }

    private Map<String, Object> baseGroup(String type, int page, int pageSize) {
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("type", type);
        group.put("label", label(type));
        group.put("page", page);
        group.put("pageSize", pageSize);
        return group;
    }

    private String normalizeType(String rawType) {
        if (rawType == null || rawType.trim().isEmpty()) return null;
        String type = rawType.trim().toUpperCase(Locale.ROOT);
        if (!VALID_TYPES.contains(type)) throw new IllegalArgumentException("不支持的搜索类型: " + rawType);
        return type;
    }

    private String label(String type) {
        switch (type) {
            case "OFFLINE_TASK": return "离线任务";
            case "REALTIME_TASK": return "实时任务";
            case "HIVE_TABLE": return "Hive 表";
            case "REALTIME_TABLE": return "实时表";
            case "OFFLINE_EXECUTION": return "离线实例";
            case "REALTIME_INSTANCE": return "实时实例";
            case "CHAT_SESSION": return "我的会话";
            case "REALTIME_ALERT": return "实时告警";
            default: return type;
        }
    }

    private long number(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String safe(Exception ex) {
        Throwable cause = ex.getCause() == null ? ex : ex.getCause();
        String message = cause.getMessage();
        return message == null || message.trim().isEmpty() ? cause.getClass().getSimpleName() : message;
    }

    private static final class QuerySpec {
        private final String itemsSql;
        private final String countSql;
        private final boolean actorScoped;

        private QuerySpec(String itemsSql, String countSql, boolean actorScoped) {
            this.itemsSql = itemsSql;
            this.countSql = countSql;
            this.actorScoped = actorScoped;
        }
    }
}
