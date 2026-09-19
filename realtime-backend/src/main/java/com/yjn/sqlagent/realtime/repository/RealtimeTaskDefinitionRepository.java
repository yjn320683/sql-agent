package com.yjn.sqlagent.realtime.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjn.sqlagent.realtime.config.RealtimeProperties;
import com.yjn.sqlagent.realtime.model.UnifiedTaskRequest;
import com.yjn.sqlagent.realtime.model.RealtimeLineageSnapshotDraft;
import com.yjn.sqlagent.datamap.store.LineageOutboxService;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

@Repository
public class RealtimeTaskDefinitionRepository {
    private final JdbcTemplate jdbc; private final NamedParameterJdbcTemplate named; private final ObjectMapper mapper;
    private final RealtimeProperties properties; private final RealtimeTableRepository tables;
    private final LineageRelationRepository lineageRelations;
    private LineageOutboxService lineageOutbox;
    public RealtimeTaskDefinitionRepository(JdbcTemplate jdbc,ObjectMapper mapper,RealtimeProperties properties,RealtimeTableRepository tables,LineageRelationRepository lineageRelations){this.jdbc=jdbc;this.named=new NamedParameterJdbcTemplate(jdbc);this.mapper=mapper;this.properties=properties;this.tables=tables;this.lineageRelations=lineageRelations;}
    @Autowired(required=false) void setLineageOutbox(LineageOutboxService value){this.lineageOutbox=value;}

    public Map<String,Object> page(String taskType,Map<String,String> query){
        requireType(taskType); int page=positive(query.get("page"),1),size=Math.min(100,positive(query.get("pageSize"),20));
        StringBuilder where=new StringBuilder(" WHERE t.task_type=:taskType AND t.status<>'deleted'"); MapSqlParameterSource params=new MapSqlParameterSource("taskType",taskType);
        String keyword=text(query.get("keyword")); if(!keyword.isEmpty()){where.append(" AND (CAST(t.id AS CHAR) LIKE :keyword OR t.task_name LIKE :keyword OR t.description LIKE :keyword)");params.addValue("keyword","%"+keyword+"%");}
        String status=text(query.get("status"));if(!status.isEmpty()&&!"all".equals(status)){where.append(" AND t.status=:status");params.addValue("status",status);}
        String owner=text(query.get("owner"));if(!owner.isEmpty()&&!"all".equals(owner)){where.append(" AND t.owner=:owner");params.addValue("owner",owner);}
        String operator=text(query.get("lastOperator"));if(!operator.isEmpty()){where.append(" AND EXISTS (SELECT 1 FROM rt_task_change_log lo WHERE lo.task_id=t.id AND lo.operator LIKE :operator)");params.addValue("operator","%"+operator+"%");}
        String tableKeyword=text(query.get("sourceKeyword"));if(tableKeyword.isEmpty())tableKeyword=text(query.get("targetKeyword"));
        if(!tableKeyword.isEmpty()){where.append(" AND EXISTS (SELECT 1 FROM rt_task_table_reference tr JOIN rt_realtime_table rr ON rr.id=tr.realtime_table_id WHERE tr.task_id=t.id AND (rr.database_name LIKE :tableKeyword OR rr.table_name LIKE :tableKeyword OR CONCAT(rr.database_name,'.',rr.table_name) LIKE :tableKeyword))");params.addValue("tableKeyword","%"+tableKeyword+"%");}
        Long total=named.queryForObject("SELECT COUNT(*) FROM rt_task t"+where,params,Long.class);params.addValue("limit",size).addValue("offset",(page-1)*size);
        List<Map<String,Object>> records=named.queryForList("SELECT t.id,t.task_name name,t.task_type taskType,t.status,t.owner,t.description,t.flink_version flinkVersion,"
                +"t.create_time createTime,t.update_time updateTime,l.operator lastOperator,l.create_time lastOperationTime,"
                +"i.id latestInstanceId,i.status latestInstanceStatus,i.execution_mode latestInstanceExecutionMode,i.tracking_url flinkUrl "
                +"FROM rt_task t LEFT JOIN rt_task_change_log l ON l.id=(SELECT MAX(l2.id) FROM rt_task_change_log l2 WHERE l2.task_id=t.id) "
                +"LEFT JOIN rt_task_instance i ON i.id=(SELECT MAX(i2.id) FROM rt_task_instance i2 WHERE i2.task_id=t.id AND i2.execution_mode='PRODUCTION')"
                +where+" ORDER BY t.update_time DESC,t.id DESC LIMIT :limit OFFSET :offset",params);
        for(Map<String,Object> row:records){ long id=((Number)row.get("id")).longValue(); row.put("tableReferences",tablesForTask(id)); row.put("capabilities",capabilities()); }
        return Map.of("records",records,"total",total==null?0:total,"pageNo",page,"pageSize",size);
    }

