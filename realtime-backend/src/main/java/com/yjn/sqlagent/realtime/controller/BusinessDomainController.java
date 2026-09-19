package com.yjn.sqlagent.realtime.controller;

import com.yjn.sqlagent.realtime.common.RealtimeActorProvider;
import com.yjn.sqlagent.realtime.common.RealtimeResponse;
import com.yjn.sqlagent.realtime.repository.BusinessDomainRepository;
import com.yjn.sqlagent.realtime.service.BusinessDomainService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
@RequestMapping("/api/realtime/business-domains")
public class BusinessDomainController {
    private final BusinessDomainRepository repository; private final BusinessDomainService service;
    private final RealtimeActorProvider actors;
    public BusinessDomainController(BusinessDomainRepository repository, BusinessDomainService service,
                                    RealtimeActorProvider actors) {
        this.repository=repository; this.service=service; this.actors=actors;
    }
    @GetMapping public RealtimeResponse<Map<String,Object>> page(@RequestParam Map<String,String> query){actors.requireActor();return RealtimeResponse.success(repository.page(query));}
    @GetMapping("/options") public RealtimeResponse<List<Map<String,Object>>> options(){actors.requireActor();return RealtimeResponse.success(repository.enabledOptions());}
    @PostMapping public RealtimeResponse<Map<String,Object>> create(@RequestBody Map<String,Object> body){actors.requireActor();return RealtimeResponse.success(service.create(new LinkedHashMap<>(body)));}
    @PutMapping("/{id}") public RealtimeResponse<Map<String,Object>> update(@PathVariable long id,@RequestBody Map<String,Object> body){actors.requireActor();return RealtimeResponse.success(service.update(id,new LinkedHashMap<>(body)));}
    @PostMapping("/{id}/status") public RealtimeResponse<Map<String,Object>> status(@PathVariable long id,@RequestBody Map<String,Object> body){actors.requireActor();return RealtimeResponse.success(service.status(id,Boolean.TRUE.equals(body.get("enabled"))));}
    @GetMapping("/{id}/assets")
    public RealtimeResponse<Map<String,Object>> assets(@PathVariable long id,
            @RequestParam(defaultValue="") String assetType,
            @RequestParam(defaultValue="") String databaseName,
            @RequestParam(defaultValue="") String keyword,
            @RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int pageSize) {
        actors.requireActor();
        repository.required(id);
        return RealtimeResponse.success(repository.assetPage(
                id, assetType, databaseName, keyword, page, pageSize));
    }
    @GetMapping("/assignment") public RealtimeResponse<Map<String,Object>> assignment(@RequestParam String assetType,@RequestParam(defaultValue="")String catalogName,@RequestParam String databaseName,@RequestParam String tableName){actors.requireActor();return RealtimeResponse.success(service.assignment(assetType,catalogName,databaseName,tableName));}
    @PutMapping("/assignment") public RealtimeResponse<Map<String,Object>> assign(@RequestBody Map<String,Object> body){return RealtimeResponse.success(service.assign(new LinkedHashMap<>(body),actors.requireActor()));}
    @DeleteMapping("/assignment") public RealtimeResponse<Boolean> unassign(@RequestParam String assetType,@RequestParam(defaultValue="")String catalogName,@RequestParam String databaseName,@RequestParam String tableName){actors.requireActor();service.unassign(assetType,catalogName,databaseName,tableName);return RealtimeResponse.success(true);}
}
