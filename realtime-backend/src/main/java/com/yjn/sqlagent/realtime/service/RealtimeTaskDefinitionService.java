package com.yjn.sqlagent.realtime.service;

import com.yjn.sqlagent.realtime.model.UnifiedTaskRequest;
import com.yjn.sqlagent.realtime.common.ExportSchemaCompatibility;
import com.yjn.sqlagent.realtime.repository.RealtimeSyncRepository;
import com.yjn.sqlagent.realtime.repository.RealtimeTableRepository;
import com.yjn.sqlagent.realtime.repository.RealtimeTaskDefinitionRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class RealtimeTaskDefinitionService {
    private final RealtimeTaskDefinitionRepository repository; private final RealtimeTableRepository tables;
    private final RealtimeSyncRepository syncRepository; private final RealtimeServerService servers; private final ManagedFlinkPlannerService flinkPlanner;
    private final RealtimePaimonCatalogService paimon;
    public RealtimeTaskDefinitionService(RealtimeTaskDefinitionRepository repository,RealtimeTableRepository tables,
            RealtimeSyncRepository syncRepository,RealtimeServerService servers,ManagedFlinkPlannerService flinkPlanner,
            RealtimePaimonCatalogService paimon){this.repository=repository;this.tables=tables;this.syncRepository=syncRepository;this.servers=servers;this.flinkPlanner=flinkPlanner;this.paimon=paimon;}

    public long create(UnifiedTaskRequest request,String actor){References refs=validate(request,null);return repository.create(request,actor,refs.inputs,refs.outputs);}
    public void update(long id,UnifiedTaskRequest request,String actor){References refs=validate(request,id);repository.update(id,request,actor,refs.inputs,refs.outputs);}
    public Map<String,Object> analyze(UnifiedTaskRequest request){
        requireCommon(request);if(!"compute".equals(type(request)))throw new IllegalArgumentException("SQL 分析仅支持计算任务");
        Map<String,Object>compute=map(request.getTaskConfig().get("computeConfig"));ManagedFlinkPlannerService.Analysis analysis=flinkPlanner.analyze(text(compute.get("sql")),text(compute.get("defaultDatabase")));
        Map<String,Object>result=new LinkedHashMap<>();result.put("valid",true);result.put("inputs",analysis.getInputs());result.put("outputs",analysis.getOutputs());result.put("insertCount",analysis.getInsertCount());result.put("plan",analysis.getPlan());
        References refs=validateCompute(analysis,request.getTaskId());result.put("inputTableIds",refs.inputs);result.put("outputTableIds",refs.outputs);return result;
    }

    public References validate(UnifiedTaskRequest request,Long taskId){requireCommon(request);return "compute".equals(type(request))?validateCompute(request,taskId):validateExport(request,taskId);}
    private References validateCompute(UnifiedTaskRequest request,Long taskId){
        Map<String,Object>config=map(request.getTaskConfig().get("computeConfig"));String database=text(config.get("defaultDatabase"));if(database.isEmpty())throw new IllegalArgumentException("计算任务默认数据库不能为空");
        ManagedFlinkPlannerService.Analysis analysis=flinkPlanner.validate(text(config.get("sql")),database);return validateCompute(analysis,taskId);
    }
    private References validateCompute(ManagedFlinkPlannerService.Analysis analysis,Long taskId){Map<String,Map<String,Object>>managed=managedByName();
        List<Long>inputs=resolve(analysis.getInputs(),managed,false,taskId),outputs=resolve(analysis.getOutputs(),managed,true,taskId);return new References(inputs,outputs);
    }
    private References validateExport(UnifiedTaskRequest request,Long taskId){
        Map<String,Object>config=map(request.getTaskConfig().get("exportConfig"));long serverId=number(config.get("targetServerId"),"目标 Server");Map<String,Object>server=syncRepository.requiredServer(serverId,false);String database=text(server.get("databaseName"));String sourceDatabase=text(config.get("sourceDatabase"));
        if(database.isEmpty())throw new IllegalArgumentException("目标 Server 未配置数据库");List<String>targetTables=servers.tables(serverId);List<Long>inputs=new ArrayList<>();Set<String>seenTargets=new LinkedHashSet<>();
        List<Map<String,Object>>mappings=maps(config.get("mappings"));if(mappings.isEmpty())throw new IllegalArgumentException("出仓任务至少需要一组表映射");
        List<String>requestedTargets=new ArrayList<>();for(Map<String,Object>mapping:mappings)requestedTargets.add(text(mapping.get("targetTable")));
        Map<String,Map<String,Object>>targetSchemas=servers.schemas(serverId,requestedTargets);List<Map<String,Object>>contracts=new ArrayList<>();
        for(Map<String,Object>mapping:mappings){long tableId=number(mapping.get("realtimeTableId"),"实时表");Map<String,Object>source=tables.required(tableId);requireUsable(source);if(!sourceDatabase.isEmpty()&&!sourceDatabase.equalsIgnoreCase(text(source.get("databaseName"))))throw new IllegalArgumentException("出仓源表不属于所选实时库："+source.get("databaseName")+"."+source.get("tableName"));inputs.add(tableId);String target=text(mapping.get("targetTable"));if(!targetTables.contains(target))throw new IllegalArgumentException("MySQL 目标表不存在："+database+"."+target);if(!seenTargets.add(target.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("MySQL 目标表重复："+target);
            Map<String,Object>schema=targetSchemas.get(target);if(schema==null)throw new IllegalArgumentException("MySQL 目标表结构不存在："+database+"."+target);List<String>keys=strings(schema.get("primaryKeys"));if(keys.isEmpty())throw new IllegalArgumentException("MySQL 目标表必须有主键："+target);List<Map<String,Object>>columnMappings=maps(mapping.get("columnMappings"));if(columnMappings.isEmpty())columnMappings=autoMappings(source,schema);validateMappings(source,schema,columnMappings,keys,target);mapping.put("columnMappings",columnMappings);mapping.put("primaryKeys",keys);
            Map<String,Object>contract=new LinkedHashMap<>();contract.put("realtimeTableId",tableId);contract.put("source",ExportSchemaCompatibility.snapshot(text(source.get("databaseName")),text(source.get("tableName")),maps(source.get("columns")),sourcePrimaryKeys(source)));contract.put("target",ExportSchemaCompatibility.snapshot(database,target,maps(schema.get("columns")),keys));contracts.add(contract);}
        config.put("schemaContracts",contracts);request.getTaskConfig().put("exportConfig",config);
        return new References(new ArrayList<>(new LinkedHashSet<>(inputs)),List.of());
    }
    private List<Map<String,Object>>autoMappings(Map<String,Object>source,Map<String,Object>target){Set<String>targetNames=new LinkedHashSet<>();for(Map<String,Object>c:maps(target.get("columns")))targetNames.add(text(c.get("name")).toLowerCase(Locale.ROOT));List<Map<String,Object>>result=new ArrayList<>();for(Map<String,Object>c:maps(source.get("columns"))){String name=text(c.get("name"));if(targetNames.contains(name.toLowerCase(Locale.ROOT)))result.add(Map.of("sourceColumn",name,"targetColumn",name));}return result;}
    private void validateMappings(Map<String,Object>source,Map<String,Object>target,List<Map<String,Object>>mappings,List<String>keys,String table){ExportSchemaCompatibility.validate(maps(source.get("columns")),maps(target.get("columns")),mappings,keys,table);}
    private List<String>sourcePrimaryKeys(Map<String,Object>source){List<String>result=new ArrayList<>();for(Map<String,Object>column:maps(source.get("columns")))if(Boolean.TRUE.equals(column.get("primaryKey")))result.add(text(column.get("name")));return result;}
    private Map<String,Map<String,Object>>byName(List<Map<String,Object>>columns){Map<String,Map<String,Object>>r=new LinkedHashMap<>();for(Map<String,Object>c:columns)r.put(text(c.get("name")).toLowerCase(Locale.ROOT),c);return r;}
    private String baseType(String value){String v=text(value).toUpperCase(Locale.ROOT).replace(" NOT NULL","");int i=v.indexOf('(');return (i<0?v:v.substring(0,i)).trim();}
    private Set<String>names(List<Map<String,Object>>columns){Set<String>r=new LinkedHashSet<>();for(Map<String,Object>c:columns)r.add(text(c.get("name")).toLowerCase(Locale.ROOT));return r;}
    private List<Long>resolve(List<String>identifiers,Map<String,Map<String,Object>>managed,boolean output,Long taskId){List<Long>ids=new ArrayList<>();for(String identifier:identifiers){Map<String,Object>table=managed.get(identifier.toLowerCase(Locale.ROOT));if(table==null)throw new IllegalArgumentException("SQL 引用了未在实时表管理登记的表："+identifier);requireUsable(tables.required(((Number)table.get("id")).longValue()));if(output&&table.get("producerTaskId")!=null&&(taskId==null||((Number)table.get("producerTaskId")).longValue()!=taskId))throw new IllegalStateException("输出表已绑定其他生产任务："+identifier);ids.add(((Number)table.get("id")).longValue());}return new ArrayList<>(new LinkedHashSet<>(ids));}
    private Map<String,Map<String,Object>>managedByName(){Map<String,Map<String,Object>>r=new LinkedHashMap<>();for(Map<String,Object>t:tables.available()){String full="paimon."+text(t.get("databaseName"))+"."+text(t.get("tableName"));r.put(full.toLowerCase(Locale.ROOT),t);}return r;}
    private void requireActive(Map<String,Object>table){if(!"active".equalsIgnoreCase(text(table.get("physicalStatus"))))throw new IllegalStateException("实时表尚不可用："+table.get("databaseName")+"."+table.get("tableName"));}
    private void requireUsable(Map<String,Object>table){requireActive(table);Map<String,Object>physical=paimon.describe(text(table.get("databaseName")),text(table.get("tableName")));List<Map<String,Object>>declared=maps(table.get("columns")),actual=maps(physical.get("columns"));if(!schemaSignature(declared).equals(schemaSignature(actual)))throw new IllegalStateException("实时表登记 Schema 与物理表不一致，请先刷新："+table.get("databaseName")+"."+table.get("tableName"));}
    private List<String>schemaSignature(List<Map<String,Object>>columns){List<String>r=new ArrayList<>();for(Map<String,Object>c:columns)r.add(text(c.get("name")).toLowerCase(Locale.ROOT)+":"+baseType(text(c.get("dataType")))+":"+Boolean.TRUE.equals(c.get("nullable"))+":"+Boolean.TRUE.equals(c.get("primaryKey"))+":"+Boolean.TRUE.equals(c.get("partitionKey")));return r;}
    private void requireCommon(UnifiedTaskRequest r){type(r);if(text(r.getName()).isEmpty())throw new IllegalArgumentException("任务名称不能为空");if(text(r.getOwner()).isEmpty())throw new IllegalArgumentException("负责人不能为空");Object p=r.getFlinkConf().get("parallelism");if(p==null||Integer.parseInt(String.valueOf(p))<1)throw new IllegalArgumentException("并行度必须大于 0");}
    private String type(UnifiedTaskRequest r){String t=text(r.getTaskType()).toLowerCase(Locale.ROOT);if(!List.of("compute","export").contains(t))throw new IllegalArgumentException("任务类型必须是 compute 或 export");return t;}
    private long number(Object v,String label){try{return Long.parseLong(String.valueOf(v));}catch(Exception e){throw new IllegalArgumentException(label+"不能为空");}}
    @SuppressWarnings("unchecked")private Map<String,Object>map(Object v){return v instanceof Map?new LinkedHashMap<>((Map<String,Object>)v):new LinkedHashMap<>();}@SuppressWarnings("unchecked")private List<Map<String,Object>>maps(Object v){return v instanceof List?(List<Map<String,Object>>)v:List.of();}@SuppressWarnings("unchecked")private List<String>strings(Object v){return v instanceof List?(List<String>)v:List.of();}private String text(Object v){return v==null?"":String.valueOf(v).trim();}
    public static final class References{final List<Long>inputs,outputs;public References(List<Long>inputs,List<Long>outputs){this.inputs=inputs;this.outputs=outputs;}public List<Long>getInputs(){return inputs;}public List<Long>getOutputs(){return outputs;}}
}
