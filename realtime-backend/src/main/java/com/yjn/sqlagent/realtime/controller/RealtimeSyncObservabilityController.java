package com.yjn.sqlagent.realtime.controller;

import com.yjn.sqlagent.realtime.common.RealtimeActorProvider;
import com.yjn.sqlagent.realtime.common.RealtimeResponse;
import com.yjn.sqlagent.realtime.service.RealtimeSyncObservabilityService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/realtime/sync-tasks/{taskId}")
public class RealtimeSyncObservabilityController {
    private final RealtimeSyncObservabilityService service;private final RealtimeActorProvider actors;
    public RealtimeSyncObservabilityController(RealtimeSyncObservabilityService service,RealtimeActorProvider actors){this.service=service;this.actors=actors;}
    @GetMapping("/instances/{instanceId}/sync-progress") public RealtimeResponse<Map<String,Object>>progress(@PathVariable long taskId,@PathVariable long instanceId,@RequestParam(defaultValue="true")boolean refresh){actors.requireActor();return RealtimeResponse.success(service.progress(taskId,instanceId,refresh));}
    @GetMapping("/dirty-records") public RealtimeResponse<Map<String,Object>>dirty(@PathVariable long taskId,@RequestParam(defaultValue="true")boolean unresolvedOnly,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize){actors.requireActor();return RealtimeResponse.success(service.dirtyPage(taskId,unresolvedOnly,page,pageSize));}
    @PostMapping("/instances/{instanceId}/dirty-records") public RealtimeResponse<Long>addDirty(@PathVariable long taskId,@PathVariable long instanceId,@RequestBody Map<String,Object>body){actors.requireActor();return RealtimeResponse.success(service.addDirty(taskId,instanceId,body));}
    @PostMapping("/dirty-records/{recordId}/resolve") public RealtimeResponse<Boolean>resolveDirty(@PathVariable long taskId,@PathVariable long recordId){service.resolveDirty(taskId,recordId,actors.requireActor());return RealtimeResponse.success(true);}
    @GetMapping("/schema-changes") public RealtimeResponse<List<Map<String,Object>>>schemaChanges(@PathVariable long taskId,@RequestParam(defaultValue="false")boolean refresh){actors.requireActor();return RealtimeResponse.success(refresh?service.detectSchemaChanges(taskId):service.schemaChanges(taskId));}
    @PostMapping("/schema-changes/{eventId}/apply") public RealtimeResponse<Map<String,Object>>apply(@PathVariable long taskId,@PathVariable long eventId){return RealtimeResponse.success(service.applySchemaChange(taskId,eventId,actors.requireActor()));}
}
