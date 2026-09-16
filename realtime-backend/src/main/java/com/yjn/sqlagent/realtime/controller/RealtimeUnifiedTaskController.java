package com.yjn.sqlagent.realtime.controller;

import com.yjn.sqlagent.realtime.common.RealtimeActorProvider;
import com.yjn.sqlagent.realtime.common.RealtimeResponse;
import com.yjn.sqlagent.realtime.model.TaskActionRequest;
import com.yjn.sqlagent.realtime.model.UnifiedTaskRequest;
import com.yjn.sqlagent.realtime.repository.RealtimeSyncRepository;
import com.yjn.sqlagent.realtime.repository.RealtimeTaskDefinitionRepository;
import com.yjn.sqlagent.realtime.service.RealtimeRuntimeService;
import com.yjn.sqlagent.realtime.service.RealtimeSyncConfigValidator;
import com.yjn.sqlagent.realtime.service.RealtimeSyncTargetValidationService;
import com.yjn.sqlagent.realtime.service.RealtimeTaskDefinitionService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 实时同步、计算和出仓任务的统一入口。 */
@RestController
@RequestMapping("/v1/api/tasks")
public class RealtimeUnifiedTaskController {
    private static final Logger LOG = LoggerFactory.getLogger(RealtimeUnifiedTaskController.class);
    private static final int MAX_FILTER_KEYWORD_LENGTH = 200;
    private final RealtimeSyncRepository repository;
    private final RealtimeRuntimeService runtime;
    private final RealtimeSyncConfigValidator validator;
    private final RealtimeSyncTargetValidationService targetValidator;
    private final RealtimeActorProvider actors;
    @Autowired private RealtimeTaskDefinitionService definitions;
    @Autowired private RealtimeTaskDefinitionRepository definitionRepository;

    public RealtimeUnifiedTaskController(RealtimeSyncRepository repository, RealtimeRuntimeService runtime,
            RealtimeSyncConfigValidator validator, RealtimeSyncTargetValidationService targetValidator,
            RealtimeActorProvider actors) {
        this.repository = repository; this.runtime = runtime; this.validator = validator; this.actors = actors;
        this.targetValidator = targetValidator;
    }

    @GetMapping("/params")
    public RealtimeResponse<List<Map<String, Object>>> params(@RequestParam String taskType) {
        requireTaskType(taskType); actors.requireActor(); return RealtimeResponse.success(repository.params());
    }

    @PostMapping("/page")
    public RealtimeResponse<Map<String, Object>> page(@RequestBody Map<String, Object> input) {
        String taskType = text(input.get("taskType")).toLowerCase(); requireTaskType(taskType); actors.requireActor();
        Map<String, String> query = new LinkedHashMap<>();
        put(query, "page", input.get("pageNo")); put(query, "pageSize", input.get("pageSize"));
        put(query, "keyword", input.get("keyword")); put(query, "status", input.get("status"));
        put(query, "owner", input.get("owner")); put(query, "lastOperator", input.get("lastOperator"));
        put(query, "sourceServerId", input.get("sourceServerId"));
        String source = filterKeyword(input.get("sourceKeyword"), "来源关键字");
        String target = filterKeyword(input.get("targetKeyword"), "去向关键字");
        String legacyPaimon = filterKeyword(input.get("paimonTableKeyword"), "Paimon 表关键字");
        if (!source.isEmpty()) query.put("sourceKeyword", source);
        if (!target.isEmpty()) query.put("targetKeyword", target);
        else if (!legacyPaimon.isEmpty()) query.put("targetKeyword", legacyPaimon);
        put(query, "sort", input.get("sortField")); put(query, "order", input.get("sortOrder"));
        if (!"sync".equals(taskType)) return RealtimeResponse.success(definitionRepository.page(taskType, query));
        Map<String, Object> oldPage = repository.taskPage(query);
        @SuppressWarnings("unchecked") List<Map<String, Object>> records = (List<Map<String, Object>>) oldPage.get("items");
        for (Map<String, Object> record : records) {
            long id = ((Number) record.get("id")).longValue();
            record.put("taskType", "sync"); record.put("mapping", repository.mappings(id));
            record.put("latestInstanceStatus", record.get("runtimeStatus"));
            record.put("latestInstanceExecutionMode", record.get("executionMode"));
            record.put("capabilities", capabilities());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records); result.put("total", oldPage.get("total"));
        result.put("pageNo", oldPage.get("page")); result.put("pageSize", oldPage.get("pageSize"));
        return RealtimeResponse.success(result);
    }

