package com.yjn.sqlagent.realtime.controller;

import com.yjn.sqlagent.realtime.common.RealtimeActorProvider;
import com.yjn.sqlagent.realtime.common.RealtimeResponse;
import com.yjn.sqlagent.realtime.model.ServerRequest;
import com.yjn.sqlagent.realtime.repository.RealtimeSyncRepository;
import com.yjn.sqlagent.realtime.service.RealtimeServerService;
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

@RestController
@RequestMapping({"/api/servers", "/api/realtime/servers"})
public class RealtimeServerController {
    private final RealtimeSyncRepository repository; private final RealtimeServerService service;
    private final RealtimeActorProvider actors;
    public RealtimeServerController(RealtimeSyncRepository repository, RealtimeServerService service,
            RealtimeActorProvider actors) { this.repository=repository; this.service=service; this.actors=actors; }

    @GetMapping public RealtimeResponse<List<Map<String,Object>>> list() {
        actors.requireActor(); return RealtimeResponse.success(repository.servers().stream().map(this::serverView)
                .collect(java.util.stream.Collectors.toList()));
    }
    @PostMapping public RealtimeResponse<Map<String,Object>> create(@Valid @RequestBody ServerRequest request) {
        long id=repository.createServer(request,actors.requireActor()); return RealtimeResponse.success(serverView(repository.requiredServer(id,false)));
    }
    @PutMapping("/{id}") public RealtimeResponse<Map<String,Object>> update(@PathVariable long id,
            @Valid @RequestBody ServerRequest request) {
        repository.updateServer(id,request,actors.requireActor()); return RealtimeResponse.success(serverView(repository.requiredServer(id,false)));
    }
    @PostMapping("/{id}/update") public RealtimeResponse<Map<String,Object>> updateV1(@PathVariable long id,
            @Valid @RequestBody ServerRequest request) { return update(id, request); }
    @DeleteMapping("/{id}") public RealtimeResponse<Boolean> delete(@PathVariable long id) {
        actors.requireActor(); repository.deleteServer(id); return RealtimeResponse.success(true);
    }
    @PostMapping("/{id}/delete") public RealtimeResponse<Boolean> deleteV1(@PathVariable long id) { return delete(id); }
    @PostMapping("/test-connection") public RealtimeResponse<Map<String,Object>> test(@Valid @RequestBody ServerRequest request) {
        actors.requireActor(); return RealtimeResponse.success(service.test(request));
    }
    @PostMapping("/{id}/test-connection") public RealtimeResponse<Map<String,Object>> test(@PathVariable long id) {
        actors.requireActor(); return RealtimeResponse.success(service.test(id));
    }
    @GetMapping("/{id}/mysql/tables") public RealtimeResponse<List<String>> tables(@PathVariable long id,
            @RequestParam String database) {
        actors.requireActor(); requireConfiguredDatabase(id, database); return RealtimeResponse.success(service.tables(id));
    }
    @GetMapping("/{id}/mysql/table-schema") public RealtimeResponse<Map<String,Object>> schema(@PathVariable long id,
            @RequestParam String database, @RequestParam String table) {
        actors.requireActor(); requireConfiguredDatabase(id, database); return RealtimeResponse.success(service.schema(id,table));
    }
    @GetMapping("/{id}/mysql/table-ddl") public RealtimeResponse<Map<String,Object>> ddl(@PathVariable long id,
            @RequestParam String table) {
        actors.requireActor(); return RealtimeResponse.success(service.ddl(id, table));
    }
    @GetMapping("/{id}/mysql/common-columns") public RealtimeResponse<List<Map<String,Object>>> commonColumns(@PathVariable long id,
            @RequestParam String database, @RequestParam List<String> tables) {
        actors.requireActor(); requireConfiguredDatabase(id, database); return RealtimeResponse.success(service.commonColumns(id,tables));
    }

    private Map<String, Object> serverView(Map<String, Object> source) {
        Map<String, Object> result = new java.util.LinkedHashMap<>(source);
        result.put("database", result.remove("databaseName"));
        return result;
    }
    private void requireConfiguredDatabase(long id, String database) {
        String configured = String.valueOf(repository.requiredServer(id, false).get("databaseName")).trim();
        if (database == null || !configured.equals(database.trim())) {
            throw new IllegalArgumentException("数据库必须与所选 Server 配置一致");
        }
    }
}
