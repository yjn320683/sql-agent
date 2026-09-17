package com.yjn.sqlagent.datamap.service;

import com.yjn.sqlagent.datamap.graph.GraphStoreClient;
import com.yjn.sqlagent.datamap.graph.GraphStoreUnavailableException;
import com.yjn.sqlagent.datamap.project.DataMapProjectionScheduler;
import com.yjn.sqlagent.datamap.store.LineageOutboxService;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 数据地图统一查询服务。所有关系查询均以 Neo4j 当前生效代次为准。 */
@Service
public class DataMapService {
    private static final int NODE_LIMIT = 500;
    private static final int EDGE_LIMIT = 1000;
    private final GraphStoreClient graph;
    private final JdbcTemplate jdbc;
    private final LineageOutboxService outbox;
    private final DataMapProjectionScheduler scheduler;

    public DataMapService(GraphStoreClient graph, JdbcTemplate jdbc, LineageOutboxService outbox,
                          DataMapProjectionScheduler scheduler) {
        this.graph=graph;this.jdbc=jdbc;this.outbox=outbox;this.scheduler=scheduler;
    }

    public Map<String, Object> overview() {
        long generation = outbox.activeGeneration();
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("graphConfigured",graph.isConfigured()); result.put("graphAvailable",graph.ping());
        result.put("activeGeneration",generation);
        result.put("assetCount",scalar("MATCH (v:TaskVersion {generation:$generation})-[:READS|WRITES]->(a:Asset) RETURN count(DISTINCT a) AS value",generation));
        result.put("columnCount",scalar("MATCH (c:Column)-[r:DERIVES_TO|USED_BY]-() WHERE r.generation=$generation RETURN count(DISTINCT c) AS value",generation));
        result.put("taskCount",scalar("MATCH (v:TaskVersion {generation:$generation}) RETURN count(v) AS value",generation));
        result.put("pendingProjection",count("SELECT COUNT(*) FROM data_map_graph_outbox WHERE status IN('PENDING','PROCESSING','FAILED')"));
        result.put("completeTasks",count("SELECT COUNT(*) FROM data_map_lineage_task_state WHERE parse_status='COMPLETE'"));
        result.put("partialTasks",count("SELECT COUNT(*) FROM data_map_lineage_task_state WHERE parse_status='PARTIAL'"));
        result.put("latestRuns",runs(1,5).get("records"));
        return result;
    }

    public Map<String,Object> search(String keyword,String catalog,int page,int pageSize){
        requireGraph();int safePage=Math.max(1,page),safeSize=Math.max(1,Math.min(100,pageSize));
        Map<String,Object>params=new LinkedHashMap<>();params.put("keyword",text(keyword).toLowerCase(Locale.ROOT));params.put("catalog",text(catalog).toLowerCase(Locale.ROOT));params.put("generation",outbox.activeGeneration());params.put("skip",(safePage-1)*safeSize);params.put("limit",safeSize);
        String where="WHERE ($keyword='' OR toLower(a.qualifiedName) CONTAINS $keyword) AND ($catalog='' OR $catalog='all' OR toLower(a.catalog)=$catalog)";
        String current="MATCH (v:TaskVersion {generation:$generation})-[:READS|WRITES]->(a:Asset) ";
        List<Map<String,Object>>rows=graph.query(current+where+" RETURN DISTINCT properties(a) AS asset ORDER BY asset.qualifiedName SKIP $skip LIMIT $limit",params);
        List<Map<String,Object>>records=new ArrayList<>();for(Map<String,Object>row:rows){Map<String,Object>a=map(row.get("asset"));a.put("id","asset:"+a.get("assetKey"));a.put("nodeType","TABLE");records.add(a);}
        List<Map<String,Object>>totals=graph.query(current+where+" RETURN count(DISTINCT a) AS total",params);long total=totals.isEmpty()?0:number(totals.get(0).get("total"));
        return Map.of("records",records,"total",total,"page",safePage,"pageSize",safeSize,"complete",true,"missingReasons",List.of());
    }

    public Map<String,Object> graph(String catalog,String database,String table,String column,String direction,int depth,String view){
        requireGraph();long generation=outbox.activeGeneration();int safeDepth=Math.max(1,Math.min(3,depth));String normalizedView=text(view).isEmpty()?"TABLE":text(view).toUpperCase(Locale.ROOT);
        if("COLUMN".equals(normalizedView)&&!text(column).isEmpty())return columnGraph(assetKey(catalog,database,table)+"|"+text(column).toLowerCase(Locale.ROOT),direction,safeDepth,generation);
        Map<String,Object>base=tableGraph(assetKey(catalog,database,table),direction,safeDepth,generation);
        if("TASK".equals(normalizedView))return taskGraph(base);
        base.put("view","TABLE");return base;
    }

