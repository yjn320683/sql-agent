package com.yjn.sqlagent.service;

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

/** 基于版本快照索引的离线、实时统一资产血缘查询。 */
@Service
public class AssetLineageService {
    private static final int RELATION_LIMIT = 20_000;
    private static final int NODE_LIMIT = 500;
    private final JdbcTemplate jdbc;

    public AssetLineageService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Map<String, Object> search(String keyword, String catalog, int page, int pageSize) {
        List<Relation> relations = currentRelations();
        Map<String, Map<String, Object>> assets = new LinkedHashMap<>();
        for (Relation relation : relations) {
            addAsset(assets, relation.source);
            addAsset(assets, relation.target);
        }
        String term = text(keyword).toLowerCase(Locale.ROOT);
        String wantedCatalog = text(catalog).toLowerCase(Locale.ROOT);
        List<Map<String, Object>> matched = new ArrayList<>();
        for (Map<String, Object> asset : assets.values()) {
            String name = text(asset.get("qualifiedName")).toLowerCase(Locale.ROOT);
            if (!term.isEmpty() && !name.contains(term)) continue;
            if (!wantedCatalog.isEmpty() && !"all".equals(wantedCatalog)
                    && !wantedCatalog.equals(text(asset.get("catalog")).toLowerCase(Locale.ROOT))) continue;
            matched.add(asset);
        }
        int safePage = Math.max(1, page), safeSize = Math.max(1, Math.min(100, pageSize));
        int from = Math.min(matched.size(), (safePage - 1) * safeSize);
        int to = Math.min(matched.size(), from + safeSize);
        long pending = pendingIndexCount();
        boolean complete = relations.size() < RELATION_LIMIT && pending == 0;
        List<String> missing = new ArrayList<>();
        if (relations.size() >= RELATION_LIMIT) missing.add("relation_scan_limit");
        if (pending > 0) missing.add("lineage_index_pending");
        if (pending < 0) missing.add("lineage_index_unavailable");
        return Map.of("records", new ArrayList<>(matched.subList(from, to)), "total", matched.size(),
                "page", safePage, "pageSize", safeSize, "complete", complete, "missingReasons", missing);
    }

    public Map<String, Object> graph(String catalog, String database, String table, String column,
                                     String direction, int depth) {
        return graph(currentRelations(), catalog, database, table, column, direction, depth, pendingIndexCount());
    }

    private Map<String, Object> graph(List<Relation> relations, String catalog, String database, String table,
                                      String column, String direction, int depth, long pending) {
        Asset start = new Asset(defaultCatalog(catalog), text(database), required(table), empty(column));
        String normalizedDirection = direction == null ? "BOTH" : direction.trim().toUpperCase(Locale.ROOT);
        int maxDepth = Math.max(1, Math.min(3, depth));
        Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        Map<String, Map<String, Object>> edges = new LinkedHashMap<>();
        Map<String, List<GraphEdge>> adjacency = graphEdges(relations);
        Deque<Hop> queue = new ArrayDeque<>();
        Set<String> visited = new LinkedHashSet<>();
        queue.add(new Hop(start.key(), 0));
        putAssetNode(nodes, start);
        boolean truncated = relations.size() >= RELATION_LIMIT;
        while (!queue.isEmpty() && nodes.size() < NODE_LIMIT) {
            Hop hop = queue.removeFirst();
            if (!visited.add(hop.key) || hop.depth >= maxDepth) continue;
            for (GraphEdge edge : adjacency.getOrDefault(hop.key, List.of())) {
                boolean outgoing = edge.from.equals(hop.key);
                if ("UPSTREAM".equals(normalizedDirection) && outgoing) continue;
                if ("DOWNSTREAM".equals(normalizedDirection) && !outgoing) continue;
                String next = outgoing ? edge.to : edge.from;
                nodes.putIfAbsent(edge.from, edge.fromNode);
                nodes.putIfAbsent(edge.to, edge.toNode);
                edges.putIfAbsent(edge.id(), edge.toMap());
                if (!visited.contains(next)) queue.addLast(new Hop(next, hop.depth + 1));
                if (nodes.size() >= NODE_LIMIT) { truncated = true; break; }
            }
        }
        List<String> missing = new ArrayList<>();
        if (truncated) missing.add(nodes.size() >= NODE_LIMIT ? "graph_node_limit" : "relation_scan_limit");
        if (relations.stream().anyMatch(item -> !item.complete)) missing.add("lineage_snapshot_incomplete");
        if (pending > 0) missing.add("lineage_index_pending");
        if (pending < 0) missing.add("lineage_index_unavailable");
        return Map.of("start", start.toMap(), "nodes", new ArrayList<>(nodes.values()),
                "edges", new ArrayList<>(edges.values()), "depth", maxDepth, "direction", normalizedDirection,
                "complete", missing.isEmpty(), "missingReasons", unique(missing));
    }

