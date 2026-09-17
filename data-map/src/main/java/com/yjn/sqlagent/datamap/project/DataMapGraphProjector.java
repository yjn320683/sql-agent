package com.yjn.sqlagent.datamap.project;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjn.sqlagent.datamap.graph.GraphStoreClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 将一个任务版本作为单条参数化 Cypher 原子投影，重试不会产生重复关系。 */
@Service
public class DataMapGraphProjector {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<Map<String, Object>>() { };
    private final GraphStoreClient graph;
    private final ObjectMapper mapper;
    private final List<LineageFactEnricher> enrichers;
    private final LineageProjectionMapper projectionMapper = new LineageProjectionMapper();

    public DataMapGraphProjector(GraphStoreClient graph, ObjectMapper mapper,
                                 List<LineageFactEnricher> enrichers) {
        this.graph = graph; this.mapper = mapper;
        this.enrichers = enrichers == null ? Collections.emptyList() : enrichers;
    }

    public void initializeSchema() {
        if (!graph.isConfigured()) return;
        for (String query : SCHEMA) graph.query(query, Collections.emptyMap());
    }

    public long project(Map<String, Object> row, long generation) {
        try {
            String scope = text(row.get("task_scope"));
            long taskId = number(row.get("task_id"));
            Long versionId = nullableNumber(row.get("version_id"));
            int versionNo = integer(row.get("version_no"));
            String taskType = text(row.get("task_type"));
            Map<String, Object> facts = mapper.readValue(text(row.get("lineage_json")), MAP);
            for (LineageFactEnricher enricher : enrichers) {
                if (enricher.supports(scope, taskType)) facts = enricher.enrich(taskId, versionId, versionNo, facts);
            }
            LineageProjection projection = projectionMapper.map(facts);
            long revision = Instant.now().toEpochMilli();
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("taskKey", scope.toLowerCase() + ":" + taskId);
            params.put("taskId", taskId); params.put("taskScope", scope);
            params.put("taskName", text(row.get("task_name"))); params.put("taskType", taskType);
            params.put("versionKey", scope.toLowerCase() + ":" + taskId + ":" + versionNo + ":g" + generation);
            params.put("versionId", versionId); params.put("versionNo", versionNo);
            params.put("snapshotId", number(row.get("snapshot_id")));
            params.put("parserVersion", text(row.get("parser_version")));
            params.put("checksum", text(row.get("sql_checksum")));
            params.put("complete", projection.complete); params.put("generation", generation);
            params.put("revision", revision); params.put("inputs", projection.inputs);
            params.put("outputs", projection.outputs); params.put("derivations", projection.derivations);
            params.put("usages", projection.usages); params.put("joins", projection.joins);
            params.put("diagnosticCount", projection.diagnostics.size());
            graph.query(PROJECT, params);
            return revision;
        } catch (RuntimeException error) { throw error; }
        catch (Exception error) { throw new IllegalStateException("血缘快照无法投影", error); }
    }

