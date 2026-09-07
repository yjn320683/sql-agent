package com.yjn.sqlagent.realtime.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class RealtimeObservabilityRepository {
    private static final TypeReference<Map<String,Object>> OBJECT_MAP = new TypeReference<Map<String,Object>>() { };
    private final JdbcTemplate jdbc; private final ObjectMapper mapper;
    public RealtimeObservabilityRepository(JdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}

    public void upsertProgress(long taskId,long instanceId,Map<String,Object> sync){
        Long dirty=jdbc.queryForObject("SELECT COUNT(*) FROM rt_sync_dirty_record WHERE task_id=? AND resolved_flag=0",Long.class,taskId);
        Integer finished=integer(sync.get("snapshotSplitsFinished")),remaining=integer(sync.get("snapshotSplitsRemaining"));
        Double progress=null;if(finished!=null&&remaining!=null&&finished+remaining>0)progress=(double)finished/(finished+remaining);
        jdbc.update("INSERT INTO rt_sync_progress_snapshot(task_id,task_instance_id,snapshot_finished,snapshot_remaining,snapshot_progress,source_lag_ms,source_idle_ms,dirty_record_count,offset_summary,metric_payload,observed_at) VALUES(?,?,?,?,?,?,?,?,?,?,NOW()) ON DUPLICATE KEY UPDATE snapshot_finished=VALUES(snapshot_finished),snapshot_remaining=VALUES(snapshot_remaining),snapshot_progress=VALUES(snapshot_progress),source_lag_ms=VALUES(source_lag_ms),source_idle_ms=VALUES(source_idle_ms),dirty_record_count=VALUES(dirty_record_count),offset_summary=VALUES(offset_summary),metric_payload=VALUES(metric_payload),observed_at=NOW(),update_time=NOW()",
                taskId,instanceId,finished,remaining,progress,longValue(sync.get("sourceLagMs")),longValue(sync.get("sourceIdleMs")),dirty==null?0:dirty,text(sync.get("offsetSummary")),json(sync));
    }
    public Map<String,Object> progress(long taskId,long instanceId){List<Map<String,Object>>rows=jdbc.queryForList("SELECT task_id taskId,task_instance_id taskInstanceId,snapshot_finished snapshotFinished,snapshot_remaining snapshotRemaining,snapshot_progress snapshotProgress,source_lag_ms sourceLagMs,source_idle_ms sourceIdleMs,dirty_record_count dirtyRecordCount,offset_summary offsetSummary,metric_payload metricPayload,observed_at observedAt FROM rt_sync_progress_snapshot WHERE task_id=? AND task_instance_id=?",taskId,instanceId);if(rows.isEmpty())return Map.of();Map<String,Object>r=new LinkedHashMap<>(rows.get(0));r.put("metrics",jsonMap(r.remove("metricPayload")));return r;}
    public Map<String,Object> dirtyPage(long taskId,boolean unresolvedOnly,int page,int pageSize){String where=unresolvedOnly?" AND resolved_flag=0":"";List<Map<String,Object>>items=jdbc.queryForList("SELECT id,task_id taskId,task_instance_id taskInstanceId,source_database sourceDatabase,source_table sourceTable,operation_type operationType,primary_key_value primaryKeyValue,error_code errorCode,error_message errorMessage,raw_payload rawPayload,resolved_flag resolved,resolved_by resolvedBy,resolved_at resolvedAt,create_time createTime FROM rt_sync_dirty_record WHERE task_id=?"+where+" ORDER BY id DESC LIMIT ?,?",taskId,(page-1)*pageSize,pageSize);for(Map<String,Object>row:items)row.put("rawPayload",jsonValue(row.get("rawPayload")));Long total=jdbc.queryForObject("SELECT COUNT(*) FROM rt_sync_dirty_record WHERE task_id=?"+where,Long.class,taskId);return Map.of("items",items,"page",page,"pageSize",pageSize,"total",total==null?0:total);}
    public long insertDirty(long taskId,Long instanceId,Map<String,Object>body){KeyHolder key=new GeneratedKeyHolder();jdbc.update(c->{java.sql.PreparedStatement ps=c.prepareStatement("INSERT INTO rt_sync_dirty_record(task_id,task_instance_id,source_database,source_table,operation_type,primary_key_value,error_code,error_message,raw_payload) VALUES(?,?,?,?,?,?,?,?,?)",java.sql.Statement.RETURN_GENERATED_KEYS);ps.setLong(1,taskId);if(instanceId==null)ps.setNull(2,java.sql.Types.BIGINT);else ps.setLong(2,instanceId);ps.setString(3,text(body.get("sourceDatabase")));ps.setString(4,text(body.get("sourceTable")));ps.setString(5,text(body.get("operationType")));ps.setString(6,text(body.get("primaryKeyValue")));ps.setString(7,text(body.get("errorCode")));ps.setString(8,text(body.get("errorMessage")));ps.setString(9,json(body.get("rawPayload")));return ps;},key);return key.getKey().longValue();}
    public void resolveDirty(long taskId,long id,String actor){if(jdbc.update("UPDATE rt_sync_dirty_record SET resolved_flag=1,resolved_by=?,resolved_at=NOW() WHERE id=? AND task_id=? AND resolved_flag=0",actor,id,taskId)!=1)throw new IllegalArgumentException("脏数据记录不存在或已处理");}
    public List<Map<String,Object>> schemaEvents(long taskId){List<Map<String,Object>>rows=jdbc.queryForList("SELECT id,task_id taskId,realtime_table_id realtimeTableId,source_database sourceDatabase,source_table sourceTable,target_database targetDatabase,target_table targetTable,change_type changeType,status,change_signature changeSignature,change_payload changePayload,detected_at detectedAt,applied_by appliedBy,applied_at appliedAt,message FROM rt_schema_change_event WHERE task_id=? ORDER BY id DESC",taskId);for(Map<String,Object>r:rows)r.put("change",jsonMap(r.remove("changePayload")));return rows;}
    public long upsertSchemaEvent(long taskId,long realtimeTableId,String sourceDb,String sourceTable,String targetDb,String targetTable,String changeType,String status,Map<String,Object>payload,String message){String signature=sha256(json(payload));jdbc.update("INSERT INTO rt_schema_change_event(task_id,realtime_table_id,source_database,source_table,target_database,target_table,change_type,status,change_signature,change_payload,detected_at,message) VALUES(?,?,?,?,?,?,?,?,?,?,NOW(),?) ON DUPLICATE KEY UPDATE detected_at=NOW(),message=VALUES(message),update_time=NOW()",taskId,realtimeTableId,sourceDb,sourceTable,targetDb,targetTable,changeType,status,signature,json(payload),message);return jdbc.queryForObject("SELECT id FROM rt_schema_change_event WHERE task_id=? AND realtime_table_id=? AND change_signature=?",Long.class,taskId,realtimeTableId,signature);}
    public Map<String,Object> requiredSchemaEvent(long id){List<Map<String,Object>>rows=jdbc.queryForList("SELECT id,task_id taskId,realtime_table_id realtimeTableId,status,change_type changeType,change_payload changePayload FROM rt_schema_change_event WHERE id=?",id);if(rows.isEmpty())throw new IllegalArgumentException("Schema 变更事件不存在");Map<String,Object>r=new LinkedHashMap<>(rows.get(0));r.put("change",jsonMap(r.remove("changePayload")));return r;}
    public void markSchemaApplied(long id,String actor){jdbc.update("UPDATE rt_schema_change_event SET status='APPLIED',applied_by=?,applied_at=NOW(),update_time=NOW() WHERE id=? AND status='PENDING'",actor,id);}
    private String json(Object v){try{return mapper.writeValueAsString(v==null?Collections.emptyMap():v);}catch(Exception e){throw new IllegalArgumentException("数据无法序列化");}}
    private Map<String,Object>jsonMap(Object v){if(v==null)return new LinkedHashMap<>();try{return mapper.readValue(String.valueOf(v),OBJECT_MAP);}catch(Exception e){return new LinkedHashMap<>();}}
    private Object jsonValue(Object value){if(value==null)return null;try{return mapper.readValue(String.valueOf(value),Object.class);}catch(Exception ignored){return value;}}
    private String text(Object v){return v==null?null:String.valueOf(v);}
    private Integer integer(Object v){if(v instanceof Number)return((Number)v).intValue();try{return v==null?null:Integer.parseInt(String.valueOf(v));}catch(Exception e){return null;}}
    private Long longValue(Object v){if(v instanceof Number)return((Number)v).longValue();try{return v==null?null:Math.round(Double.parseDouble(String.valueOf(v)));}catch(Exception e){return null;}}
    private String sha256(String value){try{byte[]digest=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:digest)out.append(String.format("%02x",b));return out.toString();}catch(Exception e){throw new IllegalStateException(e);}}
}
