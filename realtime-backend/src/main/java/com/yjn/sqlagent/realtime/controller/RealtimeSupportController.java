package com.yjn.sqlagent.realtime.controller;

import com.yjn.sqlagent.realtime.common.RealtimeActorProvider;
import com.yjn.sqlagent.realtime.common.RealtimeResponse;
import com.yjn.sqlagent.realtime.config.RealtimeProperties;
import com.yjn.sqlagent.realtime.repository.RealtimeSyncRepository;
import com.yjn.sqlagent.realtime.service.RealtimeAlertService;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/realtime", "/api"})
public class RealtimeSupportController {
    private final RealtimeSyncRepository repository; private final RealtimeActorProvider actors;
    private final RealtimeProperties properties;
    private final RealtimeAlertService alerts;
    public RealtimeSupportController(RealtimeSyncRepository repository, RealtimeActorProvider actors,
            RealtimeProperties properties, RealtimeAlertService alerts) {
        this.repository=repository; this.actors=actors; this.properties=properties; this.alerts=alerts;
    }
    @GetMapping("/sync-params") public RealtimeResponse<List<Map<String,Object>>> params() {
        actors.requireActor(); return RealtimeResponse.success(repository.params());
    }
    @GetMapping("/paimon/cdc-options") public RealtimeResponse<Map<String,Object>> cdcOptions() {
        actors.requireActor();
        List<Map<String, Object>> tablePrefixes = repository.domains().stream().map(domain -> Map.<String, Object>of(
                "label", domain.get("name"), "value", domain.get("code")))
                .collect(java.util.stream.Collectors.toList());
        return RealtimeResponse.success(Map.of(
                "targetDatabase", properties.getTargetDatabase(),
                "debugTargetDatabase", properties.getPaimonDebugTargetDatabase(),
                "defaultProjectId", properties.getDefaultProjectId(),
                "tablePrefixes", tablePrefixes));
    }
    @GetMapping("/alerts") public RealtimeResponse<List<Map<String,Object>>> alerts(
            @RequestParam(required=false) Long taskId) {
        actors.requireActor(); return RealtimeResponse.success(repository.alerts(taskId));
    }
    @PostMapping("/alerts/{id}/acknowledge") public RealtimeResponse<Boolean> acknowledge(@PathVariable long id) {
        alerts.acknowledge(id, actors.requireActor()); return RealtimeResponse.success(true);
    }
    @GetMapping("/alerts/page") public RealtimeResponse<Map<String,Object>> alertPage(
            @RequestParam Map<String,String> query) {
        actors.requireActor(); return RealtimeResponse.success(alerts.page(query));
    }
    @GetMapping("/alerts/{id}") public RealtimeResponse<Map<String,Object>> alertDetail(@PathVariable long id) {
        actors.requireActor(); return RealtimeResponse.success(alerts.detail(id));
    }
    @PostMapping("/alerts/{id}/mute") public RealtimeResponse<Boolean> mute(@PathVariable long id,
            @RequestBody Map<String,Object> body) {
        String until=String.valueOf(body.getOrDefault("mutedUntil", ""));
        LocalDateTime mutedUntil=until.endsWith("Z")||until.matches(".*[+-]\\d\\d:\\d\\d$")
                ?OffsetDateTime.parse(until).toLocalDateTime():LocalDateTime.parse(until);
        alerts.mute(id,mutedUntil,actors.requireActor());return RealtimeResponse.success(true);
    }
    @PostMapping("/alerts/{id}/unmute") public RealtimeResponse<Boolean> unmute(@PathVariable long id) {
        actors.requireActor();alerts.unmute(id);return RealtimeResponse.success(true);
    }
    @GetMapping("/alert-rules") public RealtimeResponse<List<Map<String,Object>>> alertRules() {
        actors.requireActor();return RealtimeResponse.success(alerts.rules());
    }
    @PostMapping("/alert-rules/{id}") public RealtimeResponse<Map<String,Object>> updateAlertRule(
            @PathVariable long id,@RequestBody Map<String,Object> body) {
        actors.requireActor();return RealtimeResponse.success(alerts.updateRule(id,new LinkedHashMap<>(body)));
    }
    @GetMapping("/task-change-logs") public RealtimeResponse<List<Map<String,Object>>> changes(
            @RequestParam(required=false) Long taskId) {
        actors.requireActor(); return RealtimeResponse.success(repository.changes(taskId));
    }
    @GetMapping("/task-change-logs/{id}/detail") public RealtimeResponse<Map<String,Object>> detail(@PathVariable long id) {
        actors.requireActor(); return RealtimeResponse.success(repository.changeDetail(id));
    }
}
