package com.yjn.sqlagent.realtime.service;

import com.yjn.sqlagent.realtime.repository.RealtimeObservabilityRepository;
import com.yjn.sqlagent.realtime.repository.RealtimeSyncRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class RealtimeSyncObservabilityService {
    private final RealtimeObservabilityRepository observability;
    private final RealtimeSyncRepository tasks;
    private final RealtimeRuntimeService runtime;
    private final RealtimeServerService servers;
    private final RealtimeTableService tables;

    public RealtimeSyncObservabilityService(RealtimeObservabilityRepository observability,
            RealtimeSyncRepository tasks, RealtimeRuntimeService runtime,
            RealtimeServerService servers, RealtimeTableService tables) {
        this.observability=observability;this.tasks=tasks;this.runtime=runtime;this.servers=servers;this.tables=tables;
    }

    public Map<String,Object> progress(long taskId,long instanceId,boolean refresh){
        tasks.requiredInstance(taskId,instanceId);
        if(refresh){
            try{
                Object value=runtime.runtime(taskId,instanceId);
                Map<String,Object>root=map(value);Map<String,Object>sync=map(root.get("sync"));
                observability.upsertProgress(taskId,instanceId,sync);
            }catch(RuntimeException ignored){/* 终态或 Flink REST 不可用时返回最后一次快照。 */}
        }
        return observability.progress(taskId,instanceId);
    }

    public Map<String,Object> dirtyPage(long taskId,boolean unresolvedOnly,int page,int pageSize){tasks.requiredTask(taskId);if(page<1||pageSize<1||pageSize>100)throw new IllegalArgumentException("分页参数非法");return observability.dirtyPage(taskId,unresolvedOnly,page,pageSize);}
    public long addDirty(long taskId,Long instanceId,Map<String,Object>body){tasks.requiredTask(taskId);if(text(body.get("errorMessage")).isEmpty())throw new IllegalArgumentException("errorMessage 不能为空");if(instanceId!=null)tasks.requiredInstance(taskId,instanceId);return observability.insertDirty(taskId,instanceId,body);}
    public void resolveDirty(long taskId,long id,String actor){tasks.requiredTask(taskId);observability.resolveDirty(taskId,id,actor);}

    public List<Map<String,Object>> detectSchemaChanges(long taskId){
        Map<String,Object>task=tasks.requiredTask(taskId);long serverId=number(task.get("sourceServerId"));
        for(Map<String,Object>mapping:tasks.mappings(taskId)){
            String sourceDb=text(mapping.get("sourceDatabase")),sourceTable=text(mapping.get("sourceTable"));
            String targetDb=text(mapping.get("targetDatabase")),targetTable=text(mapping.get("targetTable"));
            Object realtimeIdValue=mapping.get("realtimeTableId");if(realtimeIdValue==null)continue;long realtimeId=number(realtimeIdValue);
            Map<String,Object>source=servers.schema(serverId,sourceTable);Map<String,Object>target=tables.detail(realtimeId);
            compare(taskId,realtimeId,sourceDb,sourceTable,targetDb,targetTable,columns(source),columns(target));
        }
        return observability.schemaEvents(taskId);
    }

    public List<Map<String,Object>> schemaChanges(long taskId){
        tasks.requiredTask(taskId);
        return observability.schemaEvents(taskId);
    }

    public Map<String,Object> applySchemaChange(long taskId,long eventId,String actor){
        Map<String,Object>event=observability.requiredSchemaEvent(eventId);
        if(number(event.get("taskId"))!=taskId)throw new IllegalArgumentException("Schema 变更事件不属于当前任务");
        if(!"PENDING".equals(event.get("status"))||!"ADD_COLUMNS".equals(event.get("changeType")))throw new IllegalStateException("该 Schema 事件不可自动应用");
        Map<String,Object>change=map(event.get("change"));Map<String,Object>request=new LinkedHashMap<>();request.put("addColumns",change.get("addColumns"));
        Map<String,Object>result=tables.applySyncEvolution(number(event.get("realtimeTableId")),request,actor,eventId);
        observability.markSchemaApplied(eventId,actor);return result;
    }

    private void compare(long taskId,long realtimeId,String sourceDb,String sourceTable,String targetDb,String targetTable,List<Map<String,Object>>source,List<Map<String,Object>>target){
        Map<String,Map<String,Object>>targetByName=new LinkedHashMap<>();for(Map<String,Object>column:target)targetByName.put(text(column.get("name")).toLowerCase(Locale.ROOT),column);
        List<Map<String,Object>>additions=new ArrayList<>();List<Map<String,Object>>incompatible=new ArrayList<>();
        for(Map<String,Object>column:source){String name=text(column.get("name"));Map<String,Object>existing=targetByName.get(name.toLowerCase(Locale.ROOT));String sourceType=normalizeType(column.get("type"));if(existing==null){Map<String,Object>addition=new LinkedHashMap<>();addition.put("name",name);addition.put("dataType",sourceType);addition.put("nullable",!Boolean.FALSE.equals(column.get("nullable")));addition.put("primaryKey",false);addition.put("partitionKey",false);addition.put("comment",column.get("comment"));additions.add(addition);}else if(!compatible(sourceType,normalizeType(existing.get("dataType")))){incompatible.add(Map.of("column",name,"sourceType",sourceType,"targetType",normalizeType(existing.get("dataType"))));}}
        if(!incompatible.isEmpty())observability.upsertSchemaEvent(taskId,realtimeId,sourceDb,sourceTable,targetDb,targetTable,"INCOMPATIBLE","BLOCKED",Map.of("incompatibleColumns",incompatible),"存在不兼容字段类型，必须人工迁移");
        if(!additions.isEmpty())observability.upsertSchemaEvent(taskId,realtimeId,sourceDb,sourceTable,targetDb,targetTable,"ADD_COLUMNS","PENDING",Map.of("addColumns",additions),"检测到 "+additions.size()+" 个可安全新增字段");
    }
    private boolean compatible(String source,String target){if(source.equals(target))return true;List<String>strings=List.of("STRING","VARCHAR","CHAR","TEXT");if(strings.contains(source)&&strings.contains(target))return true;List<String>numbers=List.of("TINYINT","SMALLINT","INT","INTEGER","BIGINT","FLOAT","DOUBLE","DECIMAL");return numbers.contains(source)&&numbers.contains(target)&&numbers.indexOf(target)>=numbers.indexOf(source);}
    private String normalizeType(Object value){String type=text(value).toUpperCase(Locale.ROOT);int index=type.indexOf('(');return(index<0?type:type.substring(0,index)).trim();}
    @SuppressWarnings("unchecked")private Map<String,Object>map(Object value){return value instanceof Map?new LinkedHashMap<>((Map<String,Object>)value):new LinkedHashMap<>();}
    @SuppressWarnings("unchecked")private List<Map<String,Object>>columns(Map<String,Object>value){Object columns=value.get("columns");return columns instanceof List?(List<Map<String,Object>>)columns:List.of();}
    private String text(Object value){return value==null?"":String.valueOf(value).trim();}
    private long number(Object value){if(value instanceof Number)return((Number)value).longValue();return Long.parseLong(String.valueOf(value));}
}