    public Map<String,Object> impact(Map<String,Object>request){
        String catalog=text(request.get("catalog")),database=text(request.get("database")),table=text(request.get("table"));List<String>columns=strings(request.get("columns"));
        List<Map<String,Object>>graphs=new ArrayList<>();if(columns.isEmpty())graphs.add(graph(catalog,database,table,"","DOWNSTREAM",3,"TABLE"));else for(String column:columns)graphs.add(graph(catalog,database,table,column,"DOWNSTREAM",3,"COLUMN"));
        Map<String,Map<String,Object>>nodes=new LinkedHashMap<>(),edges=new LinkedHashMap<>();boolean complete=true;List<String>missing=new ArrayList<>();
        for(Map<String,Object>item:graphs){for(Map<String,Object>node:maps(item.get("nodes")))nodes.put(text(node.get("id")),node);for(Map<String,Object>edge:maps(item.get("edges")))edges.put(text(edge.get("id")),edge);complete&=Boolean.TRUE.equals(item.get("complete"));missing.addAll(strings(item.get("missingReasons")));}
        List<Map<String,Object>>tasks=new ArrayList<>(),assets=new ArrayList<>();for(Map<String,Object>node:nodes.values()){if("TASK".equals(node.get("nodeType")))tasks.add(node);else assets.add(node);}
        Map<String,Object>result=new LinkedHashMap<>();result.put("asset",Map.of("catalog",catalog,"database",database,"table",table));result.put("columns",columns);result.put("changeType",text(request.get("changeType")));result.put("affectedTasks",tasks);result.put("affectedAssets",assets);result.put("edges",new ArrayList<>(edges.values()));result.put("fieldImpactKnown",columns.isEmpty()||!edges.isEmpty());result.put("complete",complete);result.put("missingReasons",new ArrayList<>(new LinkedHashSet<>(missing)));return result;
    }

    public Map<String,Object> runs(int page,int pageSize){int safePage=Math.max(1,page),safeSize=Math.max(1,Math.min(100,pageSize));List<Map<String,Object>>records=jdbc.queryForList("SELECT * FROM data_map_lineage_run ORDER BY id DESC LIMIT ? OFFSET ?",safeSize,(safePage-1)*safeSize);return Map.of("records",records,"total",count("SELECT COUNT(*) FROM data_map_lineage_run"),"page",safePage,"pageSize",safeSize);}
    public Map<String,Object> coverage(){return Map.of("complete",count("SELECT COUNT(*) FROM data_map_lineage_task_state WHERE parse_status='COMPLETE'"),"partial",count("SELECT COUNT(*) FROM data_map_lineage_task_state WHERE parse_status='PARTIAL'"),"failed",count("SELECT COUNT(*) FROM data_map_lineage_task_state WHERE parse_status='FAILED'"),"pendingProjection",count("SELECT COUNT(*) FROM data_map_graph_outbox WHERE status IN('PENDING','PROCESSING','FAILED')"));}
    public Map<String,Object> retry(String scope,long taskId){int changed=jdbc.update("UPDATE data_map_graph_outbox SET status='PENDING',available_at=NOW(),last_error=NULL WHERE task_scope=? AND task_id=? AND status='FAILED'",scope.toUpperCase(Locale.ROOT),taskId);return Map.of("accepted",changed>0,"events",changed);}
    public Map<String,Object> reproject(){outbox.enqueueMissing(1000);int count=scheduler.projectPendingNow();return Map.of("projected",count,"generation",outbox.activeGeneration());}
    public Map<String,Object> rebuild(){long generation=outbox.beginFullGeneration();return Map.of("accepted",true,"generation",generation,"message","已创建全量图代次，后台投影完整后自动切换");}

