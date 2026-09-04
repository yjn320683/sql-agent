package com.yjn.sqlagent.realtime.controller;

import com.yjn.sqlagent.realtime.common.RealtimeActorProvider;
import com.yjn.sqlagent.realtime.common.RealtimeResponse;
import com.yjn.sqlagent.realtime.repository.RealtimeTableRepository;
import com.yjn.sqlagent.realtime.service.RealtimeTableService;
import java.util.LinkedHashMap;
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
@RequestMapping("/api/realtime/tables")
public class RealtimeTableController {
    private final RealtimeTableRepository repository; private final RealtimeTableService service;
    private final RealtimeActorProvider actors;
    public RealtimeTableController(RealtimeTableRepository repository, RealtimeTableService service, RealtimeActorProvider actors) {
        this.repository=repository; this.service=service; this.actors=actors;
    }
    @GetMapping public RealtimeResponse<Map<String,Object>> page(@RequestParam Map<String,String> query) { actors.requireActor(); return RealtimeResponse.success(repository.page(query)); }
    @GetMapping("/available") public RealtimeResponse<List<Map<String,Object>>> available() { actors.requireActor(); return RealtimeResponse.success(repository.available()); }
    @GetMapping("/databases") public RealtimeResponse<List<String>> databases() { actors.requireActor(); return RealtimeResponse.success(service.databases()); }
    @PostMapping public RealtimeResponse<Map<String,Object>> create(@RequestBody Map<String,Object> request) { return RealtimeResponse.success(service.create(new LinkedHashMap<>(request),actors.requireActor())); }
    @GetMapping("/{id}") public RealtimeResponse<Map<String,Object>> detail(@PathVariable long id) { actors.requireActor(); return RealtimeResponse.success(repository.required(id)); }
    @GetMapping("/{id}/dependencies") public RealtimeResponse<List<Map<String,Object>>> dependencies(@PathVariable long id) { actors.requireActor(); return RealtimeResponse.success(repository.dependencies(id)); }
    @PostMapping("/{id}/refresh") public RealtimeResponse<Map<String,Object>> refresh(@PathVariable long id) { return RealtimeResponse.success(service.refresh(id,actors.requireActor())); }
    @PostMapping("/{id}/safe-update") public RealtimeResponse<Map<String,Object>> update(@PathVariable long id,@RequestBody Map<String,Object> request) { return RealtimeResponse.success(service.safeUpdate(id,new LinkedHashMap<>(request),actors.requireActor())); }
}