    @PostMapping("/create")
    public RealtimeResponse<Long> create(@Valid @RequestBody UnifiedTaskRequest input) {
        String actor = actors.requireActor();
        if (!"sync".equalsIgnoreCase(input.getTaskType())) return RealtimeResponse.success(definitions.create(input, actor));
        com.yjn.sqlagent.realtime.model.SyncTaskRequest request = input.toSyncTaskRequest();
        long started = System.nanoTime();
        validateSyncSubmission(request, null);
        long validationMs = elapsedMs(started);
        long persistenceStarted = System.nanoTime();
        long taskId = repository.createTask(request, actor);
        LOG.info("sync_save_total operation=create taskId={} tableCount={} validationMs={} persistenceMs={} totalMs={}",
                taskId, selectedTableCount(request), validationMs, elapsedMs(persistenceStarted), elapsedMs(started));
        return RealtimeResponse.success(taskId);
    }

    @PostMapping("/{id}/update")
    public RealtimeResponse<Long> update(@PathVariable long id, @Valid @RequestBody UnifiedTaskRequest input) {
        String actor = actors.requireActor();
        if (!"sync".equalsIgnoreCase(input.getTaskType())) { definitions.update(id,input,actor); return RealtimeResponse.success(id); }
        com.yjn.sqlagent.realtime.model.SyncTaskRequest request = input.toSyncTaskRequest();
        long started = System.nanoTime();
        validateSyncSubmission(request, id);
        long validationMs = elapsedMs(started);
        long persistenceStarted = System.nanoTime();
        repository.updateTask(id, request, actor);
        LOG.info("sync_save_total operation=update taskId={} tableCount={} validationMs={} persistenceMs={} totalMs={}",
                id, selectedTableCount(request), validationMs, elapsedMs(persistenceStarted), elapsedMs(started));
        return RealtimeResponse.success(id);
    }

    @PostMapping("/{id}/delete")
    public RealtimeResponse<Void> delete(@PathVariable long id) {
        String actor=actors.requireActor(); String type=taskType(id); if("sync".equals(type))repository.deleteTask(id,actor);else definitionRepository.delete(id,actor);return RealtimeResponse.success(null);
    }

    @GetMapping("/{id}/detail")
    public RealtimeResponse<Map<String, Object>> detail(@PathVariable long id) {
        actors.requireActor();
        String type = taskType(id);
        return RealtimeResponse.success("sync".equals(type)
                ? unifiedDetail(repository.requiredSyncTask(id)) : definitionRepository.required(id));
    }

    @GetMapping("/{id}/versions")
    public RealtimeResponse<List<Map<String, Object>>> versions(@PathVariable long id) {
        actors.requireActor(); taskType(id); return RealtimeResponse.success(repository.versions(id));
    }

    @GetMapping("/{id}/versions/{versionId}/config")
    public RealtimeResponse<Map<String, Object>> versionConfig(@PathVariable long id,
            @PathVariable long versionId) {
        actors.requireActor(); taskType(id); return RealtimeResponse.success(repository.versionConfig(id, versionId));
    }

    @PostMapping("/analyze-sql")
    public RealtimeResponse<Map<String,Object>> analyzeSql(@Valid @RequestBody UnifiedTaskRequest input) {
        actors.requireActor(); return RealtimeResponse.success(definitions.analyze(input));
    }

    @PostMapping("/command-preview")
    public RealtimeResponse<Map<String, Object>> preview(@Valid @RequestBody UnifiedTaskRequest input,
            @RequestParam(required = false) Long excludeTaskId) {
        actors.requireActor();
        if (!"sync".equalsIgnoreCase(input.getTaskType())) { definitions.validate(input, excludeTaskId); return RealtimeResponse.success(runtime.previewUnifiedRequest(input,excludeTaskId)); }
        com.yjn.sqlagent.realtime.model.SyncTaskRequest request = input.toSyncTaskRequest();
        long started = System.nanoTime();
        validateSyncSubmission(request, excludeTaskId);
        long validationMs = elapsedMs(started);
        long previewStarted = System.nanoTime();
        Map<String, Object> preview = runtime.previewRequest(request, excludeTaskId);
        LOG.info("sync_save_total operation=preview taskId={} tableCount={} validationMs={} previewBuildMs={} totalMs={}",
                excludeTaskId, selectedTableCount(request), validationMs, elapsedMs(previewStarted), elapsedMs(started));
        return RealtimeResponse.success(preview);
    }

