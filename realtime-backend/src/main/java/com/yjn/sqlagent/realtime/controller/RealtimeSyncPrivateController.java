package com.yjn.sqlagent.realtime.controller;

import com.yjn.sqlagent.realtime.common.RealtimeActorProvider;
import com.yjn.sqlagent.realtime.common.RealtimeResponse;
import com.yjn.sqlagent.realtime.model.TaskActionRequest;
import com.yjn.sqlagent.realtime.repository.RealtimeSyncRepository;
import com.yjn.sqlagent.realtime.service.RealtimeRuntimeService;
import com.yjn.sqlagent.realtime.service.RealtimeServerService;
import com.yjn.sqlagent.realtime.service.RealtimeSyncConfigValidator;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 与参考项目一致的同步任务私有接口；公共生命周期统一走 /v1/api/tasks。 */
@RestController
@RequestMapping("/api/tasks")
public class RealtimeSyncPrivateController {
    private final RealtimeSyncRepository repository;
    private final RealtimeRuntimeService runtime;
    private final RealtimeServerService servers;
    private final RealtimeSyncConfigValidator validator;
    private final RealtimeActorProvider actors;

    public RealtimeSyncPrivateController(RealtimeSyncRepository repository, RealtimeRuntimeService runtime,
            RealtimeServerService servers, RealtimeSyncConfigValidator validator, RealtimeActorProvider actors) {
        this.repository = repository;
        this.runtime = runtime;
        this.servers = servers;
        this.validator = validator;
        this.actors = actors;
    }

    @GetMapping("/sync/source-tables")
    public RealtimeResponse<List<Map<String, Object>>> sourceTables(
            @RequestParam long sourceServerId, @RequestParam String database,
            @RequestParam(required = false) Long excludeTaskId) {
        actors.requireActor();
        Map<String, Object> server = repository.requiredServer(sourceServerId, false);
        if (!text(server.get("databaseName")).equals(database.trim())) {
            throw new IllegalArgumentException("源库必须与所选 Server 配置一致");
        }
        return RealtimeResponse.success(repository.sourceTableOptions(
                sourceServerId, servers.tables(sourceServerId), excludeTaskId));
    }

    @PostMapping("/{id}/debug-command-preview")
    public RealtimeResponse<Map<String, Object>> debugCommandPreview(@PathVariable long id,
            @RequestBody(required = false) TaskActionRequest request) {
        actors.requireActor();
        validator.validateTask(repository.requiredTask(id));
        return RealtimeResponse.success(runtime.previewSaved(
                id, request == null ? new TaskActionRequest() : request, true));
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