    private Map<String,Object> tableGraph(String seedKey,String direction,int depth,long generation){
        Map<String,Object>params=Map.of("seed",seedKey,"generation",generation,"limit",EDGE_LIMIT);int relationDepth=depth*2;
        String cypher="MATCH (seed:Asset {assetKey:$seed}) MATCH p=(seed)-[:READS|WRITES*1.."+relationDepth+"]-(n) WHERE all(x IN [z IN nodes(p) WHERE z:TaskVersion] WHERE x.generation=$generation) AND all(r IN relationships(p) WHERE r.generation=$generation) UNWIND relationships(p) AS r WITH DISTINCT r,startNode(r) AS s,endNode(r) AS t RETURN labels(s) AS sourceLabels,properties(s) AS source,type(r) AS relationType,properties(r) AS relation,labels(t) AS targetLabels,properties(t) AS target LIMIT $limit";
        return assemble(graph.query(cypher,params),seedKey,direction,depth,"TABLE");
    }
    private Map<String,Object> columnGraph(String seedKey,String direction,int depth,long generation){
        Map<String,Object>params=Map.of("seed",seedKey,"generation",generation,"limit",EDGE_LIMIT);String cypher="MATCH (seed:Column {columnKey:$seed}) MATCH p=(seed)-[:DERIVES_TO*1.."+depth+"]-(n:Column) WHERE all(r IN relationships(p) WHERE r.generation=$generation) UNWIND relationships(p) AS r WITH DISTINCT r,startNode(r) AS s,endNode(r) AS t RETURN labels(s) AS sourceLabels,properties(s) AS source,type(r) AS relationType,properties(r) AS relation,labels(t) AS targetLabels,properties(t) AS target LIMIT $limit";return assemble(graph.query(cypher,params),seedKey,direction,depth,"COLUMN");}

    private Map<String,Object> assemble(List<Map<String,Object>>rows,String seed,String direction,int depth,String view){Map<String,Map<String,Object>>allNodes=new LinkedHashMap<>(),allEdges=new LinkedHashMap<>();String wanted=text(direction).isEmpty()?"BOTH":text(direction).toUpperCase(Locale.ROOT);for(Map<String,Object>row:rows){Map<String,Object>s=node(list(row.get("sourceLabels")),map(row.get("source"))),t=node(list(row.get("targetLabels")),map(row.get("target")));Map<String,Object>rel=map(row.get("relation"));String type=text(row.get("relationType"));String sid=text(s.get("id")),tid=text(t.get("id"));if("READS".equals(type)){String tmp=sid;sid=tid;tid=tmp;Map<String,Object>n=s;s=t;t=n;}allNodes.put(sid,s);allNodes.put(tid,t);String id=sid+">"+tid+":"+type+":"+text(rel.get("versionKey"));Map<String,Object>edge=new LinkedHashMap<>();edge.put("id",id);edge.put("source",sid);edge.put("target",tid);edge.put("relationKind",type);edge.put("usageType",rel.get("usageType"));edge.put("expression",rel.get("expression"));edge.put("direct",rel.get("direct"));allEdges.put(id,edge);}String seedId=("COLUMN".equals(view)?"column:":"asset:")+seed;Set<String>kept=new LinkedHashSet<>();int maxHops="TABLE".equals(view)?depth*2:depth;if(!"UPSTREAM".equals(wanted))kept.addAll(walkEdges(seedId,allEdges.values(),true,maxHops));if(!"DOWNSTREAM".equals(wanted))kept.addAll(walkEdges(seedId,allEdges.values(),false,maxHops));Map<String,Map<String,Object>>nodes=new LinkedHashMap<>();List<Map<String,Object>>edges=new ArrayList<>();for(String id:kept){Map<String,Object>edge=allEdges.get(id);if(edge==null)continue;edges.add(edge);String sid=text(edge.get("source")),tid=text(edge.get("target"));if(allNodes.containsKey(sid))nodes.put(sid,allNodes.get(sid));if(allNodes.containsKey(tid))nodes.put(tid,allNodes.get(tid));}Map<String,Object>result=new LinkedHashMap<>();result.put("start",Map.of("id",seedId));result.put("nodes",new ArrayList<>(nodes.values()));result.put("edges",edges);result.put("depth",depth);result.put("direction",wanted);result.put("view",view);boolean truncated=rows.size()>=EDGE_LIMIT||nodes.size()>=NODE_LIMIT;result.put("complete",!truncated);result.put("missingReasons",truncated?List.of("graph_limit"):List.of());result.put("activeGeneration",outbox.activeGeneration());return result;}
    private Set<String> walkEdges(String seed,Collection<Map<String,Object>>edges,boolean forward,int maxHops){Set<String>result=new LinkedHashSet<>(),seen=new LinkedHashSet<>();Set<String>frontier=new LinkedHashSet<>();frontier.add(seed);seen.add(seed);for(int hop=0;hop<maxHops&&!frontier.isEmpty();hop++){Set<String>next=new LinkedHashSet<>();for(Map<String,Object>edge:edges){String from=text(edge.get(forward?"source":"target")),to=text(edge.get(forward?"target":"source"));if(!frontier.contains(from))continue;result.add(text(edge.get("id")));if(seen.add(to))next.add(to);}frontier=next;}return result;}
    private Map<String,Object> taskGraph(Map<String,Object>base){Map<String,Map<String,Object>>tasks=new LinkedHashMap<>();Map<String,List<String>>readers=new LinkedHashMap<>(),writers=new LinkedHashMap<>();for(Map<String,Object>edge:maps(base.get("edges"))){String source=text(edge.get("source")),target=text(edge.get("target"));if(source.startsWith("asset:")&&target.startsWith("task:"))readers.computeIfAbsent(source,k->new ArrayList<>()).add(target);if(source.startsWith("task:")&&target.startsWith("asset:"))writers.computeIfAbsent(target,k->new ArrayList<>()).add(source);}for(Map<String,Object>node:maps(base.get("nodes")))if("TASK".equals(node.get("nodeType")))tasks.put(text(node.get("id")),node);List<Map<String,Object>>edges=new ArrayList<>();for(String asset:writers.keySet())for(String from:writers.get(asset))for(String to:readers.getOrDefault(asset,List.of()))if(!from.equals(to)){String id=from+">"+to+":"+asset;edges.add(Map.of("id",id,"source",from,"target",to,"relationKind","DATA_FLOW","viaAsset",asset));}Map<String,Object>result=new LinkedHashMap<>(base);result.put("nodes",new ArrayList<>(tasks.values()));result.put("edges",edges);result.put("view","TASK");return result;}

