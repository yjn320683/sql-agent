package com.yjn.sqlagent.realtime.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 受管实时表不可变 Schema 版本与结构化差异。 */
@Repository
public class RealtimeTableSchemaVersionRepository {
    private static final TypeReference<Map<String,Object>> MAP = new TypeReference<Map<String,Object>>() { };
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public RealtimeTableSchemaVersionRepository(JdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}

    @Transactional
    public Map<String,Object> record(long tableId,Map<String,Object>physical,String source,String actor,Long sourceEventId){
        Map<String,Object>schema=normalize(physical);String schemaJson=json(schema);String fingerprint=sha256(schemaJson);
        List<Map<String,Object>>same=jdbc.queryForList("SELECT id,version_no versionNo FROM rt_realtime_table_schema_version WHERE realtime_table_id=? AND schema_fingerprint=?",tableId,fingerprint);
        if(!same.isEmpty()){jdbc.update("UPDATE rt_realtime_table_schema_version SET last_seen_at=NOW() WHERE id=?",same.get(0).get("id"));return detail(tableId,((Number)same.get(0).get("versionNo")).intValue());}
        List<Map<String,Object>>previous=jdbc.queryForList("SELECT id,version_no versionNo,schema_fingerprint schemaFingerprint,change_source changeSource,schema_json schemaJson FROM rt_realtime_table_schema_version WHERE realtime_table_id=? ORDER BY version_no DESC LIMIT 1",tableId);
        if(!previous.isEmpty()&&normalize(readMap(previous.get(0).get("schemaJson"))).equals(schema)){
            jdbc.update("UPDATE rt_realtime_table_schema_version SET schema_fingerprint=?,schema_json=?,last_seen_at=NOW() WHERE id=?",fingerprint,schemaJson,previous.get(0).get("id"));
            return detail(tableId,((Number)previous.get(0).get("versionNo")).intValue());
        }
        int version=previous.isEmpty()?1:((Number)previous.get(0).get("versionNo")).intValue()+1;
        Map<String,Object>diff=previous.isEmpty()?emptyDiff():diff(normalize(readMap(previous.get(0).get("schemaJson"))),schema);
        String compatibility=previous.isEmpty()?"BASELINE":compatible(diff)?"COMPATIBLE":"INCOMPATIBLE";
        jdbc.update("INSERT INTO rt_realtime_table_schema_version(realtime_table_id,version_no,schema_fingerprint,change_source,compatibility,schema_json,diff_json,source_event_id,operator) VALUES(?,?,?,?,?,?,?,?,?)",tableId,version,fingerprint,source,compatibility,schemaJson,json(diff),sourceEventId,actor);
        return detail(tableId,version);
    }

    public Map<String,Object> page(long tableId,int page,int pageSize){int p=Math.max(1,page),s=Math.max(1,Math.min(100,pageSize));Long total=jdbc.queryForObject("SELECT COUNT(*) FROM rt_realtime_table_schema_version WHERE realtime_table_id=?",Long.class,tableId);List<Map<String,Object>>records=jdbc.queryForList("SELECT id,realtime_table_id realtimeTableId,version_no versionNo,schema_fingerprint schemaFingerprint,change_source changeSource,compatibility,source_event_id sourceEventId,operator,first_seen_at firstSeenAt,last_seen_at lastSeenAt,diff_json diffJson FROM rt_realtime_table_schema_version WHERE realtime_table_id=? ORDER BY version_no DESC LIMIT ?,?",tableId,(p-1)*s,s);records.forEach(row->row.put("diff",readMap(row.remove("diffJson"))));return Map.of("records",records,"total",total==null?0:total,"page",p,"pageSize",s);}
    public Map<String,Object> detail(long tableId,int versionNo){List<Map<String,Object>>rows=jdbc.queryForList("SELECT id,realtime_table_id realtimeTableId,version_no versionNo,schema_fingerprint schemaFingerprint,change_source changeSource,compatibility,schema_json schemaJson,diff_json diffJson,source_event_id sourceEventId,operator,first_seen_at firstSeenAt,last_seen_at lastSeenAt FROM rt_realtime_table_schema_version WHERE realtime_table_id=? AND version_no=?",tableId,versionNo);if(rows.isEmpty())throw new IllegalArgumentException("Schema 版本不存在："+versionNo);Map<String,Object>row=new LinkedHashMap<>(rows.get(0));row.put("schema",readMap(row.remove("schemaJson")));row.put("diff",readMap(row.remove("diffJson")));return row;}
    public Map<String,Object> compare(long tableId,int from,int to){Map<String,Object>left=detail(tableId,from),right=detail(tableId,to);Map<String,Object>delta=diff(map(left.get("schema")),map(right.get("schema")));return Map.of("fromVersion",left,"toVersion",right,"diff",delta,"compatibility",compatible(delta)?"COMPATIBLE":"INCOMPATIBLE");}