    @GetMapping("/{id}/command-preview")
    public RealtimeResponse<Map<String, Object>> preview(@PathVariable long id) {
        actors.requireActor(); taskType(id);
        return RealtimeResponse.success(runtime.previewSaved(id, new TaskActionRequest(), false));
    }

    @PostMapping("/debug")
    public RealtimeResponse<Map<String, Object>> debug(@Valid @RequestBody UnifiedTaskRequest input) {
        if (input.getTaskId() == null) throw new IllegalArgumentException("调试任务 ID 不能为空");
        String actor = actors.requireActor(); if(!"sync".equals(taskType(input.getTaskId())))definitions.validate(input,input.getTaskId());
        TaskActionRequest action = input.toActionRequest();
        if (!"sync".equals(taskType(input.getTaskId()))) action.setDryRun(true);
        return RealtimeResponse.success(runtime.start(input.getTaskId(), action, actor, true));
    }

    @PostMapping("/{id}/can-enable")
    public RealtimeResponse<Map<String, Object>> canEnable(@PathVariable long id) {
        actors.requireActor(); taskType(id);
        String reason = enableBlockedReason(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("canEnable", reason.isEmpty());
        result.put("reason", reason.isEmpty() ? null : reason);
        result.put("message", reason.isEmpty() ? null : reason);
        if (reason.isEmpty() && "sync".equals(taskType(id))) {
            Map<String, Object> policy = repository.editPolicy(id);
            Map<String, Object> startPolicy = new LinkedHashMap<>();
            startPolicy.put("productionLocked", Boolean.TRUE.equals(policy.get("productionLocked")));
            startPolicy.put("syncTableSetChanged", Boolean.TRUE.equals(policy.get("syncTableSetChanged")));
            startPolicy.put("requiredStartType", policy.get("requiredStartType"));
            startPolicy.put("requiredStatePath", policy.get("requiredStatePath"));
            startPolicy.put("canResetConsumptionPoint", Boolean.TRUE.equals(policy.get("productionLocked"))
                    && text(policy.get("requiredStartType")).isEmpty());
            result.put("startPolicy", startPolicy);
        }
        return RealtimeResponse.success(result);
    }

    @PostMapping("/{id}/enable")
    public RealtimeResponse<Map<String, Object>> enable(@PathVariable long id,
            @RequestBody(required = false) TaskActionRequest input) {
        String actor = actors.requireActor(); taskType(id);
        TaskActionRequest action = input == null ? new TaskActionRequest() : input;
        String reason = enableBlockedReason(id);
        if (!reason.isEmpty()) throw new IllegalStateException(reason);
        if("sync".equals(taskType(id))) validateRequiredRecovery(id, action);
        return RealtimeResponse.success(runtime.start(id, action, actor, false));
    }

    @PostMapping("/{id}/stop")
    public RealtimeResponse<Map<String, Object>> stop(@PathVariable long id,
            @RequestBody(required = false) TaskActionRequest input) {
        TaskActionRequest action = input == null ? new TaskActionRequest() : input;
        if (!"savepoint".equalsIgnoreCase(text(action.getStopType()))) {
            throw new IllegalArgumentException("正式实例仅支持 savepoint 停止");
        }
        long instanceId = latestManagedActive(id, "PRODUCTION");
        return RealtimeResponse.success(runtime.stop(id, instanceId, action, actors.requireActor()));
    }

    @GetMapping("/{id}/instances")
    public RealtimeResponse<List<Map<String, Object>>> instances(@PathVariable long id,
            @RequestParam(defaultValue = "PRODUCTION") String executionMode) {
        actors.requireActor(); List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> instance : repository.instances(id)) {
            if (executionMode.equalsIgnoreCase(text(instance.get("executionMode")))) result.add(instance);
        }
        return RealtimeResponse.success(result);
    }

