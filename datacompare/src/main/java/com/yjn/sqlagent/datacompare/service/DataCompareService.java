package com.yjn.sqlagent.datacompare.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjn.sqlagent.datacompare.config.DataCompareProperties;
import com.yjn.sqlagent.datacompare.model.CompareRule;
import com.yjn.sqlagent.datacompare.model.CompareTableRequest;
import com.yjn.sqlagent.datacompare.model.CreateCompareRequest;
import com.yjn.sqlagent.datacompare.model.GenerateVersionPlanRequest;
import com.yjn.sqlagent.datacompare.model.PrepareVersionRequest;
import com.yjn.sqlagent.datacompare.model.SqlStep;
import com.yjn.sqlagent.datacompare.model.TaskVersionContent;
import com.yjn.sqlagent.datacompare.model.VersionComparePlan;
import com.yjn.sqlagent.datacompare.repository.DataCompareRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 版本验数、直接表验数及结构化报告的领域服务。 */
@Service
public class DataCompareService {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<List<String>>() { };
    private static final TypeReference<List<CompareTableRequest>> TABLE_LIST =
            new TypeReference<List<CompareTableRequest>>() { };
    private final DataCompareRepository repository;
    private final SqlStepParser parser;
    private final HiveDdlService ddlService;
    private final HiveJdbcClient hive;
    private final TableVerifyService verifier;
    private final SqlParameterRenderer parameterRenderer;
    private final CompareLog logs;
    private final ObjectMapper json;
    private final String tempDatabase;
    private final String instanceId = UUID.randomUUID().toString().replace("-", "");
    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private final Map<Long, Future<?>> futures = new ConcurrentHashMap<>();
    private final Map<Long, Statement> statements = new ConcurrentHashMap<>();

    public DataCompareService(DataCompareRepository repository, SqlStepParser parser, HiveDdlService ddlService,
                              HiveJdbcClient hive, TableVerifyService verifier,
                              SqlParameterRenderer parameterRenderer, CompareLog logs, ObjectMapper json,
                              DataCompareProperties properties) {
        this.repository = repository;
        this.parser = parser;
        this.ddlService = ddlService;
        this.hive = hive;
        this.verifier = verifier;
        this.parameterRenderer = parameterRenderer;
        this.logs = logs;
        this.json = json;
        this.tempDatabase = properties.getHiveTempDatabase();
    }

    @PostConstruct
    public void recover() {
        repository.failOrphaned(instanceId);
        repository.pendingJobs().forEach(id -> futures.put(id, workers.submit(() -> run(id))));
    }

    @PreDestroy
    public void shutdown() {
        futures.values().forEach(future -> future.cancel(true));
        statements.values().forEach(this::cancelStatement);
        workers.shutdownNow();
    }

    /** 固定使用当前生效代码作为基线，只允许仍可编辑的开发版本作为候选。 */
    public Map<String, Object> prepare(PrepareVersionRequest request) {
        TaskVersionContent baseline = requireEffective(request.getTaskId());
        TaskVersionContent candidate = requireCandidate(request.getTaskId(), request.getCandidateVersionNo(), baseline);
        Map<String, Object> union = repository.unionContext(request.getTaskId(), request.getCandidateVersionNo());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", request.getTaskId());
        result.put("baselineVersion", baseline);
        result.put("candidateVersion", candidate);
        result.put("baselineSteps", versionSteps(baseline));
        result.put("candidateSteps", versionSteps(candidate));
        result.put("unionId", union.get("union_id"));
        result.put("unionDdl", union.get("union_ddl"));
        return result;
    }