    private Map<String,Object>normalize(Map<String,Object>value){Map<String,Object>result=new LinkedHashMap<>();result.put("comment",text(value.get("comment"),text(value.get("tableComment"))));Map<String,String>options=new TreeMap<>();map(value.get("options")).forEach((key,item)->options.put(key,String.valueOf(item)));result.put("options",options);List<Map<String,Object>>columns=new ArrayList<>();for(Map<String,Object>raw:maps(value.get("columns"))){Map<String,Object>column=new LinkedHashMap<>();column.put("name",text(raw.get("name")));column.put("dataType",text(raw.get("dataType")));column.put("nullable",boolDefault(raw.get("nullable"),true));column.put("primaryKey",bool(raw.get("primaryKey")));column.put("partitionKey",bool(raw.get("partitionKey")));column.put("comment",text(raw.get("comment")));column.put("sortOrder",number(raw.get("sortOrder"),columns.size()));columns.add(column);}columns.sort(Comparator.comparingInt(item->number(item.get("sortOrder"),0)));result.put("columns",columns);return result;}
    private Map<String,Object>diff(Map<String,Object>before,Map<String,Object>after){Map<String,Map<String,Object>>old=byName(maps(before.get("columns"))),next=byName(maps(after.get("columns")));List<Map<String,Object>>added=new ArrayList<>(),removed=new ArrayList<>(),modified=new ArrayList<>();for(String name:next.keySet()){if(!old.containsKey(name))added.add(next.get(name));else{Map<String,Object>changes=new LinkedHashMap<>();for(String key:List.of("dataType","nullable","primaryKey","partitionKey","comment","sortOrder"))if(!java.util.Objects.equals(old.get(name).get(key),next.get(name).get(key))){Map<String,Object>change=new LinkedHashMap<>();change.put("before",old.get(name).get(key));change.put("after",next.get(name).get(key));changes.put(key,change);}if(!changes.isEmpty())modified.add(Map.of("name",next.get(name).get("name"),"changes",changes));}}for(String name:old.keySet())if(!next.containsKey(name))removed.add(old.get(name));List<Map<String,Object>>optionChanges=new ArrayList<>();Map<String,Object>oldOptions=map(before.get("options")),newOptions=map(after.get("options"));SetUnion.keys(oldOptions,newOptions).forEach(key->{if(!java.util.Objects.equals(oldOptions.get(key),newOptions.get(key))){Map<String,Object>change=new LinkedHashMap<>();change.put("key",key);change.put("before",oldOptions.get(key));change.put("after",newOptions.get(key));optionChanges.add(change);}});Map<String,Object>result=new LinkedHashMap<>();result.put("addedColumns",added);result.put("removedColumns",removed);result.put("modifiedColumns",modified);result.put("optionChanges",optionChanges);result.put("commentChanged",!java.util.Objects.equals(before.get("comment"),after.get("comment")));return result;}
    private boolean compatible(Map<String,Object>diff){if(!maps(diff.get("removedColumns")).isEmpty())return false;for(Map<String,Object>column:maps(diff.get("addedColumns")))if(!boolDefault(column.get("nullable"),true)||bool(column.get("primaryKey"))||bool(column.get("partitionKey")))return false;for(Map<String,Object>column:maps(diff.get("modifiedColumns"))){Map<String,Object>changes=map(column.get("changes"));if(changes.containsKey("dataType")||changes.containsKey("primaryKey")||changes.containsKey("partitionKey"))return false;if(changes.containsKey("nullable")){Map<String,Object>change=map(changes.get("nullable"));if(Boolean.TRUE.equals(change.get("before"))&&!Boolean.TRUE.equals(change.get("after")))return false;}}for(Map<String,Object>option:maps(diff.get("optionChanges")))if("bucket".equals(option.get("key")))return false;return true;}
    private Map<String,Object>emptyDiff(){Map<String,Object>result=new LinkedHashMap<>();result.put("addedColumns",List.of());result.put("removedColumns",List.of());result.put("modifiedColumns",List.of());result.put("optionChanges",List.of());result.put("commentChanged",false);return result;}
    private Map<String,Map<String,Object>>byName(List<Map<String,Object>>columns){Map<String,Map<String,Object>>result=new LinkedHashMap<>();for(Map<String,Object>column:columns)result.put(text(column.get("name")).toLowerCase(java.util.Locale.ROOT),column);return result;}
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception error){throw new IllegalStateException("Schema 无法序列化",error);}}
    private Map<String,Object>readMap(Object value){try{return value==null?new LinkedHashMap<>():mapper.readValue(String.valueOf(value),MAP);}catch(Exception error){throw new IllegalStateException("Schema 版本 JSON 已损坏",error);}}
    @SuppressWarnings("unchecked")private Map<String,Object>map(Object value){return value instanceof Map?new LinkedHashMap<>((Map<String,Object>)value):new LinkedHashMap<>();}
    @SuppressWarnings("unchecked")private List<Map<String,Object>>maps(Object value){return value instanceof List?(List<Map<String,Object>>)value:List.of();}
    private boolean bool(Object value){return Boolean.TRUE.equals(value)||"1".equals(String.valueOf(value))||"true".equalsIgnoreCase(String.valueOf(value));}
    private boolean boolDefault(Object value,boolean fallback){return value==null?fallback:bool(value);}
    private int number(Object value,int fallback){return value instanceof Number?((Number)value).intValue():fallback;}
    private String text(Object value){return value==null?"":String.valueOf(value).trim();}private String text(Object value,String fallback){String result=text(value);return result.isEmpty()?fallback:result;}
    private String sha256(String value){try{byte[]bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte item:bytes)out.append(String.format("%02x",item));return out.toString();}catch(Exception error){throw new IllegalStateException(error);}}
    private static final class SetUnion{private static List<String>keys(Map<String,Object>a,Map<String,Object>b){java.util.Set<String>keys=new java.util.TreeSet<>();keys.addAll(a.keySet());keys.addAll(b.keySet());return new ArrayList<>(keys);}}
}
