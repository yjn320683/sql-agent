package com.yjn.sqlagent.controller;

import com.yjn.sqlagent.common.BaseResponse;
import com.yjn.sqlagent.datamap.service.DataMapService;
import com.yjn.sqlagent.realtime.service.RealtimeTableService;
import com.yjn.sqlagent.service.CurrentUserService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 安全变更的无持久化校验与下游影响预检。 */
@RestController
@RequestMapping("/api/realtime/tables")
public class RealtimeTableImpactController {
    private final RealtimeTableService tables; private final DataMapService lineage; private final CurrentUserService users;
    public RealtimeTableImpactController(RealtimeTableService tables,DataMapService lineage,CurrentUserService users){this.tables=tables;this.lineage=lineage;this.users=users;}
    @PostMapping("/{id}/safe-update/validate")
    public BaseResponse<Map<String,Object>>validate(@PathVariable long id,@RequestBody Map<String,Object>body,HttpServletRequest request){users.requireObId(request);Map<String,Object>table=tables.validateSafeUpdate(id,new LinkedHashMap<>(body));List<String>columns=new ArrayList<>();Object additions=body.get("addColumns");if(additions instanceof List)for(Object item:(List<?>)additions)if(item instanceof Map&&((Map<?,?>)item).get("name")!=null)columns.add(String.valueOf(((Map<?,?>)item).get("name")));Map<String,Object>impactRequest=new LinkedHashMap<>();impactRequest.put("catalog",table.getOrDefault("catalogName","paimon"));impactRequest.put("database",table.get("databaseName"));impactRequest.put("table",table.get("tableName"));impactRequest.put("columns",columns);impactRequest.put("changeType",columns.isEmpty()?"SAFE_METADATA_UPDATE":"ADD_COLUMN");Map<String,Object>result=new LinkedHashMap<>(lineage.impact(impactRequest));result.put("valid",true);return BaseResponse.success(result);}
}
