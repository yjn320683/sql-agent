package com.yjn.sqlagent.realtime.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjn.sqlagent.datamap.project.LineageFactEnricher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 同步与出仓没有 SQL，使用已保存的确定性配置补充字段级血缘。 */
@Component
public class RealtimeLineageFactEnricher implements LineageFactEnricher {
    private static final Set<String> METADATA_COLUMNS = Set.of("database_name", "table_name", "op_ts");
    private static final TypeReference<List<Map<String,Object>>> LIST = new TypeReference<List<Map<String,Object>>>() { };
    private final JdbcTemplate jdbc; private final ObjectMapper mapper; private final RealtimeServerService servers;
    public RealtimeLineageFactEnricher(JdbcTemplate jdbc,ObjectMapper mapper,RealtimeServerService servers){this.jdbc=jdbc;this.mapper=mapper;this.servers=servers;}

    @Override public boolean supports(String scope,String type){return "REALTIME".equalsIgnoreCase(scope)&&("sync".equalsIgnoreCase(type)||"export".equalsIgnoreCase(type));}
    @Override public Map<String,Object>enrich(long taskId,Long versionId,int versionNo,Map<String,Object>facts){return "export".equalsIgnoreCase(taskType(taskId))?export(taskId,facts):sync(taskId,facts);}

    private Map<String,Object>export(long taskId,Map<String,Object>original){Map<String,Object>facts=copy(original);List<Map<String,Object>>statements=new ArrayList<>(maps(facts.get("statements")));int index=statements.size();for(Map<String,Object>row:jdbc.queryForList("SELECT r.database_name source_database,r.table_name source_table,m.target_database,m.target_table,m.column_mapping_json FROM rt_export_task_table_mapping m JOIN rt_realtime_table r ON r.id=m.realtime_table_id WHERE m.task_id=? ORDER BY m.sort_order,m.id",taskId)){List<Map<String,Object>>lineages=new ArrayList<>();for(Map<String,Object>mapping:jsonList(row.get("column_mapping_json"))){String source=text(mapping.get("sourceColumn")),target=text(mapping.get("targetColumn"));if(source.isEmpty()||target.isEmpty())continue;lineages.add(lineage(table("mysql",text(row.get("target_database")),text(row.get("target_table"))),target,source("paimon",text(row.get("source_database")),text(row.get("source_table")),source),target,lineages.size()));}statements.add(statement(++index,"EXPORT_MAPPING",lineages));}facts.put("statements",statements);facts.put("statementCount",statements.size());return facts;}