    public Map<String, Object> impact(Map<String, Object> request) {
        String catalog = defaultCatalog(text(request.get("catalog")));
        String database = text(request.get("database"));
        String table = required(request.get("table"));
        List<String> columns = strings(request.get("columns"));
        List<Relation> relations = currentRelations();
        long pending = pendingIndexCount();
        List<Map<String, Object>> graphs = new ArrayList<>();
        if (columns.isEmpty()) graphs.add(graph(relations, catalog, database, table, "", "DOWNSTREAM", 3, pending));
        else for (String column : columns) graphs.add(graph(relations, catalog, database, table, column, "DOWNSTREAM", 3, pending));
        Map<String, Object> graph = mergeGraphs(graphs, catalog, database, table, columns);
        List<Map<String, Object>> affectedTasks = new ArrayList<>(), affectedAssets = new ArrayList<>();
        for (Map<String, Object> node : maps(graph.get("nodes"))) {
            if ("TASK".equals(node.get("nodeType"))) affectedTasks.add(node);
            else if (!isRequestedAsset(node, catalog, database, table, columns)) affectedAssets.add(node);
        }
        boolean fieldKnown = !columns.isEmpty() && maps(graph.get("edges")).stream().anyMatch(edge -> {
            Object sourceColumn = edge.get("sourceColumn");
            return sourceColumn != null && columns.stream().anyMatch(item -> item.equalsIgnoreCase(String.valueOf(sourceColumn)));
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("asset", Map.of("catalog", catalog, "database", database, "table", table));
        result.put("changeType", text(request.get("changeType")));
        result.put("columns", columns);
        result.put("affectedTasks", affectedTasks);
        result.put("affectedAssets", affectedAssets);
        result.put("edges", graph.get("edges"));
        result.put("fieldImpactKnown", columns.isEmpty() || fieldKnown);
        result.put("complete", graph.get("complete"));
        result.put("missingReasons", graph.get("missingReasons"));
        return result;
    }

    private Map<String, Object> mergeGraphs(List<Map<String, Object>> graphs, String catalog, String database,
                                             String table, List<String> columns) {
        Map<String, Map<String, Object>> nodes = new LinkedHashMap<>(), edges = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        boolean complete = true;
        for (Map<String, Object> graph : graphs) {
            for (Map<String, Object> node : maps(graph.get("nodes"))) nodes.putIfAbsent(text(node.get("id")), node);
            for (Map<String, Object> edge : maps(graph.get("edges"))) edges.putIfAbsent(text(edge.get("id")), edge);
            complete &= Boolean.TRUE.equals(graph.get("complete"));
            for (Object reason : graph.get("missingReasons") instanceof Collection ? (Collection<?>) graph.get("missingReasons") : List.of()) missing.add(text(reason));
        }
        Map<String, Object> start = new Asset(catalog, database, table, columns.size() == 1 ? columns.get(0) : "").toMap();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("start", start); result.put("nodes", new ArrayList<>(nodes.values()));
        result.put("edges", new ArrayList<>(edges.values())); result.put("depth", 3);
        result.put("direction", "DOWNSTREAM"); result.put("complete", complete);
        result.put("missingReasons", unique(missing));
        return result;
    }

    private boolean isRequestedAsset(Map<String, Object> node, String catalog, String database,
                                     String table, List<String> columns) {
        if (!catalog.equalsIgnoreCase(text(node.get("catalog")))
                || !database.equalsIgnoreCase(text(node.get("database")))
                || !table.equalsIgnoreCase(text(node.get("table")))) return false;
        return columns.isEmpty() || columns.stream().anyMatch(column -> column.equalsIgnoreCase(text(node.get("column"))));
    }

    private List<Relation> currentRelations() {
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList("SELECT r.*,CASE WHEN r.task_scope='OFFLINE' THEN o.name ELSE t.task_name END task_name,"
                    + "CASE WHEN r.task_scope='OFFLINE' THEN 'offline' ELSE t.task_type END task_type,s.complete_flag "
                    + "FROM task_lineage_relation r JOIN task_lineage_snapshot s ON s.id=r.snapshot_id "
                    + "LEFT JOIN sql_task o ON r.task_scope='OFFLINE' AND o.id=r.task_id AND o.archived=0 "
                    + "AND o.effective_version_no=r.version_no "
                    + "LEFT JOIN rt_task t ON r.task_scope='REALTIME' AND t.id=r.task_id AND t.status<>'deleted' "
                    + "AND r.version_no=(SELECT MAX(v.version_no) FROM rt_task_version v WHERE v.task_id=t.id) "
                    + "WHERE r.relation_kind<>'SNAPSHOT' AND ((r.task_scope='OFFLINE' AND o.id IS NOT NULL) "
                    + "OR (r.task_scope='REALTIME' AND t.id IS NOT NULL)) ORDER BY r.id LIMIT " + RELATION_LIMIT);
        } catch (RuntimeException error) {
            rows = List.of();
        }
        List<Relation> result = new ArrayList<>();
        for (Map<String, Object> row : rows) result.add(Relation.from(row));
        addRealtimeReferences(result);
        return result;
    }

    private long pendingIndexCount() {
        try {
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM task_lineage_snapshot s WHERE NOT EXISTS ("
                    + "SELECT 1 FROM task_lineage_relation r WHERE r.snapshot_id=s.id AND r.relation_kind='SNAPSHOT')", Long.class);
            return count == null ? 0 : count;
        } catch (RuntimeException ignored) { return -1; }
    }

    private void addRealtimeReferences(List<Relation> result) {
        try {
            for (Map<String, Object> row : jdbc.queryForList("SELECT x.task_id,t.task_name,t.task_type,x.reference_role,"
                    + "r.catalog_name,r.database_name,r.table_name FROM rt_task_table_reference x "
                    + "JOIN rt_task t ON t.id=x.task_id AND t.status<>'deleted' JOIN rt_realtime_table r ON r.id=x.realtime_table_id")) {
                Asset asset = new Asset(text(row.get("catalog_name")), text(row.get("database_name")), text(row.get("table_name")), "");
                boolean input = "INPUT".equals(row.get("reference_role"));
                result.add(Relation.declared(number(row.get("task_id")), text(row.get("task_name")), text(row.get("task_type")), input, asset));
            }
        } catch (RuntimeException ignored) { /* 实时表升级未安装时仍返回已有离线索引。 */ }
    }

    private Map<String, List<GraphEdge>> graphEdges(List<Relation> relations) {
        Map<String, List<GraphEdge>> result = new LinkedHashMap<>();
        for (Relation relation : relations) {
            String taskId = "task:" + relation.scope.toLowerCase(Locale.ROOT) + ":" + relation.taskId;
            Map<String, Object> task = new LinkedHashMap<>();
            task.put("id", taskId); task.put("nodeType", "TASK"); task.put("taskScope", relation.scope);
            task.put("taskId", relation.taskId); task.put("taskName", relation.taskName); task.put("taskType", relation.taskType);
            if (relation.source != null) addGraphEdge(result, new GraphEdge(relation.source.key(), taskId,
                    relation.source.toNode(), task, relation.kind, relation.usageType, relation.source.column));
            if (relation.target != null) addGraphEdge(result, new GraphEdge(taskId, relation.target.key(),
                    task, relation.target.toNode(), relation.kind, relation.usageType,
                    relation.source == null ? null : relation.source.column));
        }
        return result;
    }

    private void addGraphEdge(Map<String, List<GraphEdge>> values, GraphEdge edge) {
        values.computeIfAbsent(edge.from, ignored -> new ArrayList<>()).add(edge);
        values.computeIfAbsent(edge.to, ignored -> new ArrayList<>()).add(edge);
    }
    private void addAsset(Map<String, Map<String, Object>> assets, Asset asset) { if (asset != null) assets.putIfAbsent(asset.key(), asset.toMap()); }
    private void putAssetNode(Map<String, Map<String, Object>> nodes, Asset asset) { nodes.put(asset.key(), asset.toNode()); }
    private String defaultCatalog(String value) { String normalized = text(value); return normalized.isEmpty() ? "hive" : normalized.toLowerCase(Locale.ROOT); }
    private String required(Object value) { String result = text(value); if (result.isEmpty()) throw new IllegalArgumentException("table 不能为空"); return result; }
    private String empty(Object value) { return text(value); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private long number(Object value) { return ((Number) value).longValue(); }
    @SuppressWarnings("unchecked") private List<Map<String, Object>> maps(Object value) { return value instanceof List ? (List<Map<String, Object>>) value : List.of(); }
    private List<String> strings(Object value) { List<String> result=new ArrayList<>(); if(value instanceof Collection)for(Object item:(Collection<?>)value)if(!text(item).isEmpty())result.add(text(item));return result; }
    private List<String> unique(List<String> values) { return new ArrayList<>(new LinkedHashSet<>(values)); }
    private String assetKey(String catalog,String database,String table,String column){return new Asset(catalog,database,table,column).key();}

    private static final class Hop { private final String key; private final int depth; private Hop(String key,int depth){this.key=key;this.depth=depth;} }
    private static final class Asset {
        private final String catalog,database,table,column;
        private Asset(String catalog,String database,String table,String column){this.catalog=value(catalog,"hive");this.database=value(database,"");this.table=value(table,"");this.column=value(column,"");}
        private String key(){return "asset:"+catalog.toLowerCase(Locale.ROOT)+":"+database.toLowerCase(Locale.ROOT)+":"+table.toLowerCase(Locale.ROOT)+(column.isEmpty()?"":":"+column.toLowerCase(Locale.ROOT));}
        private String qualified(){return catalog+"."+(database.isEmpty()?"":database+".")+table+(column.isEmpty()?"":"."+column);}
        private Map<String,Object>toMap(){Map<String,Object>r=new LinkedHashMap<>();r.put("id",key());r.put("catalog",catalog);r.put("database",database);r.put("table",table);r.put("column",column.isEmpty()?null:column);r.put("qualifiedName",qualified());return r;}
        private Map<String,Object>toNode(){Map<String,Object>r=toMap();r.put("nodeType",column.isEmpty()?"TABLE":"COLUMN");return r;}
        private static String value(String value,String fallback){return value==null||value.trim().isEmpty()?fallback:value.trim();}
    }
    private static final class Relation {
        private final String scope,kind,taskName,taskType,usageType; private final long taskId; private final Asset source,target; private final boolean complete;
        private Relation(String scope,long taskId,String taskName,String taskType,String kind,Asset source,Asset target,String usageType,boolean complete){this.scope=scope;this.taskId=taskId;this.taskName=taskName;this.taskType=taskType;this.kind=kind;this.source=source;this.target=target;this.usageType=usageType;this.complete=complete;}
        private static Relation from(Map<String,Object>r){String kind=String.valueOf(r.get("relation_kind"));return new Relation(String.valueOf(r.get("task_scope")),((Number)r.get("task_id")).longValue(),String.valueOf(r.get("task_name")),String.valueOf(r.get("task_type")),kind,asset(r,"source"),asset(r,"target"),r.get("usage_type")==null?null:String.valueOf(r.get("usage_type")),Boolean.TRUE.equals(r.get("complete_flag"))||"1".equals(String.valueOf(r.get("complete_flag"))));}
        private static Relation declared(long id,String name,String type,boolean input,Asset asset){return new Relation("REALTIME",id,name,type,input?"TABLE_INPUT":"TABLE_OUTPUT",input?asset:null,input?null:asset,null,true);}
        private static Asset asset(Map<String,Object>r,String p){Object table=r.get(p+"_table");if(table==null)return null;return new Asset(r.get(p+"_catalog")==null?"hive":String.valueOf(r.get(p+"_catalog")),r.get(p+"_database")==null?"":String.valueOf(r.get(p+"_database")),String.valueOf(table),r.get(p+"_column")==null?"":String.valueOf(r.get(p+"_column")));}
    }
    private static final class GraphEdge {
        private final String from,to,kind,usageType,sourceColumn;private final Map<String,Object>fromNode,toNode;
        private GraphEdge(String from,String to,Map<String,Object>fromNode,Map<String,Object>toNode,String kind,String usageType,String sourceColumn){this.from=from;this.to=to;this.fromNode=fromNode;this.toNode=toNode;this.kind=kind;this.usageType=usageType;this.sourceColumn=sourceColumn;}
        private String id(){return from+">"+to+":"+kind+":"+(usageType==null?"":usageType);}
        private Map<String,Object>toMap(){Map<String,Object>r=new LinkedHashMap<>();r.put("id",id());r.put("source",from);r.put("target",to);r.put("relationKind",kind);r.put("usageType",usageType);r.put("sourceColumn",sourceColumn);return r;}
    }
}
