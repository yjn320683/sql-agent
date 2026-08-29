package com.yjn.sqlagent.realtime.controller;

import com.yjn.sqlagent.realtime.common.RealtimeActorProvider;
import com.yjn.sqlagent.realtime.common.RealtimeResponse;
import com.yjn.sqlagent.realtime.model.SyncTaskRequest;
import com.yjn.sqlagent.realtime.model.TaskActionRequest;
import com.yjn.sqlagent.realtime.repository.RealtimeSyncRepository;
import com.yjn.sqlagent.realtime.service.RealtimeRuntimeService;
import com.yjn.sqlagent.realtime.service.RealtimeServerService;
import com.yjn.sqlagent.realtime.service.RealtimeSyncConfigValidator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/realtime/sync-tasks")
public class RealtimeSyncTaskController {
    private final RealtimeSyncRepository repository;
    private final RealtimeRuntimeService runtime;
    private final RealtimeServerService servers;
    private final RealtimeSyncConfigValidator validator;
    private final RealtimeActorProvider actors;

    public RealtimeSyncTaskController(RealtimeSyncRepository repository, RealtimeRuntimeService runtime,
            RealtimeServerService servers, RealtimeSyncConfigValidator validator, RealtimeActorProvider actors) {
        this.repository = repository; this.runtime = runtime; this.servers = servers;
        this.validator = validator; this.actors = actors;
    }

    @GetMapping
    public RealtimeResponse<Map<String, Object>> page(@RequestParam Map<String, String> query) {
        actors.requireActor(); return RealtimeResponse.success(repository.taskPage(query));
    }

    @GetMapping("/{id}")
    public RealtimeResponse<Map<String, Object>> detail(@PathVariable long id) {
        actors.requireActor(); return RealtimeResponse.success(repository.requiredTask(id));
    }

    @PostMapping
    public RealtimeResponse<Map<String, Object>> create(@Valid @RequestBody SyncTaskRequest request) {
        String actor = actors.requireActor();
        validator.validate(request);
        long id = repository.createTask(request, actor);
        return RealtimeResponse.success(repository.requiredTask(id));
    }

    @PutMapping("/{id}")
    public RealtimeResponse<Map<String, Object>> update(@PathVariable long id,
            @Valid @RequestBody SyncTaskRequest request) {
        String actor = actors.requireActor();
        validator.validate(request);
        repository.updateTask(id, request, actor);
        return RealtimeResponse.success(repository.requiredTask(id));
    }

    @DeleteMapping("/{id}")
    public RealtimeResponse<Boolean> delete(@PathVariable long id) {
        repository.deleteTask(id, actors.requireActor()); return RealtimeResponse.success(true);
    }

    @PostMapping("/command-preview")
    public RealtimeResponse<Map<String, Object>> previewRequest(@Valid @RequestBody SyncTaskRequest request,
            @RequestParam(required = false) Long excludeTaskId) {
        actors.requireActor(); validator.validate(request);
        return RealtimeResponse.success(runtime.previewRequest(request, excludeTaskId));
    }

    @GetMapping("/{id}/command-preview")
    public RealtimeResponse<Map<String, Object>> preview(@PathVariable long id) {
        actors.requireActor(); validator.validateTask(repository.requiredTask(id));
        return RealtimeResponse.success(runtime.previewSaved(id, new TaskActionRequest(), false));
    }

    @PostMapping("/{id}/debug-command-preview")
    public RealtimeResponse<Map<String, Object>> debugPreview(@PathVariable long id,
            @RequestBody(required = false) TaskActionRequest request) {
        actors.requireActor();
        validator.validateTask(repository.requiredTask(id));
        return RealtimeResponse.success(runtime.previewSaved(id, request == null ? new TaskActionRequest() : request, true));
    }

    @PostMapping("/{id}/start")
    public RealtimeResponse<Map<String, Object>> start(@PathVariable long id,
            @RequestBody(required = false) TaskActionRequest request) {
        String actor = actors.requireActor();
        validator.validateTask(repository.requiredTask(id));
        return RealtimeResponse.success(runtime.start(id, request == null ? new TaskActionRequest() : request,
                actor, false));
    }

    @PostMapping("/{id}/debug")
    public RealtimeResponse<Map<String, Object>> debug(@PathVariable long id,
            @RequestBody(required = false) TaskActionRequest request) {
        String actor = actors.requireActor();
        validator.validateTask(repository.requiredTask(id));
        return RealtimeResponse.success(runtime.start(id, request == null ? new TaskActionRequest() : request,
                actor, true));
    }

    @PostMapping("/{id}/stop")
    public RealtimeResponse<Map<String, Object>> stopLatest(@PathVariable long id,
            @RequestBody(required = false) TaskActionRequest request) {
        long instanceId = latestManagedActive(id);
        return RealtimeResponse.success(runtime.stop(id, instanceId,
                request == null ? new TaskActionRequest() : request, actors.requireActor()));
    }

    @PostMapping("/{id}/refresh-status")
    public RealtimeResponse<Map<String, Object>> refreshLatest(@PathVariable long id) {
        String actor = actors.requireActor();
        return RealtimeResponse.success(runtime.refresh(id, latestManagedActive(id), actor));
    }

    @GetMapping("/{id}/table-mappings")
    public RealtimeResponse<List<Map<String, Object>>> mappings(@PathVariable long id) {
        actors.requireActor(); return RealtimeResponse.success(repository.mappings(id));
    }