    /** 先生成并持久化完整 SQL/表名/校验和快照，执行阶段不再读取可变版本。 */
    public Map<String, Object> generate(GenerateVersionPlanRequest request, String operator) {
        TaskVersionContent baseline = requireEffective(request.getTaskId());
        TaskVersionContent candidate = requireCandidate(request.getTaskId(), request.getCandidateVersionNo(), baseline);
        List<SqlStep> baselineSteps = parser.selected(versionSteps(baseline), request.getBaselineSteps());
        List<SqlStep> candidateSteps = parser.selected(versionSteps(candidate), request.getCandidateSteps());
        if (baselineSteps.isEmpty() || candidateSteps.isEmpty()) {
            throw new IllegalArgumentException("基线和候选版本至少各选择一个可执行 Step");
        }

        String token = UUID.randomUUID().toString().replace("-", "");
        Map<String, String> baselineMap = temporaryMappings(outputs(baselineSteps), token, "b");
        Map<String, String> candidateMap = temporaryMappings(outputs(candidateSteps), token, "c");
        Map<String, Object> union = repository.unionContext(request.getTaskId(), request.getCandidateVersionNo());
        String unionDdl = string(union.get("union_ddl"));
        ddlService.requireNoOverlap(unionDdl, Collections.singletonList(candidate.getDdl()));
        Set<String> ddlTables = new LinkedHashSet<>(ddlService.affectedTables(unionDdl));
        ddlTables.addAll(ddlService.affectedTables(candidate.getDdl()));

        String generatedBaseline = createLikeSql(baselineMap)
                + parameterRenderer.renderDefaults(parser.rewrite(baselineSteps, baselineMap), baseline.getParameterSchema());
        String generatedCandidate = createLikeSql(candidateMap)
                + ddlService.rewrite(unionDdl, candidateMap)
                + ddlService.rewrite(candidate.getDdl(), candidateMap)
                + parameterRenderer.renderDefaults(parser.rewrite(candidateSteps, candidateMap), candidate.getParameterSchema());

        List<CompareTableRequest> mappings = suggestedTables(
                baselineSteps, candidateSteps, baselineMap, candidateMap, ddlTables);
        if (mappings.isEmpty()) throw new IllegalArgumentException("已选 Step 没有可配对的同名输出表");
        for (String ddlTable : ddlTables) {
            boolean mapped = mappings.stream().anyMatch(item ->
                    ddlTable.equalsIgnoreCase(item.getCandidateSourceTable()));
            if (!mapped) throw new IllegalArgumentException(
                    "DDL 影响的表必须在基线与候选 Step 中形成输出表映射：" + ddlTable);
        }

        VersionComparePlan plan = new VersionComparePlan();
        plan.setToken(token);
        plan.setTaskId(request.getTaskId());
        plan.setBaselineVersionNo(baseline.getVersionNo() == 0 ? null : baseline.getVersionNo());
        plan.setCandidateVersionNo(candidate.getVersionNo());
        plan.setBaselineChecksum(baseline.getChecksum());
        plan.setCandidateChecksum(candidate.getChecksum());
        plan.setUnionId(longValue(union.get("union_id")));
        plan.setUnionDdlChecksum(blank(unionDdl) ? null : checksum(unionDdl));
        plan.setOriginalBaselineSql(baseline.getSql());
        plan.setOriginalCandidateSql(candidate.getSql());
        plan.setGeneratedBaselineSql(generatedBaseline);
        plan.setGeneratedCandidateSql(generatedCandidate);
        plan.setBaselineSteps(stepNames(baselineSteps));
        plan.setCandidateSteps(stepNames(candidateSteps));
        plan.setTableMappings(mappings);
        List<String> temporaryTables = new ArrayList<>(baselineMap.values());
        temporaryTables.addAll(candidateMap.values());
        plan.setTemporaryTables(temporaryTables);
        repository.createPlan(plan, operator);
        return planResponse(plan, baseline, candidate, baselineSteps, candidateSteps);
    }