    public Map<String,Object> required(long taskId){
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT id,project_id projectId,task_name name,task_type taskType,flink_version flinkVersion,owner,description,status,create_time createTime,update_time updateTime FROM rt_task WHERE id=? AND status<>'deleted'",taskId);
        if(rows.isEmpty())throw new IllegalArgumentException("实时任务不存在："+taskId);Map<String,Object> result=new LinkedHashMap<>(rows.get(0));String type=text(result.get("taskType"));requireType(type);
        if("compute".equals(type)){Map<String,Object> config=jdbc.queryForMap("SELECT default_database defaultDatabase,sql_text sqlText,config_json configJson FROM rt_compute_task_config WHERE task_id=?",taskId);Map<String,Object> taskConfig=jsonMap(config.remove("configJson"));Map<String,Object> compute=map(taskConfig.get("computeConfig"));compute.put("defaultDatabase",config.get("defaultDatabase"));compute.put("sql",config.get("sqlText"));taskConfig.put("computeConfig",compute);result.put("taskConfig",taskConfig);}
        else {Map<String,Object> config=jdbc.queryForMap("SELECT source_database sourceDatabase,target_server_id targetServerId,config_json configJson FROM rt_export_task_config WHERE task_id=?",taskId);Map<String,Object> taskConfig=jsonMap(config.remove("configJson"));Map<String,Object> export=map(taskConfig.get("exportConfig"));export.put("sourceDatabase",config.get("sourceDatabase"));export.put("targetServerId",config.get("targetServerId"));export.put("mappings",exportMappings(taskId));taskConfig.put("exportConfig",export);result.put("taskConfig",taskConfig);}
        Map<String,Object> config=map(result.get("taskConfig"));result.put("alarmConfig",Map.of("alarmType",text(config.get("alarmType")),"alarmGroup",text(config.get("alarmGroup"))));
        result.put("flinkConf",flink(config)); result.put("tableReferences",tablesForTask(taskId)); return result;
    }

    @Transactional public long create(UnifiedTaskRequest request,String actor,List<Long> inputs,List<Long> outputs,RealtimeLineageSnapshotDraft lineage){
        String type=type(request);KeyHolder holder=new GeneratedKeyHolder();jdbc.update(connection->{PreparedStatement ps=connection.prepareStatement("INSERT INTO rt_task(project_id,task_name,task_type,flink_version,owner,description,status) VALUES(?,?,?,?,?,?,'not_running')",Statement.RETURN_GENERATED_KEYS);ps.setLong(1,properties.getDefaultProjectId());ps.setString(2,request.getName().trim());ps.setString(3,type);ps.setString(4,text(request.getFlinkVersion(),"2.2.1"));ps.setString(5,text(request.getOwner(),actor));ps.setString(6,request.getDescription());return ps;},holder);
        long id=((Number)holder.getKeys().values().iterator().next()).longValue();persistSpecific(id,request);tables.replaceReferences(id,inputs,outputs);VersionRecord version=insertVersion(id,request,actor);insertLineage(id,version,lineage);change(id,null,version.id,actor,"CREATE","创建实时"+label(type)+"任务");return id;
    }

