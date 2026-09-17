package com.yjn.sqlagent.controller;

import com.yjn.sqlagent.common.BaseResponse;
import com.yjn.sqlagent.datamap.graph.GraphStoreUnavailableException;
import com.yjn.sqlagent.datamap.service.DataMapService;
import com.yjn.sqlagent.service.CurrentUserService;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/data-map")
public class DataMapController {
    private final DataMapService service; private final CurrentUserService users;
    public DataMapController(DataMapService service,CurrentUserService users){this.service=service;this.users=users;}

    @GetMapping("/overview") public BaseResponse<Map<String,Object>>overview(HttpServletRequest request){users.requireObId(request);return BaseResponse.success(service.overview());}
    @GetMapping("/catalog/search") public BaseResponse<Map<String,Object>>search(@RequestParam(defaultValue="")String keyword,@RequestParam(defaultValue="all")String catalog,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize,HttpServletRequest request){users.requireObId(request);return BaseResponse.success(service.search(keyword,catalog,page,pageSize));}
    @GetMapping("/lineage/graph") public BaseResponse<Map<String,Object>>graph(@RequestParam(defaultValue="hive")String catalog,@RequestParam(defaultValue="")String database,@RequestParam String table,@RequestParam(defaultValue="")String column,@RequestParam(defaultValue="BOTH")String direction,@RequestParam(defaultValue="2")int depth,@RequestParam(defaultValue="TABLE")String view,HttpServletRequest request){users.requireObId(request);return BaseResponse.success(service.graph(catalog,database,table,column,direction,depth,view));}
    @PostMapping("/impact-analysis") public BaseResponse<Map<String,Object>>impact(@RequestBody Map<String,Object>body,HttpServletRequest request){users.requireObId(request);return BaseResponse.success(service.impact(body));}
    @GetMapping("/parsing/runs") public BaseResponse<Map<String,Object>>runs(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize,HttpServletRequest request){users.requireObId(request);return BaseResponse.success(service.runs(page,pageSize));}
    @GetMapping("/parsing/coverage") public BaseResponse<Map<String,Object>>coverage(HttpServletRequest request){users.requireObId(request);return BaseResponse.success(service.coverage());}
    @PostMapping("/parsing/tasks/{scope}/{taskId}/retry") public BaseResponse<Map<String,Object>>retry(@PathVariable String scope,@PathVariable long taskId,HttpServletRequest request){users.requireObId(request);return BaseResponse.success(service.retry(scope,taskId));}
    @PostMapping("/graph/reproject") public BaseResponse<Map<String,Object>>reproject(HttpServletRequest request){users.requireObId(request);return BaseResponse.success(service.reproject());}
    @PostMapping("/graph/rebuild") public BaseResponse<Map<String,Object>>rebuild(HttpServletRequest request){users.requireObId(request);return BaseResponse.success(service.rebuild());}

    @org.springframework.web.bind.annotation.ExceptionHandler(GraphStoreUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public BaseResponse<Void> graphUnavailable(GraphStoreUnavailableException error){return BaseResponse.failure(503,error.getMessage());}
}