    public Map<String, Object> create(CreateCompareRequest request, String operator) {
        String type = request.getCompareType().trim().toUpperCase(Locale.ROOT);
        request.setCompareType(type);
        VersionComparePlan plan = null;
        String baselineSql = null;
        String candidateSql = null;
        if ("VERSION".equals(type)) {
            if (blank(request.getPlanToken())) throw new IllegalArgumentException("请先生成调测 SQL");
            plan = requirePlan(request.getPlanToken());
            validatePlanStillCurrent(plan);
            request.setTaskId(plan.getTaskId());
            request.setBaselineVersionNo(plan.getBaselineVersionNo());
            request.setCandidateVersionNo(plan.getCandidateVersionNo());
            request.setBaselineSteps(plan.getBaselineSteps());
            request.setCandidateSteps(plan.getCandidateSteps());
            request.setTables(selectedPlanMappings(plan, request.getTables()));
            baselineSql = plan.getOriginalBaselineSql();
            candidateSql = plan.getOriginalCandidateSql();
        } else if ("TABLE".equals(type)) {
            if (blank(request.getBaselineTable()) || blank(request.getCandidateTable())) {
                throw new IllegalArgumentException("表对比必须指定基线表和候选表");
            }
            CompareTableRequest table = new CompareTableRequest();
            table.setOriginalTable(request.getCandidateTable());
            table.setBaselineSourceTable(request.getBaselineTable());
            table.setCandidateSourceTable(request.getCandidateTable());
            table.setBaselineTable(request.getBaselineTable());
            table.setCandidateTable(request.getCandidateTable());
            table.setRule(request.getTables().isEmpty() ? new CompareRule() : request.getTables().get(0).getRule());
            request.setTables(Collections.singletonList(table));
        } else {
            throw new IllegalArgumentException("compareType 只允许 VERSION 或 TABLE");
        }
        validateTables(request.getTables());
        long id = repository.createJob(request, operator, plan, baselineSql, candidateSql);
        for (CompareTableRequest table : request.getTables()) repository.createTable(id, table, operator);
        if (plan != null) repository.linkUnionMemberLatestJob(
                plan.getUnionId(), plan.getTaskId(), plan.getCandidateVersionNo(), id);
        futures.put(id, workers.submit(() -> run(id)));
        return detail(id);
    }

