package com.yjn.sqlagent.service;

import com.yjn.sqlagent.common.ErrorCode;
import com.yjn.sqlagent.exception.BusinessException;
import com.yjn.sqlagent.mapper.SqlTaskMapper;
import com.yjn.sqlagent.mapper.SqlTaskVersionMapper;
import com.yjn.sqlagent.model.entity.SqlTask;
import com.yjn.sqlagent.model.entity.SqlTaskVersion;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 离线任务血缘和跨任务影响的 Java parse-sql 唯一查询入口。 */
@Service
public class TaskLineageQueryService {
    private static final int SCAN_LIMIT = 500;

    private final SqlTaskMapper taskMapper;
    private final SqlTaskVersionMapper versionMapper;
    private final TaskSqlStructureService structureService;
    private final TaskLineageSnapshotService snapshotService;
    private final AgentProxyService agentProxyService;

    public TaskLineageQueryService(SqlTaskMapper taskMapper,
                                   SqlTaskVersionMapper versionMapper,
                                   TaskSqlStructureService structureService,
                                   TaskLineageSnapshotService snapshotService,
                                   AgentProxyService agentProxyService) {
        this.taskMapper = taskMapper;
        this.versionMapper = versionMapper;
        this.structureService = structureService;
        this.snapshotService = snapshotService;
        this.agentProxyService = agentProxyService;
    }

    public Map<String, Object> lineage(long taskId, Integer versionNo, String defaultDb) {
        ResolvedTask resolved = resolve(taskId, versionNo);
        String database = database(defaultDb);
        TaskLineageFacts facts = facts(resolved, database, true);
        return lineageResult(resolved, database, facts, "VERSION_SNAPSHOT");
    }

    /** 使用当前解析器临时分析，不覆盖也不新增历史快照。 */
    public Map<String, Object> reanalyze(long taskId, Integer versionNo, String defaultDb) {
        ResolvedTask resolved = resolve(taskId, versionNo);
        String database = database(defaultDb);
        TaskLineageFacts facts = structureService.analyzeLineage(resolved.sql, database);
        return lineageResult(resolved, database, facts, "CURRENT_PARSER");
    }