    @GetMapping("/source-tables")
    public RealtimeResponse<List<Map<String, Object>>> sourceTables(@RequestParam long serverId,
            @RequestParam(required = false) Long excludeTaskId) {
        actors.requireActor();
        return RealtimeResponse.success(repository.sourceTableOptions(serverId, servers.tables(serverId), excludeTaskId));
    }

    @GetMapping("/{id}/state-history")
    public RealtimeResponse<Object> stateHistory(@PathVariable long id,
            @RequestParam(required = false) String type) {
        actors.requireActor(); return RealtimeResponse.success(runtime.stateHistory(id, type));
    }

    @GetMapping("/{id}/versions")
    public RealtimeResponse<List<Map<String, Object>>> versions(@PathVariable long id) {
        actors.requireActor(); return RealtimeResponse.success(repository.versions(id));
    }

    @GetMapping("/{id}/versions/{versionId}/config")
    public RealtimeResponse<Map<String, Object>> versionConfig(@PathVariable long id, @PathVariable long versionId) {
        actors.requireActor(); return RealtimeResponse.success(repository.versionConfig(id, versionId));
    }

    @GetMapping("/{id}/instances")
    public RealtimeResponse<List<Map<String, Object>>> instances(@PathVariable long id) {
        actors.requireActor(); return RealtimeResponse.success(repository.instances(id));
    }

    @GetMapping("/{id}/instances/{instanceId}/config")
    public RealtimeResponse<Object> instanceConfig(@PathVariable long id, @PathVariable long instanceId) {
        actors.requireActor(); return RealtimeResponse.success(repository.requiredInstance(id, instanceId).get("config"));
    }

    @GetMapping("/{id}/instances/{instanceId}/startup-log")
    public RealtimeResponse<Map<String, Object>> startupLog(@PathVariable long id, @PathVariable long instanceId) {
        actors.requireActor(); Map<String, Object> value = repository.requiredInstance(id, instanceId);
        return RealtimeResponse.success(Map.of("startupLog", text(value.get("startupLog"))));
    }

    @GetMapping("/{id}/instances/{instanceId}/runtime-log")
    public RealtimeResponse<Object> runtimeLog(@PathVariable long id, @PathVariable long instanceId,
            @RequestParam(required = false) String component,
            @RequestParam(required = false) String taskManagerId) {
        actors.requireActor();
        String selected = "taskmanager".equalsIgnoreCase(component) && !text(taskManagerId).isEmpty()
                ? "taskmanager:" + taskManagerId : component;
        return RealtimeResponse.success(runtime.logs(id, instanceId, selected, null));
    }

    @PostMapping("/{id}/instances/{instanceId}/stop")
    public RealtimeResponse<Map<String, Object>> stop(@PathVariable long id, @PathVariable long instanceId,
            @RequestBody(required = false) TaskActionRequest request) {
        return RealtimeResponse.success(runtime.stop(id, instanceId, request == null ? new TaskActionRequest() : request,
                actors.requireActor()));
    }

    @PostMapping("/{id}/instances/{instanceId}/refresh-status")
    public RealtimeResponse<Map<String, Object>> refresh(@PathVariable long id, @PathVariable long instanceId) {
        return RealtimeResponse.success(runtime.refresh(id, instanceId, actors.requireActor()));
    }

    @GetMapping("/{id}/instances/{instanceId}/runtime")
    public RealtimeResponse<Object> runtime(@PathVariable long id, @PathVariable long instanceId) {
        actors.requireActor(); return RealtimeResponse.success(runtime.runtime(id, instanceId));
    }

    @GetMapping("/{id}/instances/{instanceId}/resources")
    public RealtimeResponse<Object> resources(@PathVariable long id, @PathVariable long instanceId) {
        actors.requireActor(); return RealtimeResponse.success(runtime.resources(id, instanceId));
    }

    @GetMapping("/{id}/instances/{instanceId}/checkpoints")
    public RealtimeResponse<Object> checkpoints(@PathVariable long id, @PathVariable long instanceId) {
        actors.requireActor(); return RealtimeResponse.success(runtime.checkpoints(id, instanceId));
    }

    @GetMapping("/{id}/instances/{instanceId}/log-components")
    public RealtimeResponse<Object> logComponents(@PathVariable long id, @PathVariable long instanceId) {
        actors.requireActor(); return RealtimeResponse.success(runtime.logComponents(id, instanceId));
    }

    @GetMapping("/{id}/instances/{instanceId}/logs")
    public RealtimeResponse<Object> logs(@PathVariable long id, @PathVariable long instanceId,
            @RequestParam(required = false) String component, @RequestParam(required = false) String file) {
        actors.requireActor(); return RealtimeResponse.success(runtime.logs(id, instanceId, component, file));
    }

    @GetMapping("/{id}/instances/{instanceId}/logs/download")
    public ResponseEntity<byte[]> downloadLog(@PathVariable long id, @PathVariable long instanceId,
            @RequestParam(required = false) String component, @RequestParam(required = false) String file) {
        actors.requireActor();
        byte[] content = runtime.downloadLog(id, instanceId, component, file).getBytes(StandardCharsets.UTF_8);
        String filename = text(file).isEmpty() ? "instance-" + instanceId + ".log" : file;
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
                .body(content);
    }

    private long latestManagedActive(long taskId) {
        for (Map<String, Object> item : repository.instances(taskId)) {
            if (Boolean.TRUE.equals(item.get("managed")) && "PRODUCTION".equals(item.get("executionMode"))
                    && List.of("submitting", "running", "stopping", "restarting")
                    .contains(String.valueOf(item.get("status")))) return ((Number) item.get("id")).longValue();
        }
        throw new IllegalStateException("任务没有本平台管理的活动实例");
    }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
