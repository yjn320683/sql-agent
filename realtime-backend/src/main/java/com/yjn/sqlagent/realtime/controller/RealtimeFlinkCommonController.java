package com.yjn.sqlagent.realtime.controller;

import com.yjn.sqlagent.realtime.common.RealtimeActorProvider;
import com.yjn.sqlagent.realtime.common.RealtimeResponse;
import com.yjn.sqlagent.realtime.service.RealtimeRuntimeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 与参考平台统一的 Flink 公共状态文件查询接口。 */
@RestController
@RequestMapping("/v1/api/flink-common")
public class RealtimeFlinkCommonController {
    private final RealtimeRuntimeService runtime;
    private final RealtimeActorProvider actors;

    public RealtimeFlinkCommonController(RealtimeRuntimeService runtime, RealtimeActorProvider actors) {
        this.runtime = runtime;
        this.actors = actors;
    }

    @GetMapping("/listcheckpoint")
    public RealtimeResponse<Object> listCheckpoint(@RequestParam long taskId) {
        actors.requireActor();
        return RealtimeResponse.success(runtime.stateHistory(taskId, "checkpoint"));
    }

    @GetMapping("/listsavepoint")
    public RealtimeResponse<Object> listSavepoint(@RequestParam long taskId) {
        actors.requireActor();
        return RealtimeResponse.success(runtime.stateHistory(taskId, "savepoint"));
    }
}