    private Map<String, Object> lineageResult(ResolvedTask resolved, String database,
                                              TaskLineageFacts facts, String mode) {
        List<String> warnings = diagnosticMessages(facts);
        List<String> missing = new ArrayList<>();
        List<Map<String, Object>> inputs = validateTables(facts.getInputs(), warnings, missing);
        List<Map<String, Object>> outputs = validateTables(facts.getOutputs(), warnings, missing);
        if (!facts.isComplete()) missing.add("parse_diagnostics_present");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("source", "java-parse-sql+hive-metastore");
        result.put("fetchedAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
        result.put("taskId", resolved.taskId);
        result.put("versionNo", resolved.versionNo == 0 ? null : resolved.versionNo);
        result.put("taskName", resolved.name);
        result.put("defaultDb", database);
        result.put("statementCount", facts.getStatementCount());
        result.put("inputs", inputs);
        result.put("outputs", outputs);
        result.put("ctes", Collections.emptyList());
        result.put("diagnostics", facts.getDiagnostics());
        result.put("lineageMode", mode);
        result.put("parserVersion", TaskLineageSnapshotService.PARSER_VERSION);
        result.put("snapshot", "VERSION_SNAPSHOT".equals(mode)
                ? snapshotService.metadata(resolved.taskId, resolved.versionNo, resolved.checksum, database)
                : Collections.emptyMap());
        result.put("warnings", unique(warnings));
        result.put("complete", facts.isComplete() && missing.isEmpty());
        result.put("missingReasons", unique(missing));
        return result;
    }

    public Map<String, Object> dependencies(long taskId, Integer versionNo, String defaultDb) {
        String database = database(defaultDb);
        ResolvedTask target = resolve(taskId, versionNo);
        long total = taskMapper.countTasks("", "", "");
        List<SqlTask> tasks = taskMapper.listTasks("", "", "", 0, SCAN_LIMIT);
        boolean targetIncluded = false;
        Map<Long, ResolvedTask> resolved = new LinkedHashMap<>();
        for (SqlTask task : tasks) {
            ResolvedTask item = task.getId() == taskId ? target : ResolvedTask.current(task);
            resolved.put(item.taskId, item);
            if (item.taskId == taskId) targetIncluded = true;
        }
        if (!targetIncluded) resolved.put(taskId, target);

        Map<Long, TaskLineageFacts> parsed = new LinkedHashMap<>();
        List<Map<String, Object>> failures = new ArrayList<>();
        for (ResolvedTask item : resolved.values()) {
            try {
                parsed.put(item.taskId, facts(item, database, true));
            } catch (RuntimeException error) {
                Map<String, Object> failure = node(item);
                failure.put("reason", safeMessage(error));
                failures.add(failure);
            }
        }
        if (!parsed.containsKey(taskId)) throw badRequest("目标任务 SQL 无法解析");

        Map<String, List<Long>> producers = new LinkedHashMap<>();
        for (Map.Entry<Long, TaskLineageFacts> item : parsed.entrySet()) {
            for (Map<String, Object> output : item.getValue().getOutputs()) {
                producers.computeIfAbsent(tableKey(output), ignored -> new ArrayList<>());
                if (!producers.get(tableKey(output)).contains(item.getKey())) {
                    producers.get(tableKey(output)).add(item.getKey());
                }
            }
        }
        List<Edge> edges = new ArrayList<>();
        Set<String> self = new LinkedHashSet<>();
        Set<String> external = new LinkedHashSet<>();
        TaskLineageFacts targetFacts = parsed.get(taskId);
        for (Map.Entry<Long, TaskLineageFacts> item : parsed.entrySet()) {
            long downstream = item.getKey();
            for (Map<String, Object> input : item.getValue().getInputs()) {
                String table = tableKey(input);
                List<Long> tableProducers = producers.getOrDefault(table, Collections.emptyList());
                if (downstream == taskId && tableProducers.isEmpty()) external.add(table);
                for (Long upstream : tableProducers) {
                    if (upstream == downstream) {
                        if (downstream == taskId) self.add(table);
                    } else addEdge(edges, new Edge(upstream, downstream, table));
                }
            }
        }

        List<Map<String, Object>> outputImpacts = new ArrayList<>();
        for (Map<String, Object> output : targetFacts.getOutputs()) {
            String table = tableKey(output);
            Set<Long> consumers = new LinkedHashSet<>();
            for (Edge edge : edges) if (edge.upstream == taskId && edge.table.equals(table)) consumers.add(edge.downstream);
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("table", table);
            value.put("consumerTasks", nodes(consumers, resolved));
            outputImpacts.add(value);
        }

        List<Map<String, Object>> conflicts = new ArrayList<>();
        Set<String> targetInputs = tableKeys(targetFacts.getInputs());
        for (Map.Entry<String, List<Long>> item : producers.entrySet()) {
            if (item.getValue().size() > 1 && (targetInputs.contains(item.getKey()) || item.getValue().contains(taskId))) {
                Map<String, Object> conflict = new LinkedHashMap<>();
                conflict.put("table", item.getKey());
                conflict.put("producerTasks", nodes(new LinkedHashSet<>(item.getValue()), resolved));
                conflicts.add(conflict);
            }
        }
        List<String> warnings = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        if (total > SCAN_LIMIT) {
            warnings.add("任务总数 " + total + " 超过单次扫描上限 " + SCAN_LIMIT + "，跨任务影响范围可能不完整。");
            missing.add("task_scan_truncated");
        }
        if (!failures.isEmpty()) {
            warnings.add("有 " + failures.size() + " 个任务 SQL 无法解析，相关依赖未纳入结果。");
            missing.add("task_sql_parse_failed");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("source", "sql-agent-db+java-parse-sql");
        result.put("fetchedAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
        result.put("taskId", taskId);
        result.put("versionNo", target.versionNo == 0 ? null : target.versionNo);
        result.put("taskName", target.name);
        result.put("scannedTaskCount", resolved.size());
        result.put("totalTaskCount", total);
        result.put("scanLimit", SCAN_LIMIT);
        result.put("target", node(target));
        result.put("directUpstream", related(edges, resolved, taskId, true));
        result.put("directDownstream", related(edges, resolved, taskId, false));
        result.put("transitiveUpstream", walk(edges, resolved, taskId, true));
        result.put("transitiveDownstream", walk(edges, resolved, taskId, false));
        result.put("externalInputs", new ArrayList<>(external));
        result.put("selfDependencies", new ArrayList<>(self));
        result.put("outputs", outputImpacts);
        result.put("producerConflicts", conflicts);
        result.put("parseFailures", failures);
        result.put("parsedTaskCount", parsed.size());
        result.put("edges", edgeMaps(edges));
        result.put("warnings", warnings);
        result.put("complete", missing.isEmpty());
        result.put("missingReasons", missing);
        return result;
    }

    private TaskLineageFacts facts(ResolvedTask task, String defaultDb, boolean persistBackfill) {
        TaskLineageFacts saved = snapshotService.findOffline(task.taskId, task.versionNo, task.checksum, defaultDb);
        if (saved != null) return saved;
        TaskLineageFacts parsed = structureService.analyzeLineage(task.sql, defaultDb);
        if (persistBackfill) snapshotService.saveOffline(task.taskId, task.versionId, task.versionNo,
                task.checksum, defaultDb, "BACKFILLED", parsed);
        return parsed;
    }

    private ResolvedTask resolve(long taskId, Integer versionNo) {
        SqlTask task = taskMapper.selectById(taskId);
        if (task == null) throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "任务不存在");
        if (versionNo == null) return ResolvedTask.current(task);
        SqlTaskVersion version = versionMapper.selectVersion(taskId, versionNo);
        if (version == null) throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "任务版本不存在");
        return ResolvedTask.version(task, version);
    }

    private List<Map<String, Object>> validateTables(List<Map<String, Object>> tables,
                                                      List<String> warnings, List<String> missing) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> source : tables) {
            Map<String, Object> table = new LinkedHashMap<>(source);
            String db = text(table.get("db"));
            String name = text(table.get("table"));
            if (Boolean.TRUE.equals(table.get("dynamic")) || db.isEmpty()) {
                table.put("validationStatus", "UNRESOLVED");
                missing.add("unresolved_table:" + table.get("qualifiedName"));
            } else {
                try {
                    Map<String, Object> response = agentProxyService.getHiveTable(db, name);
                    table.put("validationStatus", "EXISTS");
                    copyMetadata(table, response);
                } catch (BusinessException error) {
                    if (error.getCode() == ErrorCode.NOT_FOUND.getCode()) {
                        table.put("validationStatus", "MISSING");
                        missing.add("table_not_found:" + table.get("qualifiedName"));
                    } else {
                        table.put("validationStatus", "UNKNOWN");
                        warnings.add("无法校验 " + table.get("qualifiedName") + "：" + safeMessage(error));
                        missing.add("metadata_validation_failed:" + table.get("qualifiedName"));
                    }
                } catch (RuntimeException error) {
                    table.put("validationStatus", "UNKNOWN");
                    warnings.add("无法校验 " + table.get("qualifiedName") + "：" + safeMessage(error));
                    missing.add("metadata_validation_failed:" + table.get("qualifiedName"));
                }
            }
            result.add(table);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void copyMetadata(Map<String, Object> target, Map<String, Object> response) {
        Object raw = response == null ? null : response.get("table");
        Map<String, Object> table = raw instanceof Map ? (Map<String, Object>) raw : response;
        if (table == null) return;
        copy(target, table, "tableType", "table_type");
        copy(target, table, "owner", "owner");
        copy(target, table, "comment", "comment");
        Object columns = table.get("columns");
        if (columns instanceof List) {
            List<String> partitions = new ArrayList<>();
            for (Object item : (List<?>) columns) {
                if (!(item instanceof Map)) continue;
                Map<?, ?> column = (Map<?, ?>) item;
                if (Boolean.TRUE.equals(column.get("partitionKey")) || Boolean.TRUE.equals(column.get("partition_key"))) {
                    partitions.add(text(column.get("name")));
                }
            }
            target.put("partitionKeys", partitions);
        }
    }

    private void copy(Map<String, Object> target, Map<String, Object> source, String targetKey, String sourceKey) {
        Object value = source.containsKey(targetKey) ? source.get(targetKey) : source.get(sourceKey);
        if (value != null) target.put(targetKey, value);
    }

    private List<String> diagnosticMessages(TaskLineageFacts facts) {
        List<String> result = new ArrayList<>();
        for (Map<String, Object> diagnostic : facts.getDiagnostics()) {
            if (!"INFO".equals(text(diagnostic.get("severity")))) result.add(text(diagnostic.get("message")));
        }
        return result;
    }

    private List<Map<String, Object>> related(List<Edge> edges, Map<Long, ResolvedTask> tasks,
                                               long target, boolean upstream) {
        Map<Long, Set<String>> grouped = new LinkedHashMap<>();
        for (Edge edge : edges) {
            long compare = upstream ? edge.downstream : edge.upstream;
            long related = upstream ? edge.upstream : edge.downstream;
            if (compare == target) grouped.computeIfAbsent(related, ignored -> new LinkedHashSet<>()).add(edge.table);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<Long, Set<String>> item : grouped.entrySet()) {
            Map<String, Object> value = node(tasks.get(item.getKey()));
            value.put("tables", new ArrayList<>(item.getValue()));
            result.add(value);
        }
        return result;
    }

    private List<Map<String, Object>> walk(List<Edge> edges, Map<Long, ResolvedTask> tasks,
                                            long target, boolean upstream) {
        Map<Long, Set<Long>> adjacency = new HashMap<>();
        for (Edge edge : edges) {
            long source = upstream ? edge.downstream : edge.upstream;
            long next = upstream ? edge.upstream : edge.downstream;
            adjacency.computeIfAbsent(source, ignored -> new LinkedHashSet<>()).add(next);
        }
        Deque<long[]> queue = new ArrayDeque<>();
        for (Long next : adjacency.getOrDefault(target, Collections.emptySet())) queue.add(new long[]{next, 1});
        Map<Long, Integer> depths = new LinkedHashMap<>();
        while (!queue.isEmpty()) {
            long[] item = queue.removeFirst();
            if (item[0] == target || depths.containsKey(item[0])) continue;
            depths.put(item[0], (int) item[1]);
            for (Long next : adjacency.getOrDefault(item[0], Collections.emptySet())) {
                queue.add(new long[]{next, item[1] + 1});
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<Long, Integer> item : depths.entrySet()) {
            Map<String, Object> value = node(tasks.get(item.getKey()));
            value.put("depth", item.getValue());
            result.add(value);
        }
        return result;
    }

    private List<Map<String, Object>> nodes(Set<Long> ids, Map<Long, ResolvedTask> tasks) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Long id : ids) if (tasks.containsKey(id)) result.add(node(tasks.get(id)));
        return result;
    }