    /** 新代次切换后清理旧代次；查询已先切换，因此清理失败不会暴露半成品新图。 */
    public void retireOlderGenerations(long generation) {
        graph.query("MATCH ()-[r:DERIVES_TO|USED_BY|JOINED_WITH]->() WHERE r.generation<>$generation DELETE r", Map.of("generation", generation));
        graph.query("MATCH (v:TaskVersion) WHERE v.generation<>$generation DETACH DELETE v", Map.of("generation", generation));
        graph.query("MATCH (t:Task) WHERE NOT (t)-[:HAS_VERSION]->() DELETE t", Collections.emptyMap());
        graph.query("MATCH (a:Asset) WHERE NOT EXISTS { MATCH (:TaskVersion {generation:$generation})-[:READS|WRITES]->(a) } DETACH DELETE a", Map.of("generation", generation));
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private long number(Object value) { return value instanceof Number ? ((Number) value).longValue() : Long.parseLong(text(value)); }
    private int integer(Object value) { return value instanceof Number ? ((Number) value).intValue() : Integer.parseInt(text(value)); }
    private Long nullableNumber(Object value) { return value instanceof Number ? ((Number) value).longValue() : null; }

    private static final List<String> SCHEMA = List.of(
            "CREATE CONSTRAINT data_map_asset_key IF NOT EXISTS FOR (n:Asset) REQUIRE n.assetKey IS UNIQUE",
            "CREATE CONSTRAINT data_map_column_key IF NOT EXISTS FOR (n:Column) REQUIRE n.columnKey IS UNIQUE",
            "CREATE CONSTRAINT data_map_task_key IF NOT EXISTS FOR (n:Task) REQUIRE n.taskKey IS UNIQUE",
            "CREATE CONSTRAINT data_map_version_key IF NOT EXISTS FOR (n:TaskVersion) REQUIRE n.versionKey IS UNIQUE",
            "CREATE INDEX data_map_asset_name IF NOT EXISTS FOR (n:Asset) ON (n.catalog,n.database,n.table)",
            "CREATE INDEX data_map_task_type IF NOT EXISTS FOR (n:Task) ON (n.taskType)"
    );

    private static final String PROJECT = String.join(" ",
            "MERGE (t:Task {taskKey:$taskKey}) SET t.taskId=$taskId,t.taskScope=$taskScope,t.name=$taskName,t.taskType=$taskType,t.updatedAt=timestamp()",
            "MERGE (v:TaskVersion {versionKey:$versionKey}) SET v.versionId=$versionId,v.versionNo=$versionNo,v.snapshotId=$snapshotId,v.parserVersion=$parserVersion,v.checksum=$checksum,v.complete=$complete,v.diagnosticCount=$diagnosticCount,v.generation=$generation,v.revision=$revision,v.taskId=$taskId,v.taskScope=$taskScope,v.taskName=$taskName,v.taskType=$taskType",
            "MERGE (t)-[:HAS_VERSION]->(v)",
            "WITH v OPTIONAL MATCH (v)-[old:READS|WRITES]->() DELETE old",
            "WITH v OPTIONAL MATCH ()-[old:USED_BY]->(v) DELETE old",
            "WITH v OPTIONAL MATCH ()-[old:DERIVES_TO|JOINED_WITH]->() WHERE old.versionKey=$versionKey DELETE old",
            "WITH v CALL { WITH v UNWIND $inputs AS item MERGE (a:Asset {assetKey:item.assetKey}) SET a += item MERGE (v)-[:READS {generation:$generation}]->(a) RETURN count(*) AS inputCount }",
            "WITH v CALL { WITH v UNWIND $outputs AS item MERGE (a:Asset {assetKey:item.assetKey}) SET a += item MERGE (v)-[:WRITES {generation:$generation}]->(a) RETURN count(*) AS outputCount }",
            "WITH v CALL { WITH v UNWIND $derivations AS item MERGE (sa:Asset {assetKey:item.source.assetKey}) SET sa.catalog=item.source.catalog,sa.database=item.source.database,sa.table=item.source.table,sa.qualifiedName=item.source.qualifiedName,sa.assetType=item.source.assetType MERGE (sc:Column {columnKey:item.source.columnKey}) SET sc.name=item.source.column MERGE (sa)-[:HAS_COLUMN]->(sc) MERGE (ta:Asset {assetKey:item.target.assetKey}) SET ta.catalog=item.target.catalog,ta.database=item.target.database,ta.table=item.target.table,ta.qualifiedName=item.target.qualifiedName,ta.assetType=item.target.assetType MERGE (tc:Column {columnKey:item.target.columnKey}) SET tc.name=item.target.column MERGE (ta)-[:HAS_COLUMN]->(tc) MERGE (sc)-[r:DERIVES_TO {versionKey:$versionKey,statementIndex:item.statementIndex,ordinal:item.ordinal}]->(tc) SET r.expression=item.expression,r.direct=item.direct,r.generation=$generation RETURN count(*) AS derivationCount }",
            "WITH v CALL { WITH v UNWIND $usages AS item MERGE (a:Asset {assetKey:item.column.assetKey}) SET a.catalog=item.column.catalog,a.database=item.column.database,a.table=item.column.table,a.qualifiedName=item.column.qualifiedName,a.assetType=item.column.assetType MERGE (c:Column {columnKey:item.column.columnKey}) SET c.name=item.column.column MERGE (a)-[:HAS_COLUMN]->(c) MERGE (c)-[r:USED_BY {versionKey:$versionKey,statementIndex:item.statementIndex,usageType:item.usageType}]->(v) SET r.expression=item.expression,r.generation=$generation RETURN count(*) AS usageCount }",
            "WITH v CALL { WITH v UNWIND $joins AS item MERGE (la:Asset {assetKey:item.left.assetKey}) SET la.catalog=item.left.catalog,la.database=item.left.database,la.table=item.left.table,la.qualifiedName=item.left.qualifiedName,la.assetType=item.left.assetType MERGE (lc:Column {columnKey:item.left.columnKey}) SET lc.name=item.left.column MERGE (la)-[:HAS_COLUMN]->(lc) MERGE (ra:Asset {assetKey:item.right.assetKey}) SET ra.catalog=item.right.catalog,ra.database=item.right.database,ra.table=item.right.table,ra.qualifiedName=item.right.qualifiedName,ra.assetType=item.right.assetType MERGE (rc:Column {columnKey:item.right.columnKey}) SET rc.name=item.right.column MERGE (ra)-[:HAS_COLUMN]->(rc) MERGE (lc)-[r:JOINED_WITH {versionKey:$versionKey,statementIndex:item.statementIndex}]->(rc) SET r.joinType=item.joinType,r.condition=item.condition,r.generation=$generation RETURN count(*) AS joinCount }",
            "RETURN v.versionKey AS versionKey");
}