    @GetMapping("/{id}/instances/{instanceId}/config")
    public RealtimeResponse<Object> config(@PathVariable long id, @PathVariable long instanceId) {
        actors.requireActor();
        return RealtimeResponse.success(unifiedInstanceConfig(id, repository.requiredInstance(id, instanceId).get("config")));
    }
    @GetMapping("/{id}/instances/{instanceId}/startup-log") public RealtimeResponse<Map<String, Object>> startup(@PathVariable long id, @PathVariable long instanceId) { actors.requireActor(); return RealtimeResponse.success(Map.of("startupLog", text(repository.requiredInstance(id, instanceId).get("startupLog")))); }
    @GetMapping("/{id}/instances/{instanceId}/runtime-log") public RealtimeResponse<Object> runtimeLog(@PathVariable long id, @PathVariable long instanceId, @RequestParam(required = false) String component, @RequestParam(required = false) String taskManagerId) { actors.requireActor(); String selected = "taskmanager".equalsIgnoreCase(component) && !text(taskManagerId).isEmpty() ? "taskmanager:" + taskManagerId : component; return RealtimeResponse.success(runtime.logs(id, instanceId, selected, null)); }
    @GetMapping("/{id}/instances/{instanceId}/runtime") public RealtimeResponse<Object> runtime(@PathVariable long id, @PathVariable long instanceId) { actors.requireActor(); return RealtimeResponse.success(runtime.runtime(id, instanceId)); }
    @GetMapping("/{id}/instances/{instanceId}/resources") public RealtimeResponse<Object> resources(@PathVariable long id, @PathVariable long instanceId) { actors.requireActor(); return RealtimeResponse.success(runtime.resources(id, instanceId)); }
    @GetMapping("/{id}/instances/{instanceId}/checkpoints") public RealtimeResponse<Object> checkpoints(@PathVariable long id, @PathVariable long instanceId) { actors.requireActor(); return RealtimeResponse.success(runtime.checkpoints(id, instanceId)); }
    @GetMapping("/{id}/instances/{instanceId}/log-components") public RealtimeResponse<Object> logComponents(@PathVariable long id, @PathVariable long instanceId) { actors.requireActor(); return RealtimeResponse.success(runtime.logComponents(id, instanceId)); }
    @GetMapping("/{id}/instances/{instanceId}/logs") public RealtimeResponse<Object> logs(
            @PathVariable long id, @PathVariable long instanceId,
            @RequestParam(defaultValue = "all") String component,
            @RequestParam(required = false) String containerId,
            @RequestParam(defaultValue = "0") int cursor,
            @RequestParam(defaultValue = "1000") int limit) {
        actors.requireActor();
        return RealtimeResponse.success(runtime.logPage(id, instanceId, component, containerId, cursor, limit));
    }
    @PostMapping("/{id}/instances/{instanceId}/stop") public RealtimeResponse<Map<String, Object>> stopInstance(@PathVariable long id, @PathVariable long instanceId, @RequestBody(required = false) TaskActionRequest input) {
        TaskActionRequest action = input == null ? new TaskActionRequest() : input;
        Map<String, Object> instance = repository.requiredInstance(id, instanceId);
        if ("DEBUG".equalsIgnoreCase(text(instance.get("executionMode")))) action.setStopType("direct");
        else if (!"savepoint".equalsIgnoreCase(text(action.getStopType()))) throw new IllegalArgumentException("正式实例仅支持 savepoint 停止");
        return RealtimeResponse.success(runtime.stop(id, instanceId, action, actors.requireActor()));
    }

    /** 外部 MySQL/Paimon 校验在写事务开始前完成。 */
    private void validateSyncSubmission(com.yjn.sqlagent.realtime.model.SyncTaskRequest request,
            Long taskId) {
        long started = System.nanoTime();
        long stageStarted = System.nanoTime();
        validator.validate(request);
        long schemaValidationMs = elapsedMs(stageStarted);
        stageStarted = System.nanoTime();
        Map<String, Object> normalized = repository.validatePreview(request, taskId);
        long normalizationAndConflictMs = elapsedMs(stageStarted);
        stageStarted = System.nanoTime();
        Map<String, Object> persisted = Map.of();
        if (taskId != null) {
            Object value = repository.requiredTask(taskId).get("taskConfig");
            if (value instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked") Map<String, Object> config = (Map<String, Object>) value;
                persisted = config;
            }
        }
        long persistedConfigMs = elapsedMs(stageStarted);
        stageStarted = System.nanoTime();
        targetValidator.validateAddedTargets(persisted, normalized);
        LOG.info("sync_validation_total taskId={} tableCount={} schemaValidationMs={} normalizationAndConflictMs={} persistedConfigMs={} targetCheckMs={} totalMs={}",
                taskId, selectedTableCount(request), schemaValidationMs, normalizationAndConflictMs,
                persistedConfigMs, elapsedMs(stageStarted), elapsedMs(started));
    }