    private Map<String, Object> node(ResolvedTask task) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", task.taskId);
        result.put("taskName", task.name);
        result.put("updatedAt", task.updatedAt);
        return result;
    }

    private List<Map<String, Object>> edgeMaps(List<Edge> edges) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Edge edge : edges) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("upstreamTaskId", edge.upstream);
            value.put("downstreamTaskId", edge.downstream);
            value.put("table", edge.table);
            result.add(value);
        }
        return result;
    }

    private void addEdge(List<Edge> edges, Edge candidate) {
        for (Edge edge : edges) if (edge.equals(candidate)) return;
        edges.add(candidate);
    }

    private Set<String> tableKeys(List<Map<String, Object>> tables) {
        Set<String> result = new LinkedHashSet<>();
        for (Map<String, Object> table : tables) result.add(tableKey(table));
        return result;
    }

    private String tableKey(Map<String, Object> table) {
        return text(table.get("qualifiedName")).toLowerCase(Locale.ROOT);
    }

    private String database(String value) { return value == null || value.trim().isEmpty() ? "default" : value.trim(); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private String safeMessage(Throwable error) {
        String value = error.getMessage();
        return value == null || value.trim().isEmpty() ? error.getClass().getSimpleName() : value.trim();
    }
    private List<String> unique(List<String> values) { return new ArrayList<>(new LinkedHashSet<>(values)); }
    private BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST.getCode(), message);
    }

    private static final class Edge {
        private final long upstream;
        private final long downstream;
        private final String table;
        private Edge(long upstream, long downstream, String table) {
            this.upstream = upstream; this.downstream = downstream; this.table = table;
        }
        @Override public boolean equals(Object value) {
            if (!(value instanceof Edge)) return false;
            Edge other = (Edge) value;
            return upstream == other.upstream && downstream == other.downstream && table.equals(other.table);
        }
        @Override public int hashCode() { return java.util.Objects.hash(upstream, downstream, table); }
    }

    private static final class ResolvedTask {
        private final long taskId;
        private final String name;
        private final Long versionId;
        private final int versionNo;
        private final String sql;
        private final String checksum;
        private final Object updatedAt;

        private ResolvedTask(long taskId, String name, Long versionId, int versionNo,
                             String sql, String checksum, Object updatedAt) {
            this.taskId = taskId; this.name = name; this.versionId = versionId; this.versionNo = versionNo;
            this.sql = sql; this.checksum = checksum; this.updatedAt = updatedAt;
        }
        private static ResolvedTask current(SqlTask task) {
            return new ResolvedTask(task.getId(), task.getName(), null,
                    task.getEffectiveVersionNo() == null ? 0 : task.getEffectiveVersionNo(),
                    task.getSqlContent(), task.getSqlChecksum(), task.getUpdateTime());
        }
        private static ResolvedTask version(SqlTask task, SqlTaskVersion version) {
            return new ResolvedTask(task.getId(), version.getName(), version.getId(), version.getVersionNo(),
                    version.getSqlContent(), version.getSqlChecksum(), version.getUpdateTime());
        }
    }
}