    @Transactional public void update(long id,UnifiedTaskRequest request,String actor,List<Long> inputs,List<Long> outputs,RealtimeLineageSnapshotDraft lineage){
        Map<String,Object> current=required(id);if(!type(request).equals(current.get("taskType")))throw new IllegalArgumentException("任务类型不能修改");if(active(id))throw new IllegalStateException("运行中的任务不能修改配置");Long before=latestVersion(id);
        int updated;if(text(request.getExpectedUpdateTime()).isEmpty())updated=jdbc.update("UPDATE rt_task SET task_name=?,flink_version=?,owner=?,description=?,update_time=NOW() WHERE id=?",request.getName().trim(),request.getFlinkVersion(),request.getOwner(),request.getDescription(),id);else updated=jdbc.update("UPDATE rt_task SET task_name=?,flink_version=?,owner=?,description=?,update_time=NOW() WHERE id=? AND DATE_FORMAT(update_time,'%Y-%m-%d %H:%i:%s')=?",request.getName().trim(),request.getFlinkVersion(),request.getOwner(),request.getDescription(),id,request.getExpectedUpdateTime().replace('T',' ').substring(0,19));
        if(updated==0)throw new IllegalStateException("任务已被其他用户修改，请刷新后重试");persistSpecific(id,request);tables.replaceReferences(id,inputs,outputs);VersionRecord after=insertVersion(id,request,actor);insertLineage(id,after,lineage);change(id,before,after.id,actor,"EDIT","编辑实时"+label(type(request))+"任务");
    }

    @Transactional public void delete(long id,String actor){Map<String,Object> task=required(id);if(active(id))throw new IllegalStateException("任务存在活动实例，不能删除");String type=text(task.get("taskType"));Long before=latestVersion(id);jdbc.update("UPDATE rt_task SET task_name=CONCAT(task_name,'#deleted#',id),status='deleted',update_time=NOW() WHERE id=?",id);jdbc.update("DELETE FROM rt_compute_task_config WHERE task_id=?",id);jdbc.update("DELETE FROM rt_export_task_table_mapping WHERE task_id=?",id);jdbc.update("DELETE FROM rt_export_task_config WHERE task_id=?",id);tables.releaseTask(id);change(id,before,null,actor,"DELETE","删除实时"+label(type)+"任务");}

    public List<Map<String,Object>> exportMappings(long taskId){return jdbc.queryForList("SELECT m.id,m.realtime_table_id realtimeTableId,r.database_name sourceDatabase,r.table_name sourceTable,m.target_server_id targetServerId,s.name targetServerName,m.target_database targetDatabase,m.target_table targetTable,m.column_mapping_json columnMappingJson,m.primary_keys_json primaryKeysJson,m.write_mode writeMode,m.sort_order sortOrder FROM rt_export_task_table_mapping m JOIN rt_realtime_table r ON r.id=m.realtime_table_id JOIN rt_server s ON s.id=m.target_server_id WHERE m.task_id=? ORDER BY m.sort_order,m.id",taskId).stream().map(row->{Map<String,Object>x=new LinkedHashMap<>(row);x.put("columnMappings",jsonList(x.remove("columnMappingJson")));x.put("primaryKeys",jsonList(x.remove("primaryKeysJson")));return x;}).collect(java.util.stream.Collectors.toList());}
    public List<Map<String,Object>> tablesForTask(long taskId){return jdbc.queryForList("SELECT x.realtime_table_id realtimeTableId,x.reference_role referenceRole,r.database_name databaseName,r.table_name tableName,r.physical_status physicalStatus FROM rt_task_table_reference x JOIN rt_realtime_table r ON r.id=x.realtime_table_id WHERE x.task_id=? ORDER BY x.reference_role,r.database_name,r.table_name",taskId);}