    private Map<String, Object> unifiedDetail(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>(source);
        @SuppressWarnings("unchecked") Map<String, Object> config = new LinkedHashMap<>((Map<String, Object>) result.get("taskConfig"));
        result.put("alarmConfig", Map.of("alarmType", text(config.get("alarmType")), "alarmGroup", text(config.get("alarmGroup"))));
        Map<String, Object> flink = new LinkedHashMap<>();
        flink.put("parallelism", config.get("parallelism")); flink.put("checkpointIntervalSeconds", config.get("checkpointInterval"));
        flink.put("taskManagerMemoryGb", memoryGb(config.get("taskManagerMemory"))); flink.put("jobManagerMemoryGb", memoryGb(config.get("jobManagerMemory")));
        flink.put("flinkConfOverrides", config.getOrDefault("flinkConfOverrides", Map.of())); result.put("flinkConf", flink);
        config.remove("alarmType"); config.remove("alarmGroup"); config.remove("parallelism");
        config.remove("checkpointInterval"); config.remove("taskManagerMemory");
        config.remove("jobManagerMemory"); config.remove("flinkConfOverrides");
        config.put("sourceServerId", result.get("sourceServerId")); config.put("sourceType", result.get("sourceType"));
        result.put("taskConfig", config); result.put("taskType", "sync");
        return result;
    }

