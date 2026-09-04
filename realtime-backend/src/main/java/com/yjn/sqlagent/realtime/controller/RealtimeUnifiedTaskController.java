package com.yjn.sqlagent.realtime.controller;

import com.yjn.sqlagent.realtime.common.RealtimeActorProvider;
import com.yjn.sqlagent.realtime.common.RealtimeResponse;
import com.yjn.sqlagent.realtime.model.TaskActionRequest;
import com.yjn.sqlagent.realtime.model.UnifiedTaskRequest;
import com.yjn.sqlagent.realtime.repository.RealtimeSyncRepository;
import com.yjn.sqlagent.realtime.service.RealtimeRuntimeService;
import com.yjn.sqlagent.realtime.service.RealtimeSyncConfigValidator;
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

/** 对齐参考项目 v1 统一任务接口，当前仅注册同步任务实现。 */
@RestController
@RequestMapping("/v1/api/tasks")
public class RealtimeUnifiedTaskController {
    private static final int MAX_FILTER_KEYWORD_LENGTH = 200;
    private final RealtimeSyncRepository repository;
    private final RealtimeRuntimeService runtime;
    private final RealtimeSyncConfigValidator validator;
    private final RealtimeActorProvider actors;

    public RealtimeUnifiedTaskController(RealtimeSyncRepository repository, RealtimeRuntimeService runtime,
            RealtimeSyncConfigValidator validator, RealtimeActorProvider actors) {
        this.repository = repository; this.runtime = runtime; this.validator = validator; this.actors = actors;
    }

    @GetMapping("/params")
    public RealtimeResponse<List<Map<String, Object>>> params(@RequestParam String taskType) {
        requireSync(taskType); actors.requireActor(); return RealtimeResponse.success(repository.params());
    }

    @PostMapping("/page")
    public RealtimeResponse<Map<String, Object>> page(@RequestBody Map<String, Object> input) {
        requireSync(text(input.get("taskType"))); actors.requireActor();
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
        String actor = actors.requireActor(); validator.validate(input.toSyncTaskRequest());
        return RealtimeResponse.success(repository.createTask(input.toSyncTaskRequest(), actor));
    }

    @PostMapping("/{id}/update")
    public RealtimeResponse<Long> update(@PathVariable long id, @Valid @RequestBody UnifiedTaskRequest input) {
        String actor = actors.requireActor(); validator.validate(input.toSyncTaskRequest());
        repository.updateTask(id, input.toSyncTaskRequest(), actor); return RealtimeResponse.success(id);
    }

    @PostMapping("/{id}/delete")
    public RealtimeResponse<Void> delete(@PathVariable long id) {
        repository.deleteTask(id, actors.requireActor()); return RealtimeResponse.success(null);
    }

    @GetMapping("/{id}/detail")
    public RealtimeResponse<Map<String, Object>> detail(@PathVariable long id) {
        actors.requireActor(); return RealtimeResponse.success(unifiedDetail(repository.requiredTask(id)));
    }

    @PostMapping("/command-preview")
    public RealtimeResponse<Map<String, Object>> preview(@Valid @RequestBody UnifiedTaskRequest input,
            @RequestParam(required = false) Long excludeTaskId) {
        actors.requireActor(); validator.validate(input.toSyncTaskRequest());
        return RealtimeResponse.success(runtime.previewRequest(input.toSyncTaskRequest(), excludeTaskId));
    }

    @GetMapping("/{id}/command-preview")
    public RealtimeResponse<Map<String, Object>> preview(@PathVariable long id) {
        actors.requireActor(); validator.validateTask(repository.requiredTask(id));
        return RealtimeResponse.success(runtime.previewSaved(id, new TaskActionRequest(), false));
    }

    @PostMapping("/debug")
    public RealtimeResponse<Map<String, Object>> debug(@Valid @RequestBody UnifiedTaskRequest input) {
        if (input.getTaskId() == null) throw new IllegalArgumentException("调试任务 ID 不能为空");
        String actor = actors.requireActor(); validator.validateTask(repository.requiredTask(input.getTaskId()));
        return RealtimeResponse.success(runtime.start(input.getTaskId(), input.toActionRequest(), actor, true));
    }

    @PostMapping("/{id}/can-enable")
    public RealtimeResponse<Map<String, Object>> canEnable(@PathVariable long id) {
        actors.requireActor(); validator.validateTask(repository.requiredTask(id));
        String reason = enableBlockedReason(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("canEnable", reason.isEmpty());
        result.put("reason", reason.isEmpty() ? null : reason);
        return RealtimeResponse.success(result);
    }

    @PostMapping("/{id}/enable")
    public RealtimeResponse<Map<String, Object>> enable(@PathVariable long id,
            @RequestBody(required = false) TaskActionRequest input) {
        String actor = actors.requireActor(); validator.validateTask(repository.requiredTask(id));
        TaskActionRequest action = input == null ? new TaskActionRequest() : input;
        String reason = enableBlockedReason(id);
        if (!reason.isEmpty()) throw new IllegalStateException(reason);
        validateRequiredRecovery(id, action);
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
                source.put("sourceServerName", repository.requiredTask(taskId).get("sourceServerName"));
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
            Map<String, Object> current = repository.requiredTask(taskId);
            result.put("sourceServerName", current.get("sourceServerName"));
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
        if (!"killed_success".equals(status)) return "最新调试实例未成功，请重新调试";
        Map<String, Object> policy = repository.editPolicy(taskId);
        if (Boolean.TRUE.equals(policy.get("syncTableSetChanged"))
                && text(policy.get("requiredStatePath")).isEmpty()) {
            return "同步表集合已变化，但没有可用的正式 Savepoint，请先恢复原配置运行并通过 Savepoint 停止";
        }
        return "";
    }
    private void validateRequiredRecovery(long taskId, TaskActionRequest action) {
        Map<String, Object> policy = repository.editPolicy(taskId);
        if (!Boolean.TRUE.equals(policy.get("syncTableSetChanged"))) return;
        String requiredPath = text(policy.get("requiredStatePath"));
        if (!"savepoint".equalsIgnoreCase(text(action.getStartType()))) {
            throw new IllegalArgumentException("增删同步表后必须从最近一次正式停止产生的 Savepoint 启动");
        }
        if (!requiredPath.equals(text(action.getStatePath()))) {
            throw new IllegalArgumentException("增删同步表后只能使用最近一次正式停止产生的 Savepoint：" + requiredPath);
        }
    }
    private Map<String, Object> capabilities() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("view", "edit", "delete", "debug", "enable", "stop", "instances",
                "stopDebugInstance", "operations", "startStop")) result.put(key, true);
        result.put("productionStopMode", "compatibility"); result.put("runtimeMode", "compatibility");
        return result;
    }
    private void requireSync(String taskType) { if (!"sync".equalsIgnoreCase(taskType)) throw new IllegalArgumentException("当前统一接口仅支持 sync"); }
    private String filterKeyword(Object value, String label) {
        String normalized = text(value);
        if (normalized.length() > MAX_FILTER_KEYWORD_LENGTH) {
            throw new IllegalArgumentException(label + "长度不能超过 " + MAX_FILTER_KEYWORD_LENGTH + " 个字符");
        }
        return normalized;
    }
    private void put(Map<String, String> target, String key, Object value) { if (value != null && !text(value).isEmpty()) target.put(key, text(value)); }
    private Double memoryGb(Object value) { String text = text(value).toLowerCase().replace("gb", "").replace("g", ""); try { return text.isEmpty() ? null : Double.valueOf(text); } catch (NumberFormatException ex) { return null; } }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
}