    public Map<String, Object> list(String status, String type, Long taskId, Integer versionNo,
                                    Long jobId, String operator, String fromTime, String toTime,
                                    boolean mine, String currentUser, int page, int pageSize,
                                    String keyword) {
        validatePage(page, pageSize);
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        String normalizedOperator = mine ? currentUser : operator == null ? "" : operator.trim();
        String normalizedFrom = normalizeDateTime(fromTime);
        String normalizedTo = normalizeDateTime(toTime);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", repository.list(status, type, taskId, versionNo, jobId,
                normalizedOperator, normalizedFrom, normalizedTo, normalizedKeyword,
                (page - 1) * pageSize, pageSize));
        result.put("page", page); result.put("pageSize", pageSize);
        result.put("total", repository.count(status, type, taskId, versionNo, jobId,
                normalizedOperator, normalizedFrom, normalizedTo, normalizedKeyword));
        return result;
    }

    private String normalizeDateTime(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        String normalized = value.trim().replace('T', ' ');
        if (!normalized.matches("\\d{4}-\\d{2}-\\d{2}( \\d{2}:\\d{2}(:\\d{2})?)?")) {
            throw new IllegalArgumentException("时间格式非法");
        }
        return normalized.length() == 10 ? normalized + " 00:00:00"
                : normalized.length() == 16 ? normalized + ":00" : normalized;
    }

    public Map<String, Object> detail(long id) {
        Map<String, Object> result = new LinkedHashMap<>(requireJob(id));
        result.put("tables", repository.tables(id));
        return result;
    }

    public Map<String, Object> report(long id, int page, int pageSize, String keyword) {
        validatePage(page, pageSize);
        Map<String, Object> result = new LinkedHashMap<>(requireJob(id));
        String normalized = keyword == null ? "" : keyword.trim();
        Map<String, Object> tablePage = new LinkedHashMap<>();
        tablePage.put("items", repository.reportTables(id, normalized,
                (page - 1) * pageSize, pageSize));
        tablePage.put("page", page); tablePage.put("pageSize", pageSize);
        tablePage.put("total", repository.countReportTables(id, normalized));
        result.put("tablePage", tablePage);
        return result;
    }

    public Map<String, Object> updateRule(long tableId, CompareRule rule, String operator) {
        Map<String, Object> table = requireTable(tableId);
        if ("PENDING".equals(string(table.get("status"))) || "RUNNING".equals(string(table.get("status")))) {
            throw new IllegalArgumentException("运行中的验数不能修改规则");
        }
        normalizeRule(rule);
        repository.updateRule(tableId, repository.json(rule), operator);
        repository.recomputeJobStatus(number(table.get("job_id")));
        return requireTable(tableId);
    }

    public Map<String, Object> rerun(long tableId, String operator) {
        Map<String, Object> table = requireTable(tableId);
        long jobId = number(table.get("job_id"));
        Future<?> running = futures.get(jobId);
        if (running != null && !running.isDone()) throw new IllegalArgumentException("该验数任务仍在运行");
        repository.prepareRerun(tableId, operator);
        repository.recomputeJobStatus(jobId);
        futures.put(jobId, workers.submit(() -> rerunTable(jobId, tableId)));
        return requireTable(tableId);
    }

    public Map<String, Object> forcePass(long tableId, String reason, String operator) {
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("强制通过原因不能为空");
        if (normalized.length() > 1000) throw new IllegalArgumentException("强制通过原因不能超过1000个字符");
        Map<String, Object> table = requireTable(tableId);
        repository.forcePass(tableId, normalized, operator);
        repository.recomputeJobStatus(number(table.get("job_id")));
        return requireTable(tableId);
    }

    public boolean cancel(long id) {
        Map<String, Object> job = requireJob(id);
        String status = string(job.get("status"));
        if ("PENDING".equals(status)) {
            Future<?> future = futures.remove(id);
            if (future != null) future.cancel(false);
            repository.finishUnfinishedTables(id, "CANCELLED", null);
            repository.finishJob(id, "CANCELLED", null);
            return true;
        }
        if (!repository.requestCancel(id)) return false;
        Statement statement = statements.get(id);
        if (statement != null) cancelStatement(statement);
        return true;
    }

    public String tableLog(long tableId) {
        Map<String, Object> table = requireTable(tableId);
        return logs.read(string(table.get("log_file")), 200000);
    }

    private void run(long jobId) {
        if (!repository.claim(jobId, instanceId)) return;
        Map<Long, String> logFiles = new LinkedHashMap<>();
        try {
            Map<String, Object> job = requireJob(jobId);
            for (Map<String, Object> table : repository.tables(jobId)) {
                long tableId = number(table.get("id"));
                String logFile = logs.path(jobId, tableId);
                logFiles.put(tableId, logFile);
                repository.startTable(tableId, logFile);
            }
            if ("VERSION".equals(string(job.get("compare_type")))) {
                executeSnapshot(jobId, job, line -> logFiles.values().forEach(path -> logs.write(path, line)));
            }
            for (Map<String, Object> table : repository.tables(jobId)) {
                if (isCancelling(jobId)) throw new CancelledException();
                verifyTable(jobId, table, logFiles.get(number(table.get("id"))));
                repository.heartbeat(jobId, instanceId);
            }
            repository.recomputeJobStatus(jobId);
        } catch (CancelledException error) {
            repository.finishUnfinishedTables(jobId, "CANCELLED", null);
            repository.finishJob(jobId, "CANCELLED", null);
        } catch (Throwable error) {
            if (error instanceof ThreadDeath) throw (ThreadDeath) error;
            if (error instanceof VirtualMachineError) throw (VirtualMachineError) error;
            boolean cancelled = isCancelling(jobId);
            String status = cancelled ? "CANCELLED" : "FAILED";
            String message = cancelled ? null : safe(error);
            logFiles.values().forEach(path -> logs.write(path, "[" + status + "] "
                    + (message == null ? "已取消" : message)));
            repository.finishUnfinishedTables(jobId, status, message);
            repository.finishJob(jobId, status, message);
        } finally {
            statements.remove(jobId);
            futures.remove(jobId);
        }
    }

    private void rerunTable(long jobId, long tableId) {
        String logFile = logs.path(jobId, tableId);
        try {
            repository.startTable(tableId, logFile);
            verifyTable(jobId, requireTable(tableId), logFile);
            repository.recomputeJobStatus(jobId);
        } catch (Throwable error) {
            repository.finishTable(tableId, Collections.emptyMap(), "FAILED", safe(error));
            logs.write(logFile, "[FAILED] " + safe(error));
            repository.recomputeJobStatus(jobId);
        } finally {
            statements.remove(jobId);
            futures.remove(jobId);
        }
    }

    private void executeSnapshot(long jobId, Map<String, Object> job,
                                 java.util.function.Consumer<String> logger) throws Exception {
        String baseline = string(job.get("generated_baseline_sql"));
        String candidate = string(job.get("generated_candidate_sql"));
        if (blank(baseline) || blank(candidate)) throw new IllegalArgumentException("验数执行快照不完整");
        hive.executeScript(baseline, statement -> statements.put(jobId, statement), logger);
        repository.heartbeat(jobId, instanceId);
        if (isCancelling(jobId)) throw new CancelledException();
        hive.executeScript(candidate, statement -> statements.put(jobId, statement), logger);
        statements.remove(jobId);
    }

    private void verifyTable(long jobId, Map<String, Object> table, String logFile) throws Exception {
        long tableId = number(table.get("id"));
        try {
            CompareRule rule = rule(string(table.get("compare_rule")));
            Map<String, Object> job = requireJob(jobId);
            Map<String, Object> result = verifier.verify(tableId, string(table.get("baseline_tbl_name")),
                    string(table.get("candidate_tbl_name")), rule,
                    booleanValue(job.get("only_compare_same_column")),
                    statement -> statements.put(jobId, statement), line -> logs.write(logFile, line));
            boolean passed = Boolean.TRUE.equals(result.get("metadataSame"))
                    && Boolean.TRUE.equals(result.get("partitionSetsSame"))
                    && Boolean.TRUE.equals(result.get("rowCountSame"))
                    && Boolean.TRUE.equals(result.get("crcSame"))
                    && !result.containsKey("validationIssue");
            repository.finishTable(tableId, result, passed ? "PASSED" : "NOT_PASSED",
                    result.get("validationIssue") == null ? null : string(result.get("validationIssue")));
        } catch (TableVerifyService.DataMismatchException mismatch) {
            repository.finishTable(tableId, Collections.emptyMap(), "NOT_PASSED", safe(mismatch));
            logs.write(logFile, "[NOT_PASSED] " + safe(mismatch));
        } finally {
            statements.remove(jobId);
        }
    }

    private List<CompareTableRequest> suggestedTables(List<SqlStep> baseline, List<SqlStep> candidate,
                                                       Map<String, String> baselineMap,
                                                       Map<String, String> candidateMap,
                                                       Set<String> ddlTables) {
        Map<String, String> candidateOutputs = outputs(candidate).stream().collect(Collectors.toMap(
                value -> value.toLowerCase(Locale.ROOT), value -> value,
                (left, right) -> left, LinkedHashMap::new));
        List<CompareTableRequest> result = new ArrayList<>();
        for (String baselineSource : outputs(baseline)) {
            String candidateSource = candidateOutputs.get(baselineSource.toLowerCase(Locale.ROOT));
            if (candidateSource == null) continue;
            CompareTableRequest mapping = new CompareTableRequest();
            mapping.setOriginalTable(candidateSource);
            mapping.setBaselineSourceTable(baselineSource);
            mapping.setCandidateSourceTable(candidateSource);
            mapping.setBaselineTable(mappedTable(baselineMap, baselineSource));
            mapping.setCandidateTable(mappedTable(candidateMap, candidateSource));
            mapping.setRequiredByDdl(ddlTables.stream().anyMatch(value ->
                    value.equalsIgnoreCase(candidateSource)));
            mapping.setRule(new CompareRule());
            result.add(mapping);
        }
        return result;
    }

    private List<CompareTableRequest> selectedPlanMappings(VersionComparePlan plan,
                                                            List<CompareTableRequest> requested) {
        if (requested == null || requested.isEmpty()) throw new IllegalArgumentException("至少选择一组输出表映射");
        List<CompareTableRequest> selected = new ArrayList<>();
        for (CompareTableRequest item : requested) {
            String baselineSource = blank(item.getBaselineSourceTable())
                    ? item.getBaselineTable() : item.getBaselineSourceTable();
            String candidateSource = blank(item.getCandidateSourceTable())
                    ? item.getCandidateTable() : item.getCandidateSourceTable();
            CompareTableRequest stored = plan.getTableMappings().stream().filter(mapping ->
                    mapping.getBaselineSourceTable().equalsIgnoreCase(baselineSource)
                            && mapping.getCandidateSourceTable().equalsIgnoreCase(candidateSource)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("输出表映射不属于已生成计划："
                            + baselineSource + " / " + candidateSource));
            CompareTableRequest copy = copyMapping(stored);
            copy.setRule(item.getRule() == null ? new CompareRule() : item.getRule());
            selected.add(copy);
        }
        for (CompareTableRequest required : plan.getTableMappings()) {
            if (required.isRequiredByDdl() && selected.stream().noneMatch(item ->
                    item.getCandidateSourceTable().equalsIgnoreCase(required.getCandidateSourceTable()))) {
                throw new IllegalArgumentException("DDL 影响的输出表必须验数："
                        + required.getCandidateSourceTable());
            }
        }
        return selected;
    }

    private VersionComparePlan requirePlan(String token) {
        Map<String, Object> row = repository.plan(token);
        if (row == null) throw new IllegalArgumentException("验数计划不存在或已过期，请重新生成");
        if (row.get("consumed_job_id") != null) throw new IllegalArgumentException("验数计划已被消费，请重新生成");
        try {
            VersionComparePlan plan = new VersionComparePlan();
            plan.setToken(token);
            plan.setTaskId(number(row.get("task_id")));
            plan.setBaselineVersionNo(integerValue(row.get("baseline_version_no")));
            plan.setCandidateVersionNo(Integer.parseInt(row.get("candidate_version_no").toString()));
            plan.setBaselineChecksum(string(row.get("baseline_checksum")));
            plan.setCandidateChecksum(string(row.get("candidate_checksum")));
            plan.setUnionId(longValue(row.get("union_id")));
            plan.setUnionDdlChecksum(nullableString(row.get("union_ddl_checksum")));
            plan.setOriginalBaselineSql(string(row.get("original_baseline_sql")));
            plan.setOriginalCandidateSql(string(row.get("original_candidate_sql")));
            plan.setGeneratedBaselineSql(string(row.get("generated_baseline_sql")));
            plan.setGeneratedCandidateSql(string(row.get("generated_candidate_sql")));
            plan.setBaselineSteps(json.readValue(string(row.get("baseline_steps")), STRING_LIST));
            plan.setCandidateSteps(json.readValue(string(row.get("candidate_steps")), STRING_LIST));
            plan.setTableMappings(json.readValue(string(row.get("table_mappings")), TABLE_LIST));
            plan.setTemporaryTables(json.readValue(string(row.get("temporary_tables")), STRING_LIST));
            return plan;
        } catch (Exception error) {
            throw new IllegalArgumentException("验数计划内容损坏", error);
        }
    }

    private void validatePlanStillCurrent(VersionComparePlan plan) {
        TaskVersionContent baseline = requireEffective(plan.getTaskId());
        TaskVersionContent candidate = requireCandidate(plan.getTaskId(), plan.getCandidateVersionNo(), baseline);
        if (!Objects.equals(plan.getBaselineChecksum(), baseline.getChecksum())
                || !Objects.equals(plan.getCandidateChecksum(), candidate.getChecksum())) {
            throw new IllegalArgumentException("版本或当前生效基线已变化，请重新生成调测 SQL");
        }
        Map<String, Object> union = repository.unionContext(plan.getTaskId(), plan.getCandidateVersionNo());
        Long unionId = longValue(union.get("union_id"));
        String unionDdl = string(union.get("union_ddl"));
        String unionChecksum = blank(unionDdl) ? null : checksum(unionDdl);
        if (!Objects.equals(plan.getUnionId(), unionId)
                || !Objects.equals(plan.getUnionDdlChecksum(), unionChecksum)) {
            throw new IllegalArgumentException("联合版本或联合 DDL 已变化，请重新生成调测 SQL");
        }
    }

    private TaskVersionContent requireEffective(long taskId) {
        TaskVersionContent version = repository.effective(taskId);
        if (version == null) throw new IllegalArgumentException("任务不存在、已归档或没有生效代码");
        return version;
    }

    private TaskVersionContent requireCandidate(long taskId, int versionNo, TaskVersionContent baseline) {
        TaskVersionContent version = repository.version(taskId, versionNo);
        if (version == null) throw new IllegalArgumentException("任务版本不存在：v" + versionNo);
        if (!"DRAFT".equals(version.getStatus())) throw new IllegalArgumentException("只有开发中版本可以发起验数");
        Integer effectiveNo = baseline.getVersionNo() == 0 ? null : baseline.getVersionNo();
        if (!Objects.equals(version.getBaseEffectiveVersionNo(), effectiveNo)
                || !Objects.equals(version.getBaseEffectiveChecksum(), baseline.getChecksum())) {
            throw new IllegalArgumentException("候选版本基线已过期，请基于当前生效代码创建新版本");
        }
        return version;
    }

    private List<SqlStep> versionSteps(TaskVersionContent version) {
        String script = version.getVersionNo() == 0 ? null
                : repository.versionStepScript(version.getTaskId(), version.getVersionNo());
        return parser.parse(blank(script) ? version.getSql() : script);
    }

    private Map<String, String> temporaryMappings(Set<String> outputs, String token, String side) {
        Map<String, String> result = new LinkedHashMap<>();
        int index = 0;
        String prefix = token.substring(0, 12).toLowerCase(Locale.ROOT);
        for (String output : outputs) {
            result.put(output, tempDatabase + ".dc_" + prefix + "_" + side + "_" + (++index));
        }
        return result;
    }

    private String createLikeSql(Map<String, String> mappings) {
        StringBuilder sql = new StringBuilder();
        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            sql.append("CREATE TABLE ").append(HiveJdbcClient.quote(entry.getValue())).append(" LIKE ")
                    .append(HiveJdbcClient.quote(entry.getKey())).append(";\n");
        }
        return sql.toString();
    }

    private Map<String, Object> planResponse(VersionComparePlan plan, TaskVersionContent baseline,
                                              TaskVersionContent candidate, List<SqlStep> baselineSteps,
                                              List<SqlStep> candidateSteps) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planToken", plan.getToken());
        result.put("taskId", plan.getTaskId());
        result.put("baselineVersion", baseline);
        result.put("candidateVersion", candidate);
        result.put("baselineSteps", baselineSteps);
        result.put("candidateSteps", candidateSteps);
        result.put("selectedBaselineSteps", plan.getBaselineSteps());
        result.put("selectedCandidateSteps", plan.getCandidateSteps());
        result.put("originalBaselineSql", plan.getOriginalBaselineSql());
        result.put("originalCandidateSql", plan.getOriginalCandidateSql());
        result.put("generatedBaselineSql", plan.getGeneratedBaselineSql());
        result.put("generatedCandidateSql", plan.getGeneratedCandidateSql());
        result.put("suggestedTables", plan.getTableMappings());
        result.put("unionId", plan.getUnionId());
        return result;
    }

    private void validateTables(List<CompareTableRequest> tables) {
        if (tables == null || tables.isEmpty()) throw new IllegalArgumentException("没有可验数的输出表");
        for (CompareTableRequest table : tables) {
            if (blank(table.getBaselineTable()) || blank(table.getCandidateTable())) {
                throw new IllegalArgumentException("输出表映射不能为空");
            }
            if (table.getRule() == null) table.setRule(new CompareRule());
            normalizeRule(table.getRule());
        }
    }

    private void normalizeRule(CompareRule rule) {
        if (rule.getPrimaryKeyList() == null) rule.setPrimaryKeyList(new ArrayList<>());
        if (rule.getCompareColumnList() == null) rule.setCompareColumnList(new ArrayList<>());
        if (rule.getProbeColumnList() == null) rule.setProbeColumnList(new ArrayList<>());
    }

    private CompareRule rule(String value) throws Exception {
        CompareRule rule = blank(value) ? new CompareRule() : json.readValue(value, CompareRule.class);
        normalizeRule(rule);
        return rule;
    }

    private Set<String> outputs(List<SqlStep> steps) {
        return steps.stream().flatMap(step -> step.getOutputTables().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private List<String> stepNames(List<SqlStep> steps) {
        return steps.stream().map(SqlStep::getName).collect(Collectors.toList());
    }

    private String mappedTable(Map<String, String> mappings, String source) {
        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(source)) return entry.getValue();
        }
        return null;
    }

    private CompareTableRequest copyMapping(CompareTableRequest source) {
        CompareTableRequest copy = new CompareTableRequest();
        copy.setOriginalTable(source.getOriginalTable());
        copy.setBaselineSourceTable(source.getBaselineSourceTable());
        copy.setCandidateSourceTable(source.getCandidateSourceTable());
        copy.setBaselineTable(source.getBaselineTable());
        copy.setCandidateTable(source.getCandidateTable());
        copy.setRequiredByDdl(source.isRequiredByDdl());
        copy.setRule(source.getRule());
        return copy;
    }

    @Scheduled(fixedDelay = 15000L, initialDelay = 15000L)
    public void heartbeatRunningJobs() {
        futures.forEach((id, future) -> { if (!future.isDone()) repository.heartbeat(id, instanceId); });
    }

    private Map<String, Object> requireJob(long id) {
        Map<String, Object> job = repository.job(id);
        if (job == null) throw new IllegalArgumentException("验数任务不存在");
        return job;
    }
    private Map<String, Object> requireTable(long id) {
        Map<String, Object> table = repository.table(id);
        if (table == null) throw new IllegalArgumentException("表级验数不存在");
        return table;
    }
    private boolean isCancelling(long id) {
        Map<String, Object> job = repository.job(id);
        return job != null && "CANCELLING".equals(string(job.get("status")));
    }
    private void cancelStatement(Statement statement) { try { statement.cancel(); } catch (Exception ignored) { } }
    private void validatePage(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("分页参数非法");
    }
    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
    private static String string(Object value) { return value == null ? "" : value.toString(); }
    private static String nullableString(Object value) { return value == null ? null : value.toString(); }
    private static long number(Object value) { return Long.parseLong(value.toString()); }
    private static Long longValue(Object value) { return value == null ? null : Long.valueOf(value.toString()); }
    private static Integer integerValue(Object value) { return value == null ? null : Integer.valueOf(value.toString()); }
    private static boolean booleanValue(Object value) {
        return value instanceof Boolean ? (Boolean) value
                : value != null && ("1".equals(value.toString()) || "true".equalsIgnoreCase(value.toString()));
    }
    private static String checksum(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception error) { throw new IllegalStateException("无法计算校验和", error); }
    }
    private static String safe(Throwable error) {
        String value = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        String redacted = value.replaceAll("(?i)([a-z][a-z0-9+.-]*://)\\S+", "$1<redacted>");
        return redacted.substring(0, Math.min(4000, redacted.length()));
    }
    private static class CancelledException extends Exception { }
}