    /** 历史实例可能保存 {task:{...}} 或旧同步配置；统一输出参考项目的 v1 TaskConfig。 */
    private Object unifiedInstanceConfig(long taskId, Object value) {
        if (!(value instanceof Map)) return value;
        @SuppressWarnings("unchecked") Map<String, Object> source = new LinkedHashMap<>((Map<String, Object>) value);
        if (source.containsKey("alarmConfig") && source.containsKey("flinkConf")) {
            if (text(source.get("sourceServerName")).isEmpty()) {
                source.put("sourceServerName", repository.sourceServerName(taskId));
            }
            return source;
        }
        Object nested = source.get("task");
        if (nested instanceof Map) {
            @SuppressWarnings("unchecked") Map<String, Object> task = new LinkedHashMap<>((Map<String, Object>) nested);
            source = task;
        }
        if (!source.containsKey("taskConfig")) return value;
        Map<String, Object> result = unifiedDetail(source);
        if (text(result.get("sourceServerName")).isEmpty()) {
            result.put("sourceServerName", repository.sourceServerName(taskId));
        }
        return result;
    }
    private long latestManagedActive(long taskId, String mode) {
        for (Map<String, Object> item : repository.instances(taskId)) if (Boolean.TRUE.equals(item.get("managed"))
                && mode.equalsIgnoreCase(text(item.get("executionMode")))
                && List.of("submitting", "running", "stopping", "restarting").contains(text(item.get("status")))) return ((Number) item.get("id")).longValue();
        throw new IllegalStateException("任务没有本平台管理的活动实例");
    }
    private String enableBlockedReason(long taskId) {
        String type = taskType(taskId);
        if (repository.hasActiveProductionInstance(taskId)) return "任务已有活动正式实例，不能重复启用";
        Long latestVersionId = repository.latestVersionId(taskId);
        Map<String, Object> latestDebug = null;
        for (Map<String, Object> instance : repository.instances(taskId)) {
            if ("DEBUG".equalsIgnoreCase(text(instance.get("executionMode")))
                    && Boolean.TRUE.equals(instance.get("managed"))) { latestDebug = instance; break; }
        }
        if (latestDebug == null) return "当前版本尚未调试，请先完成调试";
        Object versionId = latestDebug.get("versionId");
        if (latestVersionId == null || !(versionId instanceof Number)
                || latestVersionId.longValue() != ((Number) versionId).longValue()) return "任务配置已变化，请重新调试";
        String status = text(latestDebug.get("status")).toLowerCase();
        if ("running".equals(status) || "debug_success_running".equals(status)) return "调试已成功但仍在运行，请先停止调试";
        if (List.of("submitting", "stopping", "restarting").contains(status)) return "调试实例正在处理中，请稍后刷新";
        boolean successful = "killed_success".equals(status)
                || (!"sync".equals(type) && "finished".equals(status));
        if (!successful) return "最新调试实例未成功，请重新调试";
        // 同步表集合变化但没有 Savepoint 时仍允许打开启动弹窗，
        // 用户可显式选择异常实例 Checkpoint 或按时间戳重置消费点。
        return "";
    }
    private void validateRequiredRecovery(long taskId, TaskActionRequest action) {
        Map<String, Object> policy = repository.editPolicy(taskId);
        if (action.getSourceStartupTimestampMillis() != null) {
            if (!Boolean.TRUE.equals(policy.get("productionLocked"))) {
                throw new IllegalArgumentException("首次正式启动必须执行 initial 全量快照，不能按时间戳重置消费点");
            }
            return;
        }
        if ("checkpoint".equalsIgnoreCase(text(action.getStartType()))) {
            if (!isLatestFailedProductionCheckpoint(taskId, text(action.getStatePath()))) {
                throw new IllegalArgumentException("Checkpoint 仅支持恢复最近一次异常失败的正式实例，请刷新恢复状态后重新选择");
            }
            return;
        }
        String requiredPath = text(policy.get("requiredStatePath"));
        if (requiredPath.isEmpty()) {
            if (Boolean.TRUE.equals(policy.get("syncTableSetChanged"))) {
                throw new IllegalStateException("增删同步表后必须使用指定 Savepoint、Checkpoint 或按时间戳重置消费点启动");
            }
            return;
        }
        if (!"savepoint".equalsIgnoreCase(text(action.getStartType()))) {
            throw new IllegalArgumentException("存在可恢复的正式 Savepoint，必须从该 Savepoint 启动或显式重置消费点");
        }
        if (!requiredPath.equals(text(action.getStatePath()))) {
            throw new IllegalArgumentException("只能使用最近一次正式停止产生的 Savepoint：" + requiredPath);
        }
    }
    private boolean isLatestFailedProductionCheckpoint(long taskId, String path) {
        for (Map<String, Object> instance : repository.instances(taskId)) {
            if (!"PRODUCTION".equalsIgnoreCase(text(instance.get("executionMode")))) continue;
            if (!"failed".equalsIgnoreCase(text(instance.get("status")))) return false;
            String jobId = text(instance.get("jobId"));
            if (jobId.isEmpty()) return false;
            for (String part : path.replace('\\', '/').split("/")) if (jobId.equals(part)) return true;
            return false;
        }
        return false;
    }
    private Map<String, Object> capabilities() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("view", "edit", "delete", "debug", "enable", "stop", "instances",
                "stopDebugInstance", "operations", "startStop")) result.put(key, true);
        result.put("productionStopMode", "compatibility"); result.put("runtimeMode", "compatibility");
        return result;
    }
    private String taskType(long id) {
        return repository.taskType(id);
    }
    private void requireTaskType(String taskType) { if (!List.of("sync","compute","export").contains(text(taskType).toLowerCase())) throw new IllegalArgumentException("任务类型必须是 sync、compute 或 export"); }
    private String filterKeyword(Object value, String label) {
        String normalized = text(value);
        if (normalized.length() > MAX_FILTER_KEYWORD_LENGTH) {
            throw new IllegalArgumentException(label + "长度不能超过 " + MAX_FILTER_KEYWORD_LENGTH + " 个字符");
        }
        return normalized;
    }
    private void put(Map<String, String> target, String key, Object value) { if (value != null && !text(value).isEmpty()) target.put(key, text(value)); }
    private Double memoryGb(Object value) { String text = text(value).toLowerCase().replace("gb", "").replace("g", ""); try { return text.isEmpty() ? null : Double.valueOf(text); } catch (NumberFormatException ex) { return null; } }
    private int selectedTableCount(com.yjn.sqlagent.realtime.model.SyncTaskRequest request) {
        if (request == null || request.getTaskConfig() == null) return 0;
        Object cdcValue = request.getTaskConfig().get("cdcConfig");
        if (!(cdcValue instanceof Map<?, ?>)) return 0;
        Object tables = ((Map<?, ?>) cdcValue).get("selectedTables");
        if (!(tables instanceof Iterable<?>)) return text(tables).isEmpty() ? 0 : text(tables).split(",").length;
        int count = 0;
        for (Object ignored : (Iterable<?>) tables) count++;
        return count;
    }
    private long elapsedMs(long started) { return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
}
