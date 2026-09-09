package com.yjn.sqlagent.realtime.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjn.sqlagent.realtime.common.PaimonSyncOptionValidator;
import com.yjn.sqlagent.realtime.config.RealtimeProperties;
import com.yjn.sqlagent.realtime.model.ServerRequest;
import com.yjn.sqlagent.realtime.model.SyncTaskRequest;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RealtimeSyncRepository {

    private static final Set<String> ACTIVE = Set.of("submitting", "running", "stopping", "restarting");
    private static final Pattern DATABASE_PREFIX = Pattern.compile("[a-z]{1,9}");
    private static final int PAIMON_IDENTIFIER_MAX_LENGTH = 128;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final ObjectMapper mapper;
    private final RealtimeProperties properties;

    public RealtimeSyncRepository(JdbcTemplate jdbc, ObjectMapper mapper, RealtimeProperties properties) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.mapper = mapper;
        this.properties = properties;
    }

    public Map<String, Object> taskPage(Map<String, String> query) {
        int page = positive(query.get("page"), 1);
        int pageSize = Math.min(100, positive(query.get("pageSize"), 20));
        StringBuilder where = new StringBuilder(" WHERE t.task_type='sync' AND t.status<>'deleted'");
        MapSqlParameterSource params = new MapSqlParameterSource();
        String keyword = text(query.get("keyword"));
        if (!keyword.isEmpty()) {
            where.append(" AND (CAST(t.id AS CHAR) LIKE :keyword OR t.task_name LIKE :keyword OR t.description LIKE :keyword)");
            params.addValue("keyword", "%" + keyword.trim() + "%");
        }
        equal(where, params, query, "status", " AND t.status=:status");
        equalLong(where, params, query, "sourceServerId", " AND c.source_server_id=:sourceServerId");
        like(where, params, query, "owner", " AND t.owner LIKE :owner");
        like(where, params, query, "lastOperator", " AND l.operator LIKE :lastOperator");
        String sourceKeyword = text(query.get("sourceKeyword"));
        String targetKeyword = text(query.get("targetKeyword"));
        // 兼容旧实时页面的单输入框协议。
        String legacyTableKeyword = text(query.get("tableKeyword"));
        if (!legacyTableKeyword.isEmpty()) {
            if ("target".equals(text(query.get("tableScope")))) targetKeyword = legacyTableKeyword;
            else sourceKeyword = legacyTableKeyword;
        }
        if (!sourceKeyword.isEmpty()) {
            where.append(" AND EXISTS (SELECT 1 FROM rt_sync_task_table_mapping fm"
                    + " LEFT JOIN rt_server fs ON fs.id=fm.source_server_id WHERE fm.task_id=t.id AND ("
                    + "fs.name LIKE :sourceKeyword OR fm.source_database LIKE :sourceKeyword"
                    + " OR fm.source_table LIKE :sourceKeyword"
                    + " OR CONCAT_WS('.',fm.source_database,fm.source_table) LIKE :sourceKeyword))");
            params.addValue("sourceKeyword", "%" + sourceKeyword + "%");
        }
        if (!targetKeyword.isEmpty()) {
            where.append(" AND EXISTS (SELECT 1 FROM rt_sync_task_table_mapping fm WHERE fm.task_id=t.id AND ("
                    + "fm.target_database LIKE :targetKeyword OR fm.target_table LIKE :targetKeyword"
                    + " OR CONCAT_WS('.',fm.target_database,fm.target_table) LIKE :targetKeyword))");
            params.addValue("targetKeyword", "%" + targetKeyword + "%");
        }
        Long total = named.queryForObject("SELECT COUNT(*) FROM rt_task t JOIN rt_sync_task_config c ON c.task_id=t.id"
                + " LEFT JOIN rt_task_change_log l ON l.id=(SELECT MAX(l2.id) FROM rt_task_change_log l2 WHERE l2.task_id=t.id)"
                + where, params, Long.class);
        String sort = sortColumn(query.get("sort"));
        String order = "asc".equalsIgnoreCase(query.get("order")) ? "ASC" : "DESC";
        params.addValue("limit", pageSize).addValue("offset", (page - 1) * pageSize);
        String sql = "SELECT t.id,t.task_name name,t.status,t.owner,t.description,t.flink_version flinkVersion,"
                + "t.create_time createTime,t.update_time updateTime,p.project_name projectName,"
                + "c.source_server_id sourceServerId,s.name sourceServerName,s.database_name sourceServerDatabase,c.target_database targetDatabase,"
                + "c.config_json configJson,"
                + "(SELECT COUNT(*) FROM rt_sync_task_table_mapping mc WHERE mc.task_id=t.id) mappingCount,"
                + "JSON_UNQUOTE(JSON_EXTRACT(c.config_json,'$.parallelism')) parallelism,"
                + "JSON_UNQUOTE(JSON_EXTRACT(c.config_json,'$.taskManagerMemory')) taskManagerMemory,"
                + "JSON_UNQUOTE(JSON_EXTRACT(c.config_json,'$.jobManagerMemory')) jobManagerMemory,"
                + "l.operator lastOperator,l.action lastAction,l.create_time lastOperationTime,"
                + "i.id latestInstanceId,i.status runtimeStatus,i.execution_mode executionMode,"
                + "i.managed_flag managed,i.failure_message runtimeFailureMessage,i.tracking_url flinkUrl,"
                + "CASE WHEN i.started_at IS NULL THEN NULL ELSE TIMESTAMPDIFF(SECOND,i.started_at,COALESCE(i.ended_at,NOW())) END runtimeSeconds,"
                + "i.started_at startedAt,i.ended_at endedAt "
                + "FROM rt_task t JOIN rt_sync_task_config c ON c.task_id=t.id "
                + "LEFT JOIN rt_project p ON p.id=t.project_id LEFT JOIN rt_server s ON s.id=c.source_server_id "
                + "LEFT JOIN rt_task_change_log l ON l.id=(SELECT MAX(l2.id) FROM rt_task_change_log l2 WHERE l2.task_id=t.id) "
                + "LEFT JOIN rt_task_instance i ON i.id=(SELECT MAX(i2.id) FROM rt_task_instance i2"
                + " WHERE i2.task_id=t.id AND i2.execution_mode='PRODUCTION') "
                + where + " ORDER BY " + sort + " " + order + " LIMIT :limit OFFSET :offset";
        List<Map<String, Object>> items = named.queryForList(sql, params);
        items.forEach(item -> {
            normalizeBooleans(item);
            fillConfiguredResource(item, jsonMap(item.remove("configJson")));
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("page", page);
        result.put("pageSize", pageSize);
        result.put("total", total == null ? 0 : total);
        return result;
    }

    private void fillConfiguredResource(Map<String, Object> item, Map<String, Object> config) {
        int parallelism = integer(config.get("parallelism"));
        Integer taskManagerMemory = memoryMb(config.get("taskManagerMemory"));
        Integer jobManagerMemory = memoryMb(config.get("jobManagerMemory"));
        if (parallelism <= 0 || taskManagerMemory == null || jobManagerMemory == null) return;
        Map<String, Object> overrides = objectMap(config.get("flinkConfOverrides"));
        int slotsPerTaskManager = Math.max(1, integer(overrides.get("taskmanager.numberOfTaskSlots")));
        int taskManagerCount = (parallelism + slotsPerTaskManager - 1) / slotsPerTaskManager;
        int taskManagerVCores = Math.max(1, integer(overrides.get("yarn.containers.vcores")));
        if (!overrides.containsKey("yarn.containers.vcores")) taskManagerVCores = slotsPerTaskManager;
        int jobManagerVCores = Math.max(1, integer(overrides.get("yarn.appmaster.vcores")));
        item.put("taskManagerCount", taskManagerCount);
        item.put("configuredMemoryMb", jobManagerMemory + taskManagerCount * taskManagerMemory);
        item.put("configuredVCores", jobManagerVCores + taskManagerCount * taskManagerVCores);
    }

    static Integer memoryMb(Object raw) {
        String value = textValue(raw).toLowerCase(Locale.ROOT).replace(" ", "");
        if (value.isEmpty()) return null;
        int multiplier = 1;
        if (value.endsWith("gb")) { multiplier = 1024; value = value.substring(0, value.length() - 2); }
        else if (value.endsWith("g")) { multiplier = 1024; value = value.substring(0, value.length() - 1); }
        else if (value.endsWith("mb")) value = value.substring(0, value.length() - 2);
        else if (value.endsWith("m")) value = value.substring(0, value.length() - 1);
        try { return new BigDecimal(value).multiply(BigDecimal.valueOf(multiplier)).intValueExact(); }
        catch (ArithmeticException | NumberFormatException ignored) { return null; }
    }

    public Map<String, Object> requiredTask(long taskId) {
        List<String> taskTypes = jdbc.query("SELECT task_type FROM rt_task WHERE id=? AND status<>'deleted'",
                (rs, row) -> rs.getString(1), taskId);
        if (!taskTypes.isEmpty() && !"sync".equalsIgnoreCase(taskTypes.get(0))) return requiredManagedTask(taskId, taskTypes.get(0));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT t.id,t.project_id projectId,t.task_name name,t.task_type taskType,t.flink_version flinkVersion,"
                        + "t.owner,t.description,t.status,t.create_time createTime,t.update_time updateTime,"
                        + "c.source_type sourceType,c.source_server_id sourceServerId,c.target_database targetDatabase,"
                        + "c.config_json configJson,s.name sourceServerName,p.project_name projectName "
                        + "FROM rt_task t JOIN rt_sync_task_config c ON c.task_id=t.id "
                        + "LEFT JOIN rt_server s ON s.id=c.source_server_id LEFT JOIN rt_project p ON p.id=t.project_id "
                        + "WHERE t.id=? AND t.task_type='sync' AND t.status<>'deleted'", taskId);
        if (rows.isEmpty()) throw new IllegalArgumentException("同步任务不存在：" + taskId);
        Map<String, Object> task = new LinkedHashMap<>(rows.get(0));
        task.put("taskConfig", normalizePaimonTableNames(jsonMap(task.remove("configJson")),
                longValue(task.get("sourceServerId")), text(task.get("targetDatabase"))));
        task.put("editPolicy", editPolicy(taskId));
        return task;
    }

    private Map<String, Object> requiredManagedTask(long taskId, String taskType) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT t.id,t.project_id projectId,t.task_name name,t.task_type taskType,"
                + "t.flink_version flinkVersion,t.owner,t.description,t.status,t.create_time createTime,t.update_time updateTime,"
                + "p.project_name projectName FROM rt_task t LEFT JOIN rt_project p ON p.id=t.project_id WHERE t.id=? AND t.status<>'deleted'", taskId);
        if (rows.isEmpty()) throw new IllegalArgumentException("实时任务不存在：" + taskId);
        Map<String, Object> task = new LinkedHashMap<>(rows.get(0));
        Map<String, Object> config;
        if ("compute".equalsIgnoreCase(taskType)) {
            Map<String, Object> row = jdbc.queryForMap("SELECT default_database defaultDatabase,sql_text sqlText,config_json configJson FROM rt_compute_task_config WHERE task_id=?", taskId);
            config = jsonMap(row.remove("configJson"));
            Map<String, Object> compute = objectMap(config.get("computeConfig")); compute.put("defaultDatabase", row.get("defaultDatabase")); compute.put("sql", row.get("sqlText"));
            config.put("computeConfig", compute); task.put("sourceType", "paimon"); task.put("targetType", "paimon");
        } else if ("export".equalsIgnoreCase(taskType)) {
            Map<String, Object> row = jdbc.queryForMap("SELECT source_database sourceDatabase,target_server_id targetServerId,config_json configJson FROM rt_export_task_config WHERE task_id=?", taskId);
            config = jsonMap(row.remove("configJson"));
            List<Map<String, Object>> mappings = jdbc.queryForList("SELECT m.realtime_table_id realtimeTableId,r.database_name sourceDatabase,r.table_name sourceTable,"
                    + "m.target_server_id targetServerId,m.target_database targetDatabase,m.target_table targetTable,m.column_mapping_json columnMappingJson,"
                    + "m.primary_keys_json primaryKeysJson,m.write_mode writeMode,m.sort_order sortOrder FROM rt_export_task_table_mapping m "
                    + "JOIN rt_realtime_table r ON r.id=m.realtime_table_id WHERE m.task_id=? ORDER BY m.sort_order,m.id", taskId);
            for (Map<String, Object> mapping : mappings) {
                mapping.put("columnMappings", jsonValue(mapping.remove("columnMappingJson")));
                mapping.put("primaryKeys", jsonValue(mapping.remove("primaryKeysJson")));
            }
            Map<String, Object> export = objectMap(config.get("exportConfig")); export.put("sourceDatabase", row.get("sourceDatabase"));
            export.put("targetServerId", row.get("targetServerId")); export.put("mappings", mappings); config.put("exportConfig", export);
            task.put("sourceType", "paimon"); task.put("targetType", "mysql"); task.put("sourceServerId", row.get("targetServerId"));
            task.put("targetDatabase", requiredServer(((Number) row.get("targetServerId")).longValue(), false).get("databaseName"));
        } else throw new IllegalArgumentException("不支持的任务类型：" + taskType);
        task.put("taskConfig", config); task.put("editPolicy", Map.of("updateBlocked", hasActiveInstance(taskId), "requiredStartType", "direct"));
        return task;
    }

    @Transactional
    public long createTask(SyncTaskRequest request, String actor) {
        Map<String, Object> config = normalizedConfig(request);
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO rt_task(project_id,task_name,task_type,flink_version,owner,description,status)"
                            + " VALUES(?,?,'sync',?,?,?,'not_running')", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, properties.getDefaultProjectId());
            ps.setString(2, request.getName().trim());
            ps.setString(3, text(request.getFlinkVersion(), "2.2.1"));
            ps.setString(4, text(request.getOwner(), actor));
            ps.setString(5, request.getDescription());
            return ps;
        }, holder);
        long taskId = requiredKey(holder);
        jdbc.update("INSERT INTO rt_sync_task_config(task_id,source_type,source_server_id,target_database,config_json)"
                        + " VALUES(?,?,?,?,?)", taskId, "mysql-cdc", request.getSourceServerId(),
                request.getTargetDatabase(), json(config));
        long versionId = insertVersion(taskId, request, config, actor);
        validateMappingConflicts(taskId, request.getSourceServerId(), config);
        rebuildMappings(taskId, request.getSourceServerId(), request.getTargetDatabase(), config, actor);
        insertChange(taskId, null, null, versionId, null, actor, "CREATE", "创建同步任务");
        return taskId;
    }

    @Transactional
    public void updateTask(long taskId, SyncTaskRequest request, String actor) {
        Map<String, Object> currentTask = requiredTask(taskId);
        validateUpdatePolicy(taskId, currentTask, request);
        long operationId = startOperation(taskId, null, "EDIT", actor, json(Map.of("expectedUpdateTime",
                text(request.getExpectedUpdateTime()))));
        Long beforeVersion = latestVersionId(taskId);
        Map<String, Object> config = normalizedConfig(request);
        int updated;
        if (text(request.getExpectedUpdateTime()).isEmpty()) {
            updated = jdbc.update("UPDATE rt_task SET task_name=?,flink_version=?,owner=?,description=?,update_time=NOW() WHERE id=?",
                    request.getName().trim(), text(request.getFlinkVersion(), "2.2.1"),
                    text(request.getOwner(), actor), request.getDescription(), taskId);
        } else {
            updated = jdbc.update("UPDATE rt_task SET task_name=?,flink_version=?,owner=?,description=?,update_time=NOW()"
                            + " WHERE id=? AND DATE_FORMAT(update_time,'%Y-%m-%d %H:%i:%s')=?",
                    request.getName().trim(), text(request.getFlinkVersion(), "2.2.1"),
                    text(request.getOwner(), actor), request.getDescription(), taskId,
                    normalizeDate(request.getExpectedUpdateTime()));
        }
        if (updated == 0) throw new IllegalStateException("任务已被其他用户修改，请刷新后重试");
        jdbc.update("UPDATE rt_sync_task_config SET source_server_id=?,target_database=?,config_json=?,update_time=NOW()"
                        + " WHERE task_id=?", request.getSourceServerId(), request.getTargetDatabase(), json(config), taskId);
        validateMappingConflicts(taskId, request.getSourceServerId(), config);
        jdbc.update("DELETE FROM rt_sync_task_table_mapping WHERE task_id=?", taskId);
        rebuildMappings(taskId, request.getSourceServerId(), request.getTargetDatabase(), config, actor);
        cleanupSyncRealtimeTables(taskId);
        long afterVersion = insertVersion(taskId, request, config, actor);
        insertChange(taskId, operationId, beforeVersion, afterVersion, null, actor, "EDIT", "编辑同步任务配置");
        completeOperation(operationId, "SUCCESS", json(Map.of("beforeVersionId", beforeVersion,
                "afterVersionId", afterVersion)), null);
    }

    @Transactional
    public void deleteTask(long taskId, String actor) {
        requiredTask(taskId);
        if (hasActiveInstance(taskId)) throw new IllegalStateException("任务存在活动实例，历史导入实例也不允许删除");
        long operationId = startOperation(taskId, null, "DELETE", actor, "{}");
        Long beforeVersion = latestVersionId(taskId);
        jdbc.update("UPDATE rt_task SET task_name=CONCAT(task_name,'#deleted#',id),status='deleted',update_time=NOW()"
                + " WHERE id=? AND task_type='sync'", taskId);
        jdbc.update("DELETE FROM rt_sync_task_table_mapping WHERE task_id=?", taskId);
        jdbc.update("DELETE FROM rt_sync_task_config WHERE task_id=?", taskId);
        jdbc.update("DELETE FROM rt_task_table_reference WHERE task_id=?", taskId);
        cleanupSyncRealtimeTables(taskId);
        insertChange(taskId, operationId, beforeVersion, null, null, actor, "DELETE", "删除任务");
        Map<String, Object> operationResult = new LinkedHashMap<>();
        operationResult.put("beforeVersionId", beforeVersion);
        completeOperation(operationId, "SUCCESS", json(operationResult), null);
    }

    public List<Map<String, Object>> mappings(long taskId) {
        return jdbc.queryForList("SELECT m.id,m.task_id taskId,m.source_server_id sourceServerId,s.name serverName,"
                + "m.source_database sourceDatabase,m.source_table sourceTable,m.target_database targetDatabase,"
                + "m.target_table targetTable,m.realtime_table_id realtimeTableId,m.sort_order sortOrder FROM rt_sync_task_table_mapping m"
                + " LEFT JOIN rt_server s ON s.id=m.source_server_id WHERE m.task_id=? ORDER BY m.sort_order,m.id", taskId);
    }

    public List<Map<String, Object>> sourceTableOptions(long serverId, List<String> tables, Long excludeTaskId) {
        if (tables.isEmpty()) return Collections.emptyList();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("serverId", serverId).addValue("tables", tables)
                .addValue("excludeTaskId", excludeTaskId == null ? 0L : excludeTaskId);
        List<Map<String, Object>> occupied = named.queryForList(
                "SELECT m.source_table tableName,t.id occupiedTaskId,t.task_name occupiedTaskName"
                        + " FROM rt_sync_task_table_mapping m JOIN rt_task t ON t.id=m.task_id"
                        + " WHERE m.source_server_id=:serverId AND m.task_id<>:excludeTaskId"
                        + " AND m.source_table IN (:tables)", parameters);
        Map<String, Map<String, Object>> byTable = new LinkedHashMap<>();
        for (Map<String, Object> row : occupied) byTable.putIfAbsent(text(row.get("tableName")), row);
        List<Map<String, Object>> result = new ArrayList<>();
        for (String table : tables) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tableName", table);
            Map<String, Object> owner = byTable.get(table);
            row.put("occupied", owner != null);
            if (owner != null) {
                row.put("occupiedTaskId", owner.get("occupiedTaskId"));
                row.put("occupiedTaskName", owner.get("occupiedTaskName"));
            }
            result.add(row);
        }
        return result;
    }

    public List<Map<String, Object>> versions(long taskId) {
        return jdbc.queryForList("SELECT id,task_id taskId,version_no versionNo,operator,create_time createTime,"
                + "update_time updateTime FROM rt_task_version WHERE task_id=? ORDER BY version_no DESC", taskId);
    }

    public Map<String, Object> versionConfig(long taskId, long versionId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,version_no versionNo,config,operator,create_time createTime FROM rt_task_version"
                        + " WHERE id=? AND task_id=?", versionId, taskId);
        if (rows.isEmpty()) throw new IllegalArgumentException("任务版本不存在");
        Map<String, Object> row = new LinkedHashMap<>(rows.get(0));
        row.put("config", jsonMap(row.get("config")));
        return row;
    }

    public List<Map<String, Object>> instances(long taskId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,task_id taskId,version_id versionId,job_id jobId,yarn_application_id yarnApplicationId,"
                        + "status,execution_mode executionMode,managed_flag managed,savepoint_path savepointPath,"
                        + "tracking_url trackingUrl,failure_message failureMessage,started_at startedAt,ended_at endedAt,"
                        + "create_time createTime,update_time updateTime FROM rt_task_instance WHERE task_id=?"
                        + " ORDER BY create_time DESC,id DESC", taskId);
        rows.forEach(this::normalizeBooleans);
        return rows;
    }

    public Map<String, Object> requiredInstance(long taskId, long instanceId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,task_id taskId,version_id versionId,job_id jobId,yarn_application_id yarnApplicationId,"
                        + "status,execution_mode executionMode,managed_flag managed,startup_log startupLog,"
                        + "effective_config_snapshot_json configJson,savepoint_path savepointPath,tracking_url trackingUrl,"
                        + "failure_message failureMessage,last_runtime_log lastRuntimeLog,started_at startedAt,ended_at endedAt,"
                        + "create_time createTime,update_time updateTime FROM rt_task_instance WHERE id=? AND task_id=?",
                instanceId, taskId);
        if (rows.isEmpty()) throw new IllegalArgumentException("运行实例不存在");
        Map<String, Object> row = new LinkedHashMap<>(rows.get(0));
        normalizeBooleans(row);
        row.put("config", jsonMap(row.remove("configJson")));
        return row;
    }

    public long insertInstance(long taskId, Long versionId, String mode, String configJson) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO rt_task_instance(task_id,version_id,status,execution_mode,managed_flag,"
                            + "effective_config_snapshot_json,started_at) VALUES(?,?,'submitting',?,1,?,NOW())",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, taskId);
            if (versionId == null) ps.setNull(2, java.sql.Types.BIGINT); else ps.setLong(2, versionId);
            ps.setString(3, mode);
            ps.setString(4, configJson);
            return ps;
        }, holder);
        return requiredKey(holder);
    }

    public void updateInstanceSubmission(long instanceId, String status, String jobId, String applicationId,
            String trackingUrl, String startupLog, String failure) {
        boolean terminal = "failed".equals(status) || "canceled".equals(status) || "finished".equals(status)
                || "killed_success".equals(status);
        jdbc.update("UPDATE rt_task_instance SET status=?,job_id=?,yarn_application_id=?,tracking_url=?,startup_log=?,"
                        + "failure_message=?,ended_at=" + (terminal ? "NOW()" : "ended_at") + ",update_time=NOW() WHERE id=? AND managed_flag=1",
                status, jobId, applicationId, trackingUrl, startupLog, limited(failure, 2000), instanceId);
    }

    public void updateInstanceRuntime(long instanceId, String status, String runtimeLog, String failure) {
        boolean terminal = "failed".equals(status) || "canceled".equals(status) || "finished".equals(status)
                || "killed_success".equals(status);
        jdbc.update("UPDATE rt_task_instance SET status=?,last_runtime_log=?,failure_message=?,ended_at="
                        + (terminal ? "COALESCE(ended_at,NOW())" : "NULL") + ",update_time=NOW()"
                        + " WHERE id=? AND managed_flag=1", status, runtimeLog, limited(failure, 2000), instanceId);
    }

    public void updateInstanceIdentifiers(long instanceId, String jobId, String applicationId, String trackingUrl) {
        jdbc.update("UPDATE rt_task_instance SET job_id=COALESCE(NULLIF(?,''),job_id),"
                        + "yarn_application_id=COALESCE(NULLIF(?,''),yarn_application_id),"
                        + "tracking_url=COALESCE(NULLIF(?,''),tracking_url),update_time=NOW()"
                        + " WHERE id=? AND managed_flag=1", jobId, applicationId, trackingUrl, instanceId);
    }

    public void updateSavepointPath(long instanceId, String savepointPath) {
        jdbc.update("UPDATE rt_task_instance SET savepoint_path=?,update_time=NOW() WHERE id=? AND managed_flag=1",
                text(savepointPath).isEmpty() ? null : savepointPath, instanceId);
    }

    public Map<String, Object> activeStopOperation(long taskId, long instanceId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,operator,request_json requestJson FROM rt_task_operation"
                        + " WHERE task_id=? AND task_instance_id=? AND operation_type='STOP'"
                        + " AND operation_status='EXECUTING' AND active_flag=1 ORDER BY id DESC LIMIT 1",
                taskId, instanceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> activeManagedInstances() {
        return jdbc.queryForList("SELECT i.id,i.task_id taskId,i.job_id jobId,i.yarn_application_id yarnApplicationId,"
                + "i.tracking_url trackingUrl,i.status,i.execution_mode executionMode FROM rt_task_instance i"
                + " JOIN rt_task t ON t.id=i.task_id AND t.task_type='sync' WHERE i.managed_flag=1"
                + " AND i.status IN ('submitting','running','debug_success_running','stopping','restarting')");
    }

    /** 活动实例加每个同步任务最新的生产实例，用于修正数据库中的陈旧终态。 */
    public List<Map<String, Object>> reconcileManagedInstances() {
        return jdbc.queryForList("SELECT i.id,i.task_id taskId,i.job_id jobId,i.yarn_application_id yarnApplicationId,"
                + "i.tracking_url trackingUrl,i.status,i.execution_mode executionMode,i.started_at startedAt,"
                + "i.create_time createTime,i.last_runtime_log lastRuntimeLog FROM rt_task_instance i"
                + " JOIN rt_task t ON t.id=i.task_id AND t.task_type='sync' WHERE i.managed_flag=1 AND ("
                + "i.status IN ('submitting','running','debug_success_running','stopping','restarting') OR (i.execution_mode='PRODUCTION'"
                + " AND i.id=(SELECT MAX(p.id) FROM rt_task_instance p WHERE p.task_id=i.task_id"
                + " AND p.managed_flag=1 AND p.execution_mode='PRODUCTION'))) ORDER BY i.id DESC");
    }

    public boolean hasActiveManagedInstance(long taskId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM rt_task_instance WHERE task_id=? AND managed_flag=1"
                + " AND execution_mode='PRODUCTION'"
                + " AND status IN ('submitting','running','debug_success_running','stopping','restarting')", Integer.class, taskId);
        return count != null && count > 0;
    }

    public boolean hasActiveManagedDebugInstance(long taskId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM rt_task_instance WHERE task_id=? AND managed_flag=1"
                + " AND execution_mode='DEBUG'"
                + " AND status IN ('submitting','running','debug_success_running','stopping','restarting')",
                Integer.class, taskId);
        return count != null && count > 0;
    }

    public boolean hasActiveImportedInstance(long taskId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM rt_task_instance WHERE task_id=? AND managed_flag=0"
                + " AND execution_mode='PRODUCTION'"
                + " AND status IN ('submitting','running','debug_success_running','stopping','restarting')", Integer.class, taskId);
        return count != null && count > 0;
    }

    public boolean hasActiveProductionInstance(long taskId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM rt_task_instance WHERE task_id=?"
                + " AND execution_mode='PRODUCTION'"
                + " AND status IN ('submitting','running','debug_success_running','stopping','restarting')", Integer.class, taskId);
        return count != null && count > 0;
    }

    public boolean hasActiveInstance(long taskId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM rt_task_instance WHERE task_id=?"
                + " AND status IN ('submitting','running','debug_success_running','stopping','restarting')", Integer.class, taskId);
        return count != null && count > 0;
    }

    public Long latestVersionId(long taskId) {
        List<Long> ids = jdbc.query("SELECT id FROM rt_task_version WHERE task_id=? ORDER BY version_no DESC,id DESC LIMIT 1",
                (rs, row) -> rs.getLong(1), taskId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    public long startOperation(long taskId, Long instanceId, String type, String actor, String requestJson) {
        KeyHolder holder = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO rt_task_operation(task_id,task_instance_id,operation_type,operation_status,operator,"
                                + "request_json,start_time,deadline_at,active_flag) VALUES(?,?,?,'EXECUTING',?,?,NOW(),DATE_ADD(NOW(),INTERVAL 10 MINUTE),1)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, taskId);
                if (instanceId == null) ps.setNull(2, java.sql.Types.BIGINT); else ps.setLong(2, instanceId);
                ps.setString(3, type);
                ps.setString(4, actor);
                ps.setString(5, requestJson);
                return ps;
            }, holder);
        } catch (DuplicateKeyException ex) {
            throw new IllegalStateException("任务已有操作处理中，请稍后重试");
        }
        return requiredKey(holder);
    }

    public void completeOperation(long operationId, String status, String result, String error) {
        jdbc.update("UPDATE rt_task_operation SET operation_status=?,result_json=?,error_message=?,end_time=NOW(),"
                + "active_flag=NULL,update_time=NOW() WHERE id=?", status, result, error, operationId);
    }

    public void attachOperationInstance(long operationId, long instanceId) {
        jdbc.update("UPDATE rt_task_operation SET task_instance_id=?,update_time=NOW() WHERE id=?",
                instanceId, operationId);
    }

    public int timeoutExpiredOperations() {
        return jdbc.update("UPDATE rt_task_operation SET operation_status='TIMED_OUT',"
                + "error_message='操作超过截止时间，已释放任务锁',end_time=NOW(),active_flag=NULL,update_time=NOW()"
                + " WHERE active_flag=1 AND operation_status='EXECUTING' AND deadline_at<NOW()");
    }

    public List<Map<String, Object>> expiredActiveOperations() {
        return jdbc.queryForList("SELECT id,task_id taskId,task_instance_id taskInstanceId,operation_type operationType,"
                + "start_time startTime,deadline_at deadlineAt FROM rt_task_operation"
                + " WHERE active_flag=1 AND operation_status='EXECUTING' AND deadline_at<NOW() ORDER BY id");
    }

    public int timeoutOperation(long operationId, String reason) {
        return jdbc.update("UPDATE rt_task_operation SET operation_status='TIMED_OUT',error_message=?,"
                + "end_time=NOW(),active_flag=NULL,update_time=NOW() WHERE id=? AND active_flag=1"
                + " AND operation_status='EXECUTING'", reason, operationId);
    }

    public void changeTaskStatus(long taskId, String status) {
        jdbc.update("UPDATE rt_task SET status=?,update_time=NOW() WHERE id=?", status, taskId);
    }

    public void addChange(long taskId, Long operationId, Long versionId, Long instanceId,
            String actor, String action, String detail) {
        insertChange(taskId, operationId, null, versionId, instanceId, actor, action, detail);
    }

    public void addAlert(long taskId, String severity, String title, String detail) {
        jdbc.update("INSERT INTO rt_alert(task_id,severity,status,title,detail) VALUES(?,?,'open',?,?)",
                taskId, severity, title, detail);
    }

    public void addAlertIfOpenAbsent(long taskId, String severity, String title, String detail) {
        jdbc.update("INSERT INTO rt_alert(task_id,severity,status,title,detail)"
                        + " SELECT ?,?,'open',?,? WHERE NOT EXISTS (SELECT 1 FROM rt_alert"
                        + " WHERE task_id=? AND status='open' AND title=?)",
                taskId, severity, title, detail, taskId, title);
    }

    public List<Map<String, Object>> alerts() {
        return jdbc.queryForList("SELECT a.id,a.task_id taskId,t.task_name taskName,a.severity,a.status,a.title,a.detail,"
                + "a.create_time createTime,a.update_time updateTime FROM rt_alert a JOIN rt_task t ON t.id=a.task_id"
                + " ORDER BY (a.status='open') DESC,a.update_time DESC,a.id DESC");
    }

    public void acknowledgeAlert(long id) {
        if (jdbc.update("UPDATE rt_alert SET status='acknowledged',update_time=NOW() WHERE id=?", id) == 0) {
            throw new IllegalArgumentException("告警不存在");
        }
    }

    public List<Map<String, Object>> changes(Long taskId) {
        List<Map<String, Object>> rows;
        if (taskId == null) {
            rows = jdbc.queryForList(changeSql() + " ORDER BY l.create_time DESC,l.id DESC LIMIT 1000");
        } else {
            rows = jdbc.queryForList(changeSql() + " WHERE l.task_id=? ORDER BY l.create_time DESC,l.id DESC", taskId);
        }
        rows = new ArrayList<>(rows);
        rows.forEach(this::enrichChangeRow);
        rows.removeIf(row -> !isTaskChangeLogRow(row));
        return rows;
    }

    public Map<String, Object> changeDetail(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT l.id,l.task_id taskId,t.task_name taskName,"
                + "l.operation_id operationId,l.before_version_id beforeVersionId,l.after_version_id afterVersionId,"
                + "l.task_instance_id taskInstanceId,l.operator,l.action,l.detail,l.create_time createTime,"
                + "l.update_time updateTime,i.execution_mode jobExecutionMode,"
                + "o.operation_type operationType,o.operation_status operationStatus,"
                + "o.request_json operationRequest,o.result_json operationResult,o.error_message operationError,"
                + "o.start_time operationStartTime,o.end_time operationEndTime FROM rt_task_change_log l"
                + " LEFT JOIN rt_task t ON t.id=l.task_id LEFT JOIN rt_task_operation o ON o.id=l.operation_id"
                + " LEFT JOIN rt_task_instance i ON i.id=l.task_instance_id"
                + " WHERE l.id=?", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("变更记录不存在");
        Map<String, Object> result = new LinkedHashMap<>(rows.get(0));
        enrichChangeRow(result);
        if (!isTaskChangeLogRow(result)) throw new IllegalArgumentException("变更记录不存在");
        result.put("operationRequest", jsonValue(result.get("operationRequest")));
        result.put("operationResult", jsonValue(result.get("operationResult")));
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("operationType", result.get("operationType"));
        operation.put("operationStatus", result.get("operationStatus"));
        operation.put("id", result.get("operationId"));
        operation.put("operator", result.get("operator"));
        operation.put("request", result.get("operationRequest"));
        operation.put("result", result.get("operationResult"));
        operation.put("errorMessage", result.get("operationError"));
        operation.put("startTime", result.get("operationStartTime"));
        operation.put("endTime", result.get("operationEndTime"));
        result.put("operation", operation);
        Long taskId = longValue(result.get("taskId"));
        Long beforeVersionId = longValue(result.get("beforeVersionId"));
        Long afterVersionId = longValue(result.get("afterVersionId"));
        if (taskId != null && beforeVersionId != null) {
            Object before = versionConfig(taskId, beforeVersionId).get("config");
            result.put("beforeTask", before);
            result.put("beforeConfig", unifiedSnapshot(before, taskId));
        }
        if (taskId != null && afterVersionId != null) {
            Object after = versionConfig(taskId, afterVersionId).get("config");
            result.put("afterTask", after);
            result.put("afterConfig", unifiedSnapshot(after, taskId));
        }
        Long instanceId = longValue(result.get("taskInstanceId"));
        if (taskId != null && instanceId != null) {
            Map<String, Object> instance = requiredInstance(taskId, instanceId);
            result.put("instance", instance);
            result.put("taskInstanceId", instanceId);
            if ("start".equals(result.get("detailKind")) && instance.get("config") != null) {
                result.put("afterTask", instance.get("config"));
                result.put("afterConfig", unifiedSnapshot(instance.get("config"), taskId));
            }
        }
        if ("edit".equals(result.get("detailKind"))) {
            result.put("diffs", changeDiffs(result.get("beforeConfig"), result.get("afterConfig")));
        }
        return result;
    }

    private List<Map<String, Object>> changeDiffs(Object before, Object after) {
        Map<String, Object> beforeMap = objectMap(before);
        Map<String, Object> afterMap = objectMap(after);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("name", "任务名称"); fields.put("owner", "负责人"); fields.put("description", "描述");
        fields.put("flinkVersion", "flink版本"); fields.put("taskConfig.sourceType", "源端类型");
        fields.put("taskConfig.sourceServerId", "Server");
        fields.put("taskConfig.cdcConfig.databaseName", "源库");
        fields.put("taskConfig.cdcConfig.selectedTables", "源表列表");
        fields.put("taskConfig.cdcConfig.mysqlConfOverrides", "Mysql配置");
        fields.put("taskConfig.cdcConfig.targetDatabase", "目标Paimon库");
        fields.put("taskConfig.cdcConfig.domainPrefix", "目标Paimon表所属域");
        fields.put("taskConfig.cdcConfig.tablePrefix", "目标Paimon表前缀");
        fields.put("taskConfig.cdcConfig.targetTableList", "目标Paimon表列表");
        fields.put("taskConfig.cdcConfig.tableConfigs", "源表私有配置");
        fields.put("taskConfig.cdcConfig.metadataColumns", "目标Paimon表同步元数据列");
        fields.put("taskConfig.cdcConfig.typeMappings", "目标Paimon表类型映射");
        fields.put("taskConfig.cdcConfig.tableConfOverrides", "目标Paimon表配置");
        fields.put("taskConfig.cdcConfig.mode", "整库模式");
        fields.put("alarmConfig.alarmType", "报警设置类型"); fields.put("alarmConfig.alarmGroup", "告警组");
        fields.put("flinkConf.parallelism", "并行度");
        fields.put("flinkConf.taskManagerMemoryGb", "TaskManager 内存");
        fields.put("flinkConf.jobManagerMemoryGb", "JobManager 内存");
        fields.put("flinkConf.checkpointIntervalSeconds", "Checkpoint 间隔");
        fields.put("flinkConf.flinkConfOverrides", "Flink配置");
        List<Map<String, Object>> diffs = new ArrayList<>();
        fields.forEach((path, label) -> {
            Object left = valueAt(beforeMap, path); Object right = valueAt(afterMap, path);
            if (!jsonEquals(left, right)) {
                Map<String, Object> diff = new LinkedHashMap<>();
                diff.put("path", path); diff.put("label", label);
                diff.put("beforeValue", left); diff.put("afterValue", right); diffs.add(diff);
            }
        });
        return diffs;
    }

    private Map<String, Object> unifiedSnapshot(Object value, Long taskId) {
        Map<String, Object> source = objectMap(value);
        Map<String, Object> result = new LinkedHashMap<>();
        if (taskId != null) result.put("taskId", taskId);
        result.put("taskType", "sync");
        for (String key : List.of("name", "owner", "description", "flinkVersion")) {
            if (source.containsKey(key)) result.put(key, source.get(key));
        }
        Map<String, Object> common = objectMap(source.get("taskConfig"));
        Map<String, Object> taskConfig = new LinkedHashMap<>(common);
        taskConfig.put("sourceServerId", source.get("sourceServerId"));
        taskConfig.put("sourceType", text(source.get("sourceType"), "mysql-cdc"));
        for (String key : List.of("alarmType", "alarmGroup", "parallelism", "taskManagerMemory",
                "jobManagerMemory", "checkpointInterval", "flinkConfOverrides")) taskConfig.remove(key);
        result.put("taskConfig", taskConfig);
        Map<String, Object> alarm = new LinkedHashMap<>();
        alarm.put("alarmType", common.get("alarmType")); alarm.put("alarmGroup", common.get("alarmGroup"));
        result.put("alarmConfig", alarm);
        Map<String, Object> flink = new LinkedHashMap<>();
        flink.put("parallelism", common.get("parallelism"));
        flink.put("checkpointIntervalSeconds", common.get("checkpointInterval"));
        flink.put("taskManagerMemoryGb", memoryGbValue(common.get("taskManagerMemory")));
        flink.put("jobManagerMemoryGb", memoryGbValue(common.get("jobManagerMemory")));
        flink.put("flinkConfOverrides", common.get("flinkConfOverrides"));
        result.put("flinkConf", flink);
        return result;
    }

    private Object memoryGbValue(Object raw) {
        Integer mb = memoryMb(raw);
        if (mb == null) return null;
        return BigDecimal.valueOf(mb).divide(BigDecimal.valueOf(1024)).stripTrailingZeros();
    }

    private Object valueAt(Map<String, Object> root, String path) {
        Object current = root;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map)) return null;
            current = ((Map<?, ?>) current).get(part);
        }
        return current;
    }

    private String changeSql() {
        return "SELECT l.id,l.task_id taskId,t.task_name taskName,l.operation_id operationId,"
                + "l.before_version_id beforeVersionId,l.after_version_id afterVersionId,l.task_instance_id taskInstanceId,"
                + "l.operator,l.action,l.detail,l.create_time createTime,l.update_time updateTime,"
                + "i.execution_mode jobExecutionMode FROM rt_task_change_log l"
                + " LEFT JOIN rt_task t ON t.id=l.task_id LEFT JOIN rt_task_instance i ON i.id=l.task_instance_id";
    }

    static boolean isTaskChangeLogRow(Map<String, Object> row) {
        String executionMode = row.get("jobExecutionMode") == null
                ? "" : String.valueOf(row.get("jobExecutionMode")).trim();
        if ("DEBUG".equalsIgnoreCase(executionMode)) return false;
        String action = row.get("action") == null ? "" : String.valueOf(row.get("action")).trim();
        return "创建".equals(action) || "编辑".equals(action) || "删除".equals(action)
                || "启动".equals(action) || "停止".equals(action) || "状态同步".equals(action);
    }

    private void enrichChangeRow(Map<String, Object> row) {
        String action = normalizeChangeAction(text(row.get("action")));
        row.put("action", action);
        String kind = changeDetailKind(row);
        row.put("detailKind", kind);
        String detail = text(row.get("detail"));
        if (!detail.isEmpty()) row.put("summary", detail);
        else if ("create".equals(kind)) row.put("summary", "创建任务配置");
        else if ("edit".equals(kind)) row.put("summary", "编辑任务配置");
        else if ("start".equals(kind)) row.put("summary", "查看启动使用参数");
        else if ("stop".equals(kind)) row.put("summary", "查看停止方式");
        else row.put("summary", text(row.get("operator")) + " 执行 " + action);
    }

    private String changeDetailKind(Map<String, Object> row) {
        String action = text(row.get("action"));
        if ("创建".equals(action) && longValue(row.get("afterVersionId")) != null) return "create";
        if ("编辑".equals(action) && longValue(row.get("beforeVersionId")) != null
                && longValue(row.get("afterVersionId")) != null) return "edit";
        if ("启动".equals(action) && longValue(row.get("afterVersionId")) != null
                && longValue(row.get("taskInstanceId")) != null) return "start";
        if ("停止".equals(action) && longValue(row.get("taskInstanceId")) != null) return "stop";
        return "text";
    }

    public List<Map<String, Object>> params() {
        return jdbc.queryForList("SELECT id,param_type AS `paramType`,param_key AS `paramKey`,key_desc AS `keyDesc`,"
                + "param_value AS `paramValue`,value_type AS `valueType`,input_type AS `inputType`,"
                + "min_value AS `minValue`,max_value AS `maxValue`,step_value AS `stepValue`,"
                + "precision_value AS `precisionValue`,required_flag AS `required`,sort_order AS `sortOrder` FROM rt_task_param"
                + " WHERE task_type='sync' AND enabled_flag=1 ORDER BY param_type,sort_order,id");
    }

    public List<Map<String, Object>> domains() {
        return jdbc.queryForList("SELECT id,domain_code code,domain_name name,sort_order sortOrder FROM rt_paimon_business_domain"
                + " WHERE enabled_flag=1 ORDER BY sort_order,id");
    }

    public List<Map<String, Object>> servers() {
        return jdbc.queryForList("SELECT id,name,type,address,database_name databaseName,database_prefix databasePrefix,account,"
                + "CASE WHEN password IS NULL OR password='' THEN 0 ELSE 1 END passwordConfigured,description,operator,"
                + "create_time createTime,update_time updateTime FROM rt_server WHERE type='mysql' ORDER BY name,id");
    }

    public Map<String, Object> requiredServer(long id, boolean includePassword) {
        String password = includePassword ? ",password" : ",CASE WHEN password IS NULL OR password='' THEN 0 ELSE 1 END passwordConfigured";
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id,name,type,address,database_name databaseName,"
                + "database_prefix databasePrefix,account" + password + ",description,operator FROM rt_server WHERE id=? AND type='mysql'", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Server 不存在");
        return rows.get(0);
    }

    public long createServer(ServerRequest request, String actor) {
        normalizeAndValidateServer(request, null);
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("INSERT INTO rt_server(name,type,address,database_name,"
                    + "database_prefix,account,password,description,operator) VALUES(?,'mysql',?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, request.getName()); ps.setString(2, request.getAddress());
            ps.setString(3, request.getDatabaseName()); ps.setString(4, request.getDatabasePrefix());
            ps.setString(5, request.getAccount()); ps.setString(6, request.getPassword());
            ps.setString(7, request.getDescription()); ps.setString(8, actor);
            return ps;
        }, holder);
        return requiredKey(holder);
    }

    public void updateServer(long id, ServerRequest request, String actor) {
        requiredServer(id, true);
        normalizeAndValidateServer(request, id);
        if (text(request.getPassword()).isEmpty()) {
            jdbc.update("UPDATE rt_server SET name=?,address=?,database_name=?,database_prefix=?,account=?,description=?,"
                            + "operator=?,update_time=NOW() WHERE id=?", request.getName(), request.getAddress(),
                    request.getDatabaseName(), request.getDatabasePrefix(), request.getAccount(), request.getDescription(), actor, id);
        } else {
            jdbc.update("UPDATE rt_server SET name=?,address=?,database_name=?,database_prefix=?,account=?,password=?,description=?,"
                            + "operator=?,update_time=NOW() WHERE id=?", request.getName(), request.getAddress(),
                    request.getDatabaseName(), request.getDatabasePrefix(), request.getAccount(), request.getPassword(),
                    request.getDescription(), actor, id);
        }
    }

    public void deleteServer(long id) {
        Integer used = jdbc.queryForObject("SELECT COUNT(*) FROM rt_sync_task_config WHERE source_server_id=?", Integer.class, id);
        if (used != null && used > 0) throw new IllegalStateException("Server 已被同步任务引用，不能删除");
        jdbc.update("DELETE FROM rt_server WHERE id=?", id);
    }

    private Map<String, Object> normalizedConfig(SyncTaskRequest request) {
        Map<String, Object> config = new LinkedHashMap<>(request.getTaskConfig());
        config.put("sourceServerId", request.getSourceServerId());
        config = normalizePaimonTableNames(config, request.getSourceServerId(), request.getTargetDatabase());
        validateConfig(config);
        return config;
    }

    private Map<String, Object> normalizePaimonTableNames(Map<String, Object> original,
            Long sourceServerId, String targetDatabase) {
        Map<String, Object> config = new LinkedHashMap<>(original);
        config.put("sourceServerId", sourceServerId);
        Map<String, Object> cdc = objectMap(config.get("cdcConfig"));
        cdc.put("mode", "combined");
        cdc.put("targetDatabase", targetDatabase);
        Map<String, Object> server = requiredServer(sourceServerId, false);
        String sourceDatabase = text(cdc.get("databaseName"), text(server.get("databaseName")));
        String domain = text(cdc.get("domainPrefix"));
        String databasePrefix = text(server.get("databasePrefix"));
        String tablePrefix = targetDatabase + "_"
                + (databasePrefix.isEmpty() ? "" : databasePrefix + "_")
                + sourceDatabase + "_" + domain + "_";
        cdc.put("databaseName", sourceDatabase);
        cdc.put("tablePrefix", tablePrefix);
        List<String> targetTables = new ArrayList<>();
        String tableSuffix = text(cdc.get("tableSuffix"));
        for (String table : strings(cdc.get("selectedTables"))) targetTables.add(tablePrefix + table + tableSuffix);
        cdc.put("targetTableList", targetTables);
        if (targetTables.size() == 1) cdc.put("targetTable", targetTables.get(0));
        else cdc.remove("targetTable");
        config.put("cdcConfig", cdc);
        return config;
    }

    public Map<String, Object> validatePreview(SyncTaskRequest request, Long excludeTaskId) {
        Map<String, Object> config = normalizedConfig(request);
        validateMappingConflicts(excludeTaskId == null ? 0L : excludeTaskId,
                request.getSourceServerId(), config);
        return config;
    }

    private void validateConfig(Map<String, Object> config) {
        Map<String, Object> cdc = objectMap(config.get("cdcConfig"));
        List<String> tables = strings(cdc.get("selectedTables"));
        if (tables.isEmpty()) throw new IllegalArgumentException("至少选择一张源表");
        String domain = text(cdc.get("domainPrefix"));
        if (!domain.matches("[a-z0-9]+")) throw new IllegalArgumentException("业务域只能包含小写字母和数字");
        String targetDatabase = text(cdc.get("targetDatabase"));
        validateTargetIdentifier(targetDatabase, "Paimon 目标库名");
        String prefix = text(cdc.get("tablePrefix"));
        for (String table : tables) validateTargetIdentifier(prefix + table + text(cdc.get("tableSuffix")), "Paimon 目标表名");
        validateDynamicParams("mysql_conf", objectMap(cdc.get("mysqlConfOverrides")));
        validateDynamicParams("table_conf", objectMap(cdc.get("tableConfOverrides")));
        validateDynamicParams("flink_conf", objectMap(config.get("flinkConfOverrides")));
        validatePaimonFlinkCompatibility(objectMap(config.get("flinkConfOverrides")));
        PaimonSyncOptionValidator.validateTableConf(objectMap(cdc.get("tableConfOverrides")));
        Map<String, Object> tableConfigs = objectMap(cdc.get("tableConfigs"));
        for (Map.Entry<String, Object> entry : tableConfigs.entrySet()) {
            if (!tables.contains(entry.getKey())) throw new IllegalArgumentException("表私有配置不属于已选源表：" + entry.getKey());
            Map<String, Object> item = objectMap(entry.getValue());
            List<String> primary = strings(item.get("primaryKeys"));
            List<String> partitions = strings(item.get("partitionKeys"));
            if (!primary.isEmpty() && !primary.containsAll(partitions)) throw new IllegalArgumentException("分区键必须是主键子集：" + entry.getKey());
            if (!primary.isEmpty() && !partitions.isEmpty() && partitions.containsAll(primary)) {
                throw new IllegalArgumentException("分区键不能包含全部主键：" + entry.getKey());
            }
        }
    }

    private void validateMappingConflicts(long taskId, long serverId, Map<String, Object> config) {
        List<String> tables = strings(objectMap(config.get("cdcConfig")).get("selectedTables"));
        if (tables.isEmpty()) return;
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("serverId", serverId).addValue("taskId", taskId).addValue("tables", tables);
        List<Map<String, Object>> conflicts = named.queryForList(
                "SELECT m.source_table sourceTable,t.task_name taskName FROM rt_sync_task_table_mapping m"
                        + " JOIN rt_task t ON t.id=m.task_id WHERE m.source_server_id=:serverId"
                        + " AND m.task_id<>:taskId AND m.source_table IN (:tables)", parameters);
        if (!conflicts.isEmpty()) {
            Map<String, Object> first = conflicts.get(0);
            throw new IllegalStateException("源表 " + first.get("sourceTable") + " 已被同步任务“"
                    + first.get("taskName") + "”占用");
        }
    }

    private void validateDynamicParams(String paramType, Map<String, Object> overrides) {
        if (overrides.isEmpty()) return;
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT param_key paramKey,key_desc keyDesc,param_value paramValue,"
                + "input_type inputType,min_value minValue,max_value maxValue,step_value stepValue,"
                + "precision_value precisionValue FROM rt_task_param WHERE task_type='sync' AND param_type=?"
                + " AND enabled_flag=1", paramType);
        Map<String, Map<String, Object>> allowed = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) allowed.put(text(row.get("paramKey")), row);
        for (Map.Entry<String, Object> entry : overrides.entrySet()) {
            Map<String, Object> row = allowed.get(entry.getKey());
            if (row == null) throw new IllegalArgumentException("不支持的同步参数：" + entry.getKey());
            String value = text(entry.getValue());
            String label = text(row.get("keyDesc"), entry.getKey());
            if (value.isEmpty()) throw new IllegalArgumentException(label + "不能为空");
            if ("input_number".equals(row.get("inputType"))) validateNumberParam(row, value, label);
            if ("select".equals(row.get("inputType"))) validateSelectParam(row, value, label);
        }
        if ("table_conf".equals(paramType)) {
            validateSnapshotRetentionOrder(overrides, allowed);
            validatePaimonOptionRelations(overrides, allowed);
        }
    }

    private void validateNumberParam(Map<String, Object> row, String value, String label) {
        final BigDecimal number;
        try { number = new BigDecimal(value); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException(label + "必须是数字：" + value); }
        BigDecimal min = decimal(row.get("minValue"));
        BigDecimal max = decimal(row.get("maxValue"));
        BigDecimal step = decimal(row.get("stepValue"));
        if (min != null && number.compareTo(min) < 0) throw new IllegalArgumentException(label + "必须大于等于 " + min);
        if (max != null && number.compareTo(max) > 0) throw new IllegalArgumentException(label + "必须小于等于 " + max);
        int precision = integer(row.get("precisionValue"));
        if (row.get("precisionValue") != null && Math.max(0, number.stripTrailingZeros().scale()) > precision) {
            throw new IllegalArgumentException(label + "最多保留 " + precision + " 位小数");
        }
        BigDecimal base = min == null ? BigDecimal.ZERO : min;
        if (step != null && step.signum() > 0 && number.subtract(base).remainder(step).compareTo(BigDecimal.ZERO) != 0) {
            throw new IllegalArgumentException(label + "必须按步长 " + step.stripTrailingZeros().toPlainString() + " 递增");
        }
    }

    private void validateSelectParam(Map<String, Object> row, String value, String label) {
        Object options = jsonValue(row.get("paramValue"));
        List<String> allowed = new ArrayList<>();
        if (options instanceof Iterable) {
            for (Object option : (Iterable<?>) options) {
                allowed.add(option instanceof Map ? text(((Map<?, ?>) option).get("value")) : text(option));
            }
        }
        if (!allowed.isEmpty() && !allowed.contains(value)) throw new IllegalArgumentException(label + "不支持该选项：" + value);
    }

    private void validateSnapshotRetentionOrder(Map<String, Object> overrides,
            Map<String, Map<String, Object>> allowed) {
        String min = configured(overrides, allowed, "snapshot.num-retained.min");
        String max = configured(overrides, allowed, "snapshot.num-retained.max");
        if (min.isEmpty() || max.isEmpty() || "infinite".equalsIgnoreCase(max)) return;
        try {
            if (new BigDecimal(min).compareTo(new BigDecimal(max)) > 0) {
                throw new IllegalArgumentException("Snapshot 最小保留数量不能大于最大保留数量");
            }
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Snapshot 保留数量必须是整数或 infinite");
        }
    }

    private String configured(Map<String, Object> overrides, Map<String, Map<String, Object>> allowed, String key) {
        if (overrides.containsKey(key)) return text(overrides.get(key));
        Map<String, Object> row = allowed.get(key);
        if (row == null) return "";
        if (!"select".equals(row.get("inputType"))) return text(row.get("paramValue"));
        Object options = jsonValue(row.get("paramValue"));
        if (options instanceof Iterable<?>) for (Object option : (Iterable<?>) options) {
            if (option instanceof Map<?, ?> && Boolean.TRUE.equals(((Map<?, ?>) option).get("default"))) {
                return text(((Map<?, ?>) option).get("value"));
            }
        }
        return "";
    }

    private void validatePaimonOptionRelations(Map<String, Object> overrides,
            Map<String, Map<String, Object>> allowed) {
        Map<String, Object> effective = new LinkedHashMap<>();
        for (String key : List.of("changelog-producer", "changelog-producer.row-deduplicate",
                "merge-engine")) {
            String value = configured(overrides, allowed, key);
            if (!value.isEmpty()) effective.put(key, value);
        }
        if (overrides.containsKey("changelog-producer.row-deduplicate-ignore-fields")) {
            effective.put("changelog-producer.row-deduplicate-ignore-fields",
                    overrides.get("changelog-producer.row-deduplicate-ignore-fields"));
        }
        PaimonSyncOptionValidator.validateTableConf(effective);
    }

    private void validatePaimonFlinkCompatibility(Map<String, Object> overrides) {
        String checkpointMode = text(overrides.get("execution.checkpointing.mode"));
        if (!checkpointMode.isEmpty() && !"EXACTLY_ONCE".equalsIgnoreCase(checkpointMode)) {
            throw new IllegalArgumentException("Paimon 同步仅支持 EXACTLY_ONCE Checkpoint 模式");
        }
        if ("true".equalsIgnoreCase(text(overrides.get("execution.checkpointing.unaligned.enabled")))) {
            throw new IllegalArgumentException("Paimon 同步不支持 Unaligned Checkpoint，请关闭该配置");
        }
    }

    private BigDecimal decimal(Object value) {
        if (value == null || text(value).isEmpty()) return null;
        return value instanceof BigDecimal ? (BigDecimal) value : new BigDecimal(text(value));
    }

    private long insertVersion(long taskId, SyncTaskRequest request, Map<String, Object> config, String actor) {
        Integer version = jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM rt_task_version WHERE task_id=?",
                Integer.class, taskId);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", request.getName()); snapshot.put("owner", request.getOwner());
        snapshot.put("description", request.getDescription()); snapshot.put("flinkVersion", request.getFlinkVersion());
        snapshot.put("sourceServerId", request.getSourceServerId()); snapshot.put("sourceType", "mysql-cdc");
        snapshot.put("targetDatabase", request.getTargetDatabase()); snapshot.put("taskConfig", config);
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO rt_task_version(task_id,version_no,config,operator) VALUES(?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, taskId); ps.setInt(2, version == null ? 1 : version);
            ps.setString(3, json(snapshot)); ps.setString(4, actor); return ps;
        }, holder);
        return requiredKey(holder);
    }

    private void rebuildMappings(long taskId, long serverId, String targetDatabase, Map<String, Object> config, String actor) {
        Map<String, Object> server = requiredServer(serverId, false);
        Map<String, Object> cdc = objectMap(config.get("cdcConfig"));
        String sourceDatabase = text(cdc.get("databaseName"), text(server.get("databaseName")));
        String prefix = text(cdc.get("tablePrefix"));
        if (prefix.isEmpty()) throw new IllegalArgumentException("Paimon 目标表前缀不能为空");
        String tableSuffix = text(cdc.get("tableSuffix"));
        jdbc.update("DELETE FROM rt_task_table_reference WHERE task_id=? AND reference_role='OUTPUT'", taskId);
        int order = 0;
        for (String table : strings(cdc.get("selectedTables"))) {
            String targetTable = prefix + table + tableSuffix;
            long realtimeTableId = registerSyncRealtimeTable(taskId, targetDatabase, targetTable, actor);
            jdbc.update("INSERT INTO rt_sync_task_table_mapping(task_id,source_server_id,source_database,source_table,"
                            + "target_database,target_table,realtime_table_id,sort_order) VALUES(?,?,?,?,?,?,?,?)", taskId, serverId,
                    sourceDatabase, table, targetDatabase, targetTable, realtimeTableId, order++);
            jdbc.update("INSERT IGNORE INTO rt_task_table_reference(task_id,realtime_table_id,reference_role) VALUES(?,?,'OUTPUT')", taskId, realtimeTableId);
        }
    }

    private long registerSyncRealtimeTable(long taskId, String database, String table, String actor) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id,producer_task_id producerTaskId FROM rt_realtime_table "
                + "WHERE catalog_name='paimon' AND database_name=? AND table_name=?", database, table);
        if (!rows.isEmpty()) {
            Object producer = rows.get(0).get("producerTaskId");
            if (producer != null && ((Number) producer).longValue() != taskId) {
                throw new IllegalStateException("目标 Paimon 表已绑定其他生产任务：" + database + "." + table);
            }
            long id = ((Number) rows.get(0).get("id")).longValue();
            jdbc.update("UPDATE rt_realtime_table SET creation_source='sync',producer_task_id=?,operator=?,update_time=NOW() WHERE id=?", taskId, actor, id);
            return id;
        }
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("INSERT INTO rt_realtime_table(catalog_name,database_name,table_name,"
                    + "table_type,creation_source,producer_task_id,physical_status,table_options_json,operator) "
                    + "VALUES('paimon',?,?,'primary_key','sync',?,'declared','{}',?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, database); ps.setString(2, table); ps.setLong(3, taskId); ps.setString(4, actor); return ps;
        }, holder);
        return requiredKey(holder);
    }

    private void cleanupSyncRealtimeTables(long taskId) {
        List<Long> retained = jdbc.query("SELECT realtime_table_id FROM rt_sync_task_table_mapping WHERE task_id=?"
                        + " AND realtime_table_id IS NOT NULL", (rs, row) -> rs.getLong(1), taskId);
        List<Map<String, Object>> candidates = jdbc.queryForList("SELECT id,physical_status physicalStatus"
                + " FROM rt_realtime_table WHERE creation_source='sync' AND producer_task_id=?", taskId);
        for (Map<String, Object> candidate : candidates) {
            long id = ((Number) candidate.get("id")).longValue();
            if (retained.contains(id)) continue;
            if ("declared".equalsIgnoreCase(text(candidate.get("physicalStatus")))) {
                jdbc.update("DELETE FROM rt_realtime_table WHERE id=? AND NOT EXISTS"
                        + " (SELECT 1 FROM rt_task_table_reference x WHERE x.realtime_table_id=?)", id, id);
            } else {
                jdbc.update("UPDATE rt_realtime_table SET producer_task_id=NULL,update_time=NOW() WHERE id=?", id);
            }
        }
    }

    private void normalizeAndValidateServer(ServerRequest request, Long currentId) {
        if (!"mysql".equalsIgnoreCase(text(request.getType()))) {
            throw new IllegalArgumentException("同步任务 Server 仅支持 mysql");
        }
        String name = text(request.getName());
        Integer sameName = jdbc.queryForObject("SELECT COUNT(*) FROM rt_server WHERE LOWER(TRIM(name))=LOWER(?)"
                + (currentId == null ? "" : " AND id<>?"), Integer.class,
                currentId == null ? new Object[] {name} : new Object[] {name, currentId});
        if (sameName != null && sameName > 0) throw new IllegalArgumentException("Server 名称已存在");
        String database = text(request.getDatabaseName());
        if (database.isEmpty()) throw new IllegalArgumentException("MySQL Server 数据库不能为空");
        String prefix = text(request.getDatabasePrefix());
        if (!prefix.isEmpty() && !DATABASE_PREFIX.matcher(prefix).matches()) {
            throw new IllegalArgumentException("库前缀只能是小写字母，长度小于10");
        }
        request.setDatabaseName(database);
        request.setDatabasePrefix(prefix);
        List<Map<String, Object>> sameDatabase = jdbc.queryForList(
                "SELECT id,database_prefix databasePrefix FROM rt_server WHERE type='mysql' AND LOWER(TRIM(database_name))=LOWER(?)",
                database);
        for (Map<String, Object> row : sameDatabase) {
            if (currentId != null && currentId.equals(longValue(row.get("id")))) continue;
            if (prefix.isEmpty()) throw new IllegalArgumentException("该数据库已存在，请填写库前缀");
            if (prefix.equals(text(row.get("databasePrefix")))) {
                throw new IllegalArgumentException("库前缀与数据库组合已存在，请更换库前缀");
            }
        }
    }

    private void validateTargetIdentifier(String value, String label) {
        if (value.isEmpty()) throw new IllegalArgumentException(label + "不能为空");
        if (value.indexOf('$') >= 0) throw new IllegalArgumentException(label + "不能包含 $，该字符用于系统表：" + value);
        if (value.length() > PAIMON_IDENTIFIER_MAX_LENGTH) {
            throw new IllegalArgumentException(label + "不能超过 " + PAIMON_IDENTIFIER_MAX_LENGTH + " 个字符：" + value);
        }
    }

    private void insertChange(long taskId, Long operationId, Long beforeVersionId, Long afterVersionId,
            Long instanceId, String actor, String action, String detail) {
        jdbc.update("INSERT INTO rt_task_change_log(task_id,operation_id,before_version_id,after_version_id,"
                        + "task_instance_id,operator,action,detail) VALUES(?,?,?,?,?,?,?,?)", taskId, operationId,
                beforeVersionId, afterVersionId, instanceId, actor, normalizeChangeAction(action), detail);
    }

    static String normalizeChangeAction(String action) {
        String value = action == null ? "" : action.trim();
        switch (value.toUpperCase(Locale.ROOT)) {
            case "CREATE": return "创建";
            case "EDIT": return "编辑";
            case "DELETE": return "删除";
            case "START": return "启动";
            case "STOP": return "停止";
            case "STATUS_SYNC":
            case "REFRESH": return "状态同步";
            default:
                if ("创建任务".equals(value)) return "创建";
                if ("编辑任务".equals(value)) return "编辑";
                if ("启动任务".equals(value)) return "启动";
                if ("删除任务".equals(value)) return "删除";
                if ("停止任务".equals(value)) return "停止";
                if ("刷新状态".equals(value)) return "状态同步";
                return value;
        }
    }

    public Map<String, Object> editPolicy(long taskId) {
        Map<String, Object> result = new LinkedHashMap<>();
        // DEBUG 实例不改变生产任务编辑策略，与参考平台保持一致。
        boolean active = hasActiveProductionInstance(taskId);
        List<Map<String, Object>> snapshots = productionSnapshots(taskId);
        boolean productionLocked = !snapshots.isEmpty();
        Map<String, Object> current = taskConfigFromSnapshot(requiredTaskConfig(taskId));
        Map<String, Object> restorable = restorableSnapshot(snapshots);
        Map<String, Object> referenceRow = restorable.isEmpty() && !snapshots.isEmpty() ? snapshots.get(0) : restorable;
        Map<String, Object> latest = referenceRow.isEmpty()
                ? new LinkedHashMap<>() : taskConfigFromSnapshot(referenceRow.get("configJson"));
        List<String> lockedTables = new ArrayList<>();
        Map<String, Object> lockedTableConfigs = new LinkedHashMap<>();
        Map<String, Object> referenceCdc = objectMap(latest.get("cdcConfig"));
        Map<String, Object> referenceTableConfigs = objectMap(referenceCdc.get("tableConfigs"));
        for (String table : strings(referenceCdc.get("selectedTables"))) {
            lockedTables.add(table);
            lockedTableConfigs.put(table, objectMap(referenceTableConfigs.get(table)));
        }
        if (lockedTables.isEmpty()) lockedTables.addAll(selectedTables(current));
        result.put("editable", !active);
        result.put("updateBlocked", active);
        result.put("structureLocked", productionLocked);
        result.put("productionLocked", productionLocked);
        result.put("lockedTables", lockedTables);
        result.put("lockedTableConfigs", lockedTableConfigs);
        boolean tableSetChanged = productionLocked && !new java.util.LinkedHashSet<>(selectedTables(current))
                .equals(new java.util.LinkedHashSet<>(selectedTables(latest)));
        result.put("topologyChanged", tableSetChanged);
        result.put("syncTableSetChanged", tableSetChanged);
        if (tableSetChanged && !restorable.isEmpty()) {
            result.put("requiredStartType", "savepoint");
            result.put("requiredStatePath", text(restorable.get("savepointPath")));
        }
        result.put("reason", active ? "任务正在提交、运行、停止或重启，当前不能修改配置" : "");
        return result;
    }

    private void validateUpdatePolicy(long taskId, Map<String, Object> currentTask, SyncTaskRequest request) {
        if (hasActiveProductionInstance(taskId)) throw new IllegalStateException("任务正在提交、运行、停止或重启，当前不能修改配置");
        List<Map<String, Object>> snapshots = productionSnapshots(taskId);
        if (snapshots.isEmpty()) return;
        Map<String, Object> current = objectMap(currentTask.get("taskConfig"));
        Map<String, Object> next = request.getTaskConfig();
        Map<String, Object> currentCdc = objectMap(current.get("cdcConfig"));
        Map<String, Object> nextCdc = objectMap(next.get("cdcConfig"));
        List<String> lockedNames = List.of("databaseName", "targetDatabase", "domainPrefix", "tablePrefix",
                "targetTable", "mergeShards", "tableSuffix", "tableMapping", "metadataColumns",
                "typeMappings", "tableConfOverrides");
        List<String> changes = new ArrayList<>();
        if (!java.util.Objects.equals(longValue(currentTask.get("sourceServerId")), request.getSourceServerId())) changes.add("MySQL Server");
        for (String name : lockedNames) if (!jsonEquals(currentCdc.get(name), nextCdc.get(name))) changes.add(name);
        Map<String, Object> nextTableConfigs = objectMap(nextCdc.get("tableConfigs"));
        List<String> nextTables = strings(nextCdc.get("selectedTables"));
        if (!new java.util.LinkedHashSet<>(strings(currentCdc.get("selectedTables")))
                .equals(new java.util.LinkedHashSet<>(nextTables)) && restorableSnapshot(snapshots).isEmpty()) {
            changes.add("增删同步表前必须先将当前正式实例通过 Savepoint 成功停止");
        }
        for (Map<String, Object> row : snapshots) {
            Map<String, Object> snapshot = taskConfigFromSnapshot(row.get("configJson"));
            Map<String, Object> cdc = objectMap(snapshot.get("cdcConfig"));
            Map<String, Object> configs = objectMap(cdc.get("tableConfigs"));
            for (String table : strings(cdc.get("selectedTables"))) {
                if (nextTables.contains(table) && !jsonEquals(configs.get(table), nextTableConfigs.get(table))) {
                    changes.add("修改已投产表配置 " + table);
                }
            }
        }
        if (!changes.isEmpty()) throw new IllegalStateException("任务已有生产实例，以下结构配置已锁定：" + String.join("、", new java.util.LinkedHashSet<>(changes)));
    }

    private List<Map<String, Object>> productionSnapshots(long taskId) {
        return jdbc.queryForList("SELECT effective_config_snapshot_json configJson,status,savepoint_path savepointPath"
                + " FROM rt_task_instance WHERE task_id=? AND execution_mode='PRODUCTION'"
                + " ORDER BY create_time DESC,id DESC", taskId);
    }

    private Map<String, Object> restorableSnapshot(List<Map<String, Object>> snapshots) {
        for (Map<String, Object> row : snapshots) {
            if ("finished".equalsIgnoreCase(text(row.get("status")))
                    && !text(row.get("savepointPath")).isEmpty()
                    && !taskConfigFromSnapshot(row.get("configJson")).isEmpty()) return row;
        }
        return new LinkedHashMap<>();
    }

    private Object requiredTaskConfig(long taskId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT c.config_json configJson FROM rt_sync_task_config c WHERE c.task_id=?", taskId);
        return rows.isEmpty() ? null : rows.get(0).get("configJson");
    }

    private Map<String, Object> taskConfigFromSnapshot(Object value) {
        Map<String, Object> root = jsonMap(value);
        if (root.containsKey("taskConfig")) return objectMap(root.get("taskConfig"));
        Map<String, Object> task = objectMap(root.get("task"));
        if (task.containsKey("taskConfig")) return objectMap(task.get("taskConfig"));
        if (root.containsKey("cdcConfig")) return root;
        return new LinkedHashMap<>();
    }

    private List<String> selectedTables(Map<String, Object> config) {
        return strings(objectMap(config.get("cdcConfig")).get("selectedTables"));
    }

    private boolean jsonEquals(Object left, Object right) {
        return java.util.Objects.equals(jsonValue(left), jsonValue(right));
    }

    String sortColumn(String value) {
        Map<String, String> columns = Map.of("id", "t.id", "name", "t.task_name", "status", "t.status", "owner", "t.owner",
                "createTime", "t.create_time", "updateTime", "t.update_time", "lastOperationTime", "l.create_time",
                "runtime", "i.started_at");
        return value == null ? "COALESCE(l.create_time,t.update_time)"
                : columns.getOrDefault(value, "COALESCE(l.create_time,t.update_time)");
    }
    private void like(StringBuilder where, MapSqlParameterSource params, Map<String, String> query,
            String key, String clause) {
        if (!text(query.get(key)).isEmpty()) { where.append(clause); params.addValue(key, "%" + query.get(key).trim() + "%"); }
    }
    private void equal(StringBuilder where, MapSqlParameterSource params, Map<String, String> query,
            String key, String clause) {
        if (!text(query.get(key)).isEmpty() && !"all".equalsIgnoreCase(query.get(key))) {
            where.append(clause); params.addValue(key, query.get(key).trim());
        }
    }
    private void equalLong(StringBuilder where, MapSqlParameterSource params, Map<String, String> query,
            String key, String clause) {
        if (!text(query.get(key)).isEmpty()) { where.append(clause); params.addValue(key, Long.valueOf(query.get(key))); }
    }
    private int positive(String value, int fallback) {
        try { return Math.max(1, Integer.parseInt(value)); } catch (Exception ignored) { return fallback; }
    }
    private long requiredKey(KeyHolder holder) {
        if (holder.getKey() == null) throw new IllegalStateException("数据库未返回主键");
        return holder.getKey().longValue();
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); } catch (Exception ex) { throw new IllegalArgumentException("配置JSON序列化失败", ex); }
    }
    private Map<String, Object> jsonMap(Object value) {
        if (value instanceof Map) return objectMap(value);
        if (text(value).isEmpty()) return new LinkedHashMap<>();
        try { return mapper.readValue(String.valueOf(value), new TypeReference<Map<String, Object>>() {}); }
        catch (Exception ex) { throw new IllegalStateException("数据库中的同步配置JSON无法解析", ex); }
    }
    private Object jsonValue(Object value) {
        if (value == null || text(value).isEmpty()) return null;
        try { return mapper.readValue(String.valueOf(value), Object.class); }
        catch (Exception ignored) { return value; }
    }
    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        return value instanceof Map ? new LinkedHashMap<>((Map<String, Object>) value) : new LinkedHashMap<>();
    }
    private List<String> strings(Object value) {
        if (value instanceof Iterable) {
            List<String> result = new ArrayList<>();
            for (Object item : (Iterable<?>) value) if (!text(item).isEmpty()) result.add(text(item));
            return result;
        }
        if (text(value).isEmpty()) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (String item : text(value).split(",")) if (!item.trim().isEmpty()) result.add(item.trim());
        return result;
    }
    private void normalizeBooleans(Map<String, Object> row) {
        if (row.containsKey("managed")) row.put("managed", integer(row.get("managed")) == 1);
        if (row.containsKey("passwordConfigured")) row.put("passwordConfigured", integer(row.get("passwordConfigured")) == 1);
    }
    static int integer(Object value) {
        if (value instanceof Boolean) return Boolean.TRUE.equals(value) ? 1 : 0;
        if (value instanceof Number) return ((Number) value).intValue();
        try { return Integer.parseInt(textValue(value)); } catch (Exception ignored) { return 0; }
    }
    private Long longValue(Object value) {
        if (value == null || text(value).isEmpty()) return null;
        if (value instanceof Number) return ((Number) value).longValue();
        try { return Long.valueOf(text(value)); } catch (NumberFormatException ignored) { return null; }
    }
    private String limited(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }
    private static String textValue(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private String normalizeDate(String value) { return value.replace('T', ' ').substring(0, Math.min(19, value.length())); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private String text(Object value, String fallback) { return text(value).isEmpty() ? fallback : text(value); }
}