    private Map<String,Object>sync(long taskId,Map<String,Object>original){Map<String,Object>facts=copy(original);List<Map<String,Object>>statements=new ArrayList<>(maps(facts.get("statements")));List<Map<String,Object>>diagnostics=new ArrayList<>(maps(facts.get("diagnostics")));List<Map<String,Object>>mappings=jdbc.queryForList("SELECT source_server_id,source_database,source_table,target_database,target_table FROM rt_sync_task_table_mapping WHERE task_id=? ORDER BY sort_order,id",taskId);Map<Long,List<String>>tablesByServer=new LinkedHashMap<>();for(Map<String,Object>row:mappings)tablesByServer.computeIfAbsent(number(row.get("source_server_id")),ignored->new ArrayList<>()).add(text(row.get("source_table")));Map<String,Map<String,Object>>sourceSchemas=new LinkedHashMap<>();boolean metadataAvailable=true;for(Map.Entry<Long,List<String>>entry:tablesByServer.entrySet())try{for(Map.Entry<String,Map<String,Object>>schema:servers.schemas(entry.getKey(),entry.getValue()).entrySet())sourceSchemas.put(entry.getKey()+"|"+schema.getKey().toLowerCase(Locale.ROOT),schema.getValue());}catch(RuntimeException error){metadataAvailable=false;diagnostics.add(Map.of("code","SYNC_SOURCE_METADATA_UNAVAILABLE","severity","WARNING","message","同步源字段暂无法补全，保留表级血缘：serverId="+entry.getKey()));}int index=statements.size();boolean complete=Boolean.TRUE.equals(facts.get("complete"))&&metadataAvailable;for(Map<String,Object>row:mappings){long server=number(row.get("source_server_id"));String sourceTable=text(row.get("source_table")),targetTable=text(row.get("target_table"));Map<String,Object>schema=sourceSchemas.get(server+"|"+sourceTable.toLowerCase(Locale.ROOT));Set<String>sourceColumns=new LinkedHashSet<>();for(Map<String,Object>column:maps(schema==null?null:schema.get("columns")))sourceColumns.add(text(column.get("name")).toLowerCase(Locale.ROOT));List<Map<String,Object>>targetColumns=jdbc.queryForList("SELECT column_name name FROM rt_realtime_table_column c JOIN rt_realtime_table t ON t.id=c.realtime_table_id WHERE t.database_name=? AND t.table_name=? ORDER BY c.sort_order,c.id",text(row.get("target_database")),targetTable);List<Map<String,Object>>lineages=new ArrayList<>();for(Map<String,Object>column:targetColumns){String name=text(column.get("name"));String normalized=name.toLowerCase(Locale.ROOT);if(METADATA_COLUMNS.contains(normalized))continue;if(sourceColumns.contains(normalized))lineages.add(lineage(table("paimon",text(row.get("target_database")),targetTable),name,source("mysql",text(row.get("source_database")),sourceTable,name),name,lineages.size()));else if(metadataAvailable){complete=false;diagnostics.add(Map.of("code","SYNC_COLUMN_SOURCE_UNRESOLVED","severity","WARNING","message","同步目标字段无法从源Schema确定来源："+targetTable+"."+name));}}statements.add(statement(++index,"SYNC_MAPPING",lineages));}facts.put("statements",statements);facts.put("statementCount",statements.size());facts.put("diagnostics",diagnostics);facts.put("complete",complete);return facts;}

    private Map<String,Object>statement(int index,String type,List<Map<String,Object>>lineages){Map<String,Object>value=new LinkedHashMap<>();value.put("statementIndex",index);value.put("statementType",type);value.put("tableAccesses",List.of());value.put("columnLineages",lineages);value.put("columnUsages",List.of());value.put("joins",List.of());value.put("diagnostics",List.of());return value;}
    private Map<String,Object>lineage(Map<String,Object>target,String targetColumn,Map<String,Object>source,String expression,int ordinal){Map<String,Object>value=new LinkedHashMap<>();value.put("targetTable",target);value.put("targetColumn",targetColumn);value.put("ordinal",ordinal);value.put("expression",expression);value.put("sources",List.of(source));return value;}
    private Map<String,Object>source(String catalog,String database,String table,String column){Map<String,Object>value=table(catalog,database,table);value.put("column",column);value.put("direct",true);return value;}
    private Map<String,Object>table(String catalog,String database,String table){return new LinkedHashMap<>(Map.of("catalog",catalog,"database",database,"db",database,"table",table,"qualifiedName",catalog+"."+database+"."+table));}
    private String taskType(long taskId){try{return jdbc.queryForObject("SELECT task_type FROM rt_task WHERE id=?",String.class,taskId);}catch(RuntimeException error){return "";}}
    private Map<String,Object>copy(Map<String,Object>value){return new LinkedHashMap<>(value);}
    private List<Map<String,Object>>jsonList(Object value){try{return mapper.readValue(String.valueOf(value),LIST);}catch(Exception error){return List.of();}}
    @SuppressWarnings("unchecked")private List<Map<String,Object>>maps(Object value){return value instanceof List?(List<Map<String,Object>>)value:List.of();}private String text(Object value){return value==null?"":String.valueOf(value).trim();}private long number(Object value){return value instanceof Number?((Number)value).longValue():Long.parseLong(text(value));}
}
