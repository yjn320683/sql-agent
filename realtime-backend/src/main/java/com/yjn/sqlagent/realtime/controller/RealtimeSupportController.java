package com.yjn.sqlagent.realtime.controller;

import com.yjn.sqlagent.realtime.common.RealtimeActorProvider;
import com.yjn.sqlagent.realtime.common.RealtimeResponse;
import com.yjn.sqlagent.realtime.config.RealtimeProperties;
import com.yjn.sqlagent.realtime.repository.RealtimeSyncRepository;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/realtime")
public class RealtimeSupportController {
    private final RealtimeSyncRepository repository; private final RealtimeActorProvider actors;
    private final RealtimeProperties properties;
    public RealtimeSupportController(RealtimeSyncRepository repository, RealtimeActorProvider actors,
            RealtimeProperties properties) {
        this.repository=repository; this.actors=actors; this.properties=properties;
    }
    @GetMapping("/sync-params") public RealtimeResponse<List<Map<String,Object>>> params() {
        actors.requireActor(); return RealtimeResponse.success(repository.params());
    }
    @GetMapping("/paimon/cdc-options") public RealtimeResponse<Map<String,Object>> cdcOptions() {
        actors.requireActor(); return RealtimeResponse.success(Map.of(
                "targetDatabase", properties.getTargetDatabase(), "domains", repository.domains()));
    }
    @GetMapping("/alerts") public RealtimeResponse<List<Map<String,Object>>> alerts() {
        actors.requireActor(); return RealtimeResponse.success(repository.alerts());
    }
    @PostMapping("/alerts/{id}/acknowledge") public RealtimeResponse<Boolean> acknowledge(@PathVariable long id) {
        actors.requireActor(); repository.acknowledgeAlert(id); return RealtimeResponse.success(true);
    }
    @GetMapping("/task-change-logs") public RealtimeResponse<List<Map<String,Object>>> changes(
            @RequestParam(required=false) Long taskId) {
        actors.requireActor(); return RealtimeResponse.success(repository.changes(taskId));
    }
    @GetMapping("/task-change-logs/{id}/detail") public RealtimeResponse<Map<String,Object>> detail(@PathVariable long id) {
        actors.requireActor(); return RealtimeResponse.success(repository.changeDetail(id));
    }
}