    private Map<String,Object>node(List<Object>labels,Map<String,Object>props){Map<String,Object>node=new LinkedHashMap<>(props);boolean task=labels.stream().anyMatch(v->"TaskVersion".equals(String.valueOf(v)));boolean column=labels.stream().anyMatch(v->"Column".equals(String.valueOf(v)));if(task){node.put("id","task:"+props.get("taskScope")+":"+props.get("taskId"));node.put("nodeType","TASK");node.put("taskName",props.get("taskName"));}else if(column){node.put("id","column:"+props.get("columnKey"));node.put("nodeType","COLUMN");node.put("column",props.get("name"));}else{node.put("id","asset:"+props.get("assetKey"));node.put("nodeType","TABLE");}return node;}
    private long scalar(String cypher,long generation){if(!graph.isConfigured())return 0;try{List<Map<String,Object>>rows=graph.query(cypher,Map.of("generation",generation));return rows.isEmpty()?0:number(rows.get(0).get("value"));}catch(RuntimeException ignored){return 0;}}
    private void requireGraph(){if(!graph.isConfigured()||!graph.ping())throw new GraphStoreUnavailableException("Neo4j 血缘图存储不可用，解析事实仍保存在 MySQL，恢复后会自动补投影");}
    private long count(String sql){try{Long value=jdbc.queryForObject(sql,Long.class);return value==null?0:value;}catch(RuntimeException ignored){return 0;}}
    private String assetKey(String catalog,String database,String table){String c=text(catalog);if(c.isEmpty())c="hive";return(c+"|"+text(database)+"|"+text(table)).toLowerCase(Locale.ROOT);}
    @SuppressWarnings("unchecked")private Map<String,Object>map(Object value){return value instanceof Map?new LinkedHashMap<>((Map<String,Object>)value):new LinkedHashMap<>();}@SuppressWarnings("unchecked")private List<Map<String,Object>>maps(Object value){return value instanceof List?(List<Map<String,Object>>)value:List.of();}@SuppressWarnings("unchecked")private List<Object>list(Object value){return value instanceof List?(List<Object>)value:List.of();}@SuppressWarnings("unchecked")private List<String>strings(Object value){if(!(value instanceof Collection))return List.of();List<String>r=new ArrayList<>();for(Object item:(Collection<?>)value)r.add(text(item));return r;}private String text(Object value){return value==null?"":String.valueOf(value).trim();}private long number(Object value){return value instanceof Number?((Number)value).longValue():0L;}
}