    private void persistSpecific(long id,UnifiedTaskRequest request){Map<String,Object> normalized=normalized(request);if("compute".equals(type(request))){Map<String,Object> c=map(request.getTaskConfig().get("computeConfig"));jdbc.update("INSERT INTO rt_compute_task_config(task_id,default_database,sql_text,config_json) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE default_database=VALUES(default_database),sql_text=VALUES(sql_text),config_json=VALUES(config_json),update_time=NOW()",id,text(c.get("defaultDatabase")),text(c.get("sql")),json(normalized));}
        else{Map<String,Object> e=map(request.getTaskConfig().get("exportConfig"));long server=number(e.get("targetServerId"));Map<String,Object>s=jdbc.queryForMap("SELECT database_name databaseName FROM rt_server WHERE id=? AND type='mysql'",server);jdbc.update("INSERT INTO rt_export_task_config(task_id,source_database,target_server_id,config_json) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE source_database=VALUES(source_database),target_server_id=VALUES(target_server_id),config_json=VALUES(config_json),update_time=NOW()",id,text(e.get("sourceDatabase")),server,json(normalized));jdbc.update("DELETE FROM rt_export_task_table_mapping WHERE task_id=?",id);int order=0;for(Map<String,Object>m:maps(e.get("mappings"))){jdbc.update("INSERT INTO rt_export_task_table_mapping(task_id,realtime_table_id,target_server_id,target_database,target_table,column_mapping_json,primary_keys_json,write_mode,sort_order) VALUES(?,?,?,?,?,?,?,?,?)",id,number(m.get("realtimeTableId")),server,text(s.get("databaseName")),text(m.get("targetTable")),json(m.getOrDefault("columnMappings",List.of())),json(m.getOrDefault("primaryKeys",List.of())),"upsert",order++);}}}
    private Map<String,Object> normalized(UnifiedTaskRequest request){Map<String,Object>r=new LinkedHashMap<>(request.getTaskConfig());r.put("parallelism",request.getFlinkConf().get("parallelism"));r.put("checkpointInterval",request.getFlinkConf().get("checkpointIntervalSeconds"));r.put("taskManagerMemory",memory(request.getFlinkConf().get("taskManagerMemoryGb")));r.put("jobManagerMemory",memory(request.getFlinkConf().get("jobManagerMemoryGb")));r.put("flinkConfOverrides",request.getFlinkConf().getOrDefault("flinkConfOverrides",Map.of()));r.put("alarmType",request.getAlarmConfig().get("alarmType"));r.put("alarmGroup",request.getAlarmConfig().get("alarmGroup"));return r;}
    private VersionRecord insertVersion(long id,UnifiedTaskRequest request,String actor){Integer next=jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM rt_task_version WHERE task_id=?",Integer.class,id);int versionNo=next==null?1:next;KeyHolder h=new GeneratedKeyHolder();jdbc.update(c->{PreparedStatement ps=c.prepareStatement("INSERT INTO rt_task_version(task_id,version_no,config,operator) VALUES(?,?,?,?)",Statement.RETURN_GENERATED_KEYS);ps.setLong(1,id);ps.setInt(2,versionNo);ps.setString(3,json(request));ps.setString(4,actor);return ps;},h);return new VersionRecord(((Number)h.getKeys().values().iterator().next()).longValue(),versionNo);}
    private void insertLineage(long taskId,VersionRecord version,RealtimeLineageSnapshotDraft draft){KeyHolder h=new GeneratedKeyHolder();jdbc.update(c->{PreparedStatement ps=c.prepareStatement("INSERT INTO task_lineage_snapshot(task_scope,task_id,version_id,version_no,sql_checksum,dialect,default_database,parser_version,snapshot_source,complete_flag,lineage_json,diagnostics_json) VALUES('REALTIME',?,?,?,?,?,?,'parse-sql-v2','SAVED',?,?,?)",Statement.RETURN_GENERATED_KEYS);ps.setLong(1,taskId);ps.setLong(2,version.id);ps.setInt(3,version.versionNo);ps.setString(4,draft.getChecksum());ps.setString(5,draft.getDialect());ps.setString(6,draft.getDefaultDatabase());ps.setBoolean(7,draft.isComplete());ps.setString(8,json(draft.getLineage()));ps.setString(9,json(draft.getDiagnostics()));return ps;},h);Number key=h.getKey();if(key!=null){lineageRelations.index(key.longValue(),"REALTIME",taskId,version.id,version.versionNo,draft.getLineage());if(lineageOutbox!=null)lineageOutbox.enqueue(key.longValue(),"REALTIME",taskId,version.id,version.versionNo);}}
    private void change(long id,Long before,Long after,String actor,String action,String detail){jdbc.update("INSERT INTO rt_task_change_log(task_id,before_version_id,after_version_id,operator,action,detail) VALUES(?,?,?,?,?,?)",id,before,after,actor,action,detail);}
    private boolean active(long id){Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM rt_task_instance WHERE task_id=? AND status IN ('submitting','running','stopping','restarting','debug_success_running')",Integer.class,id);return count!=null&&count>0;}
    private Long latestVersion(long id){List<Long>x=jdbc.query("SELECT id FROM rt_task_version WHERE task_id=? ORDER BY version_no DESC LIMIT 1",(rs,row)->rs.getLong(1),id);return x.isEmpty()?null:x.get(0);}
    private Map<String,Object> flink(Map<String,Object>c){Map<String,Object>r=new LinkedHashMap<>();r.put("parallelism",c.get("parallelism"));r.put("checkpointIntervalSeconds",c.get("checkpointInterval"));r.put("taskManagerMemoryGb",memoryGb(c.get("taskManagerMemory")));r.put("jobManagerMemoryGb",memoryGb(c.get("jobManagerMemory")));r.put("flinkConfOverrides",c.getOrDefault("flinkConfOverrides",Map.of()));return r;}
    private Map<String,Object> capabilities(){Map<String,Object>r=new LinkedHashMap<>();for(String k:List.of("view","edit","delete","debug","enable","stop","instances","stopDebugInstance","operations","startStop"))r.put(k,true);return r;}
    private String type(UnifiedTaskRequest r){String t=text(r.getTaskType()).toLowerCase();requireType(t);return t;}private void requireType(String t){if(!List.of("compute","export").contains(t))throw new IllegalArgumentException("仅支持 compute/export："+t);}private String label(String t){return "compute".equals(t)?"计算":"出仓";}
    private int positive(String v,int f){try{int n=Integer.parseInt(text(v));return n>0?n:f;}catch(Exception e){return f;}}private long number(Object v){if(v==null)throw new IllegalArgumentException("缺少必要 ID");return Long.parseLong(String.valueOf(v));}
    private Object memory(Object value) {
        if (value == null) return null;
        String raw = text(value).toLowerCase();
        try {
            java.math.BigDecimal amount;
            if (raw.endsWith("mb")) return new java.math.BigDecimal(raw.substring(0, raw.length() - 2)).stripTrailingZeros().toPlainString() + "MB";
            amount = new java.math.BigDecimal(raw.replaceAll("g(?:b)?$", ""));
            if (amount.stripTrailingZeros().scale() <= 0) return amount.toBigIntegerExact() + "GB";
            return amount.multiply(java.math.BigDecimal.valueOf(1024)).setScale(0, java.math.RoundingMode.HALF_UP) + "MB";
        } catch (RuntimeException ignored) {
            return String.valueOf(value);
        }
    }

    private Double memoryGb(Object value) {
        try {
            String raw = text(value).toLowerCase();
            if (raw.endsWith("mb")) return Double.valueOf(raw.substring(0, raw.length() - 2)) / 1024D;
            return Double.valueOf(raw.replaceAll("g(?:b)?$", ""));
        } catch (Exception ignored) {
            return null;
        }
    }
    private String json(Object v){try{return mapper.writeValueAsString(v);}catch(Exception e){throw new IllegalStateException(e);}}private Map<String,Object>jsonMap(Object v){try{return mapper.readValue(String.valueOf(v),new TypeReference<Map<String,Object>>(){});}catch(Exception e){return new LinkedHashMap<>();}}private List<Object>jsonList(Object v){try{return mapper.readValue(String.valueOf(v),new TypeReference<List<Object>>(){});}catch(Exception e){return new ArrayList<>();}}
    @SuppressWarnings("unchecked")private Map<String,Object>map(Object v){return v instanceof Map?new LinkedHashMap<>((Map<String,Object>)v):new LinkedHashMap<>();}@SuppressWarnings("unchecked")private List<Map<String,Object>>maps(Object v){return v instanceof List?(List<Map<String,Object>>)v:List.of();}private String text(Object v){return v==null?"":String.valueOf(v).trim();}private String text(Object v,String f){String r=text(v);return r.isEmpty()?f:r;}
    private static final class VersionRecord{private final long id;private final int versionNo;private VersionRecord(long id,int versionNo){this.id=id;this.versionNo=versionNo;}}
}
