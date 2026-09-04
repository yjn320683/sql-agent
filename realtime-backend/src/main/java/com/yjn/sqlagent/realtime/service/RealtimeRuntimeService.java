package com.yjn.sqlagent.realtime.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjn.sqlagent.realtime.common.PaimonSyncCommandBuilder;
import com.yjn.sqlagent.realtime.common.SubmissionSpec;
import com.yjn.sqlagent.realtime.config.RealtimeProperties;
import com.yjn.sqlagent.realtime.model.SyncTaskRequest;
import com.yjn.sqlagent.realtime.model.TaskActionRequest;
import com.yjn.sqlagent.realtime.model.UnifiedTaskRequest;
import com.yjn.sqlagent.realtime.repository.RealtimeSyncRepository;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class RealtimeRuntimeService {

    private static final Pattern APPLICATION_ID = Pattern.compile("application_\\d+_\\d+");
    private static final Pattern JOB_ID = Pattern.compile("(?i)(?:JobID|Job ID)[:\\s]+([0-9a-f]{32})");
    private static final Pattern TRACKING_URL = Pattern.compile("(?im)^\\s*Tracking-URL\\s*:\\s*(\\S+)");
    private final RealtimeSyncRepository repository;
    private final RealtimeProperties properties;
    private final ObjectMapper mapper;
    private final FlinkStateHistoryReader stateHistoryReader;
    private final RealtimeTaskOperationExecutor operationExecutor;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<Long, Integer> statusDetectionFailures = new ConcurrentHashMap<>();

    @Autowired
    public RealtimeRuntimeService(RealtimeSyncRepository repository, RealtimeProperties properties,
            ObjectMapper mapper, RealtimeTaskOperationExecutor operationExecutor) {
        this.repository = repository;
        this.properties = properties;
        this.mapper = mapper;
        this.stateHistoryReader = new FlinkStateHistoryReader(properties);
        this.operationExecutor = operationExecutor;
    }

    RealtimeRuntimeService(RealtimeSyncRepository repository, RealtimeProperties properties,
            ObjectMapper mapper) {
        this(repository, properties, mapper, new RealtimeTaskOperationExecutor(Runnable::run));
    }

    public Map<String, Object> previewSaved(long taskId, TaskActionRequest action, boolean debug) {
        Map<String, Object> task = repository.requiredTask(taskId);
        validateStart(action);
        SubmissionSpec spec = spec(effectiveTask(task, action, debug), null, null, action, debug ? "DEBUG" : "PRODUCTION");
        return preview(spec);
    }

    public Map<String, Object> previewRequest(SyncTaskRequest request, Long excludeTaskId) {
        Map<String, Object> task = new LinkedHashMap<>();
        // 编辑态预览沿用当前任务 ID；新建态才使用预览占位符，避免命令里出现误导性的 task-0。
        task.put("id", excludeTaskId == null ? 0L : excludeTaskId);
        task.put("name", request.getName()); task.put("sourceType", "mysql-cdc");
        task.put("sourceServerId", request.getSourceServerId()); task.put("targetDatabase", request.getTargetDatabase());
        Map<String, Object> config = repository.validatePreview(request, excludeTaskId);
        task.put("taskConfig", config);
        return preview(spec(task, null, null, new TaskActionRequest(), "PRODUCTION"));
    }

    public Map<String, Object> previewUnifiedRequest(UnifiedTaskRequest request, Long taskId) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("id", taskId == null ? 0L : taskId); task.put("name", request.getName()); task.put("taskType", request.getTaskType());
        task.put("sourceType", "export".equalsIgnoreCase(request.getTaskType()) ? "paimon" : "paimon");
        task.put("targetType", "export".equalsIgnoreCase(request.getTaskType()) ? "mysql" : "paimon");
        Map<String, Object> config = new LinkedHashMap<>(request.getTaskConfig());
        config.put("parallelism", request.getFlinkConf().get("parallelism"));
        config.put("checkpointInterval", request.getFlinkConf().get("checkpointIntervalSeconds"));
        config.put("taskManagerMemory", memory(request.getFlinkConf().get("taskManagerMemoryGb")));
        config.put("jobManagerMemory", memory(request.getFlinkConf().get("jobManagerMemoryGb")));
        config.put("flinkConfOverrides", request.getFlinkConf().getOrDefault("flinkConfOverrides", Map.of()));
        task.put("taskConfig", config);
        if ("export".equalsIgnoreCase(request.getTaskType())) {
            Map<String, Object> export = objectMap(config.get("exportConfig"));
            task.put("sourceServerId", export.get("targetServerId"));
        }
        return preview(spec(task, null, null, new TaskActionRequest(), "PRODUCTION"));
    }

    public Map<String, Object> start(long taskId, TaskActionRequest action, String actor, boolean debug) {
        Map<String, Object> task = repository.requiredTask(taskId);
        Map<String, Object> effectiveTask = effectiveTask(task, action, debug);
        validateStart(action);
        validateStatePath(taskId, action);
        if (!debug && "sync".equalsIgnoreCase(text(task.get("taskType"), "sync"))) validateRequiredRecovery(taskId, action);
        if (debug && repository.hasActiveManagedDebugInstance(taskId)) {
            throw new IllegalStateException("当前任务已有正在提交或运行的调试实例");
        }
        if (!debug && repository.hasActiveManagedInstance(taskId)) {
            throw new IllegalStateException("任务已有本平台管理的活动实例，请勿重复提交");
        }
        if (!debug && repository.hasActiveImportedInstance(taskId)) {
            throw new IllegalStateException("任务在参考平台仍有历史导入的活动实例，为防止双跑禁止再次提交");
        }
        if (!debug) {
            List<String> liveApplications = liveSyncApplications(taskId);
            if (liveApplications.size() > 1) {
                repository.addAlertIfOpenAbsent(taskId, "critical", taskLabel(task) + "疑似双跑",
                        "按任务名前缀检测到多个存活 YARN Application：" + liveApplications);
                throw new IllegalStateException("检测到多个旧实例仍在运行，请先处理残留实例或刷新状态");
            }
            if (!liveApplications.isEmpty()) {
                throw new IllegalStateException("检测到旧实例仍在运行，请先处理残留实例或刷新状态");
            }
        }
        Long versionId = repository.latestVersionId(taskId);
        String mode = debug ? "DEBUG" : "PRODUCTION";
        long operationId = repository.startOperation(taskId, null, debug ? "DEBUG_START" : "START", actor,
                json(action));
        long instanceId = 0L;
        try {
            instanceId = repository.insertInstance(taskId, versionId, mode, json(effectiveTask));
            repository.attachOperationInstance(operationId, instanceId);
            if (!debug) repository.changeTaskStatus(taskId, "submitting");
            long preparedInstanceId = instanceId;
            if (!operationExecutor.submit(taskId, debug ? "调试" : "启动",
                    () -> completeStart(taskId, task, effectiveTask, versionId, preparedInstanceId,
                            operationId, action, actor, debug))) {
                throw new IllegalStateException("任务已有后台操作处理中，请稍后刷新状态");
            }
            return repository.requiredInstance(taskId, instanceId);
        } catch (RuntimeException ex) {
            if (instanceId > 0) {
                repository.updateInstanceSubmission(instanceId, "failed", null, null, null, null, safe(ex));
            }
            repository.completeOperation(operationId, "FAILED", null, safe(ex));
            repository.changeTaskStatus(taskId, debug ? text(task.get("status"), "not_running") : "failed");
            repository.addAlert(taskId, "critical", taskLabel(task) + "提交失败", safe(ex));
            throw ex;
        }
    }

    public Map<String, Object> stop(long taskId, long instanceId, TaskActionRequest action, String actor) {
        Map<String, Object> instance = repository.requiredInstance(taskId, instanceId);
        if (!Boolean.TRUE.equals(instance.get("managed"))) {
            throw new IllegalStateException("历史导入实例为只读，禁止停止或接管");
        }
        String currentStatus = text(instance.get("status")).toLowerCase(Locale.ROOT);
        if (!List.of("submitting", "running", "debug_success_running", "stopping", "restarting").contains(currentStatus)) return instance;
        boolean debug = "DEBUG".equalsIgnoreCase(text(instance.get("executionMode")));
        String requestedStopType = text(action.getStopType(), debug ? "direct" : "savepoint").toLowerCase(Locale.ROOT);
        final String stopType = debug ? "direct" : requestedStopType;
        if (!debug && !"savepoint".equals(stopType)) {
            throw new IllegalArgumentException("正式实例仅支持 savepoint 停止");
        }
        if (!List.of("direct", "savepoint").contains(stopType)) {
            throw new IllegalArgumentException("停止类型必须是 direct 或 savepoint");
        }
        if ("savepoint".equals(stopType) && !"running".equals(currentStatus)) {
            throw new IllegalStateException("savepoint 停止只支持已确认 RUNNING 的 Flink Job，请先刷新状态后重试");
        }
        String jobId = text(instance.get("jobId"));
        String applicationId = text(instance.get("yarnApplicationId"));
        if (!debug && "savepoint".equals(stopType) && jobId.isEmpty()) {
            throw new IllegalStateException("正式实例缺少 Flink JobID，禁止降级为 YARN kill；请先刷新实例状态");
        }
        long operationId = repository.startOperation(taskId, instanceId,
                "DEBUG".equals(instance.get("executionMode")) ? "DEBUG_STOP" : "STOP", actor,
                json(Map.of("stopType", stopType)));
        repository.updateInstanceRuntime(instanceId, "stopping", text(instance.get("lastRuntimeLog")), null);
        if (!debug) repository.changeTaskStatus(taskId, "stopping");
        try {
            if (!operationExecutor.submit(taskId, debug ? "调试停止" : "停止",
                    () -> completeStop(taskId, instanceId, instance, stopType, jobId, applicationId,
                            operationId, actor, debug))) {
                throw new IllegalStateException("任务已有后台操作处理中，请稍后刷新状态");
            }
            return repository.requiredInstance(taskId, instanceId);
        } catch (RuntimeException ex) {
            repository.updateInstanceRuntime(instanceId, "running", text(instance.get("lastRuntimeLog")), safe(ex));
            repository.completeOperation(operationId, "FAILED", null, safe(ex));
            if (!debug) repository.changeTaskStatus(taskId, "running");
            repository.addAlert(taskId, "warning", "同步任务停止失败", safe(ex));
            throw ex;
        }
    }

    private void completeStart(long taskId, Map<String, Object> task, Map<String, Object> effectiveTask,
            Long versionId, long instanceId, long operationId, TaskActionRequest action,
            String actor, boolean debug) {
        try {
            String mode = debug ? "DEBUG" : "PRODUCTION";
            SubmissionSpec spec = spec(effectiveTask, versionId, instanceId, action, mode);
            StoredSpec stored = store(spec);
            List<String> command = flinkCommand(spec, stored, action.isDryRun());
            CommandResult result = execute(command, 180);
            String applicationId = match(APPLICATION_ID, result.output, 0);
            String jobId = match(JOB_ID, result.output, 1);
            String trackingUrl = applicationId.isEmpty() ? "" : yarnTrackingUrl(applicationId);
            if (result.exitCode != 0) {
                throw new IllegalStateException("Flink 提交失败：" + tail(result.output, 4000));
            }
            String status = "submitting";
            String startupLog = result.output;
            if (action.isDryRun()) {
                CommandResult terminal = awaitYarnTerminal(applicationId, 120);
                status = yarnStatus(terminal.output);
                startupLog = append(startupLog, terminal.output);
                String terminalTrackingUrl = usableTrackingUrl(match(TRACKING_URL, terminal.output, 1));
                if (!terminalTrackingUrl.isEmpty()) trackingUrl = terminalTrackingUrl;
                if (!"finished".equals(status)) {
                    throw new IllegalStateException("DEBUG Dry Run 未成功结束：" + tail(terminal.output, 4000));
                }
            } else if (jobId.isEmpty() && !trackingUrl.isEmpty()) {
                jobId = discoverJobId(trackingUrl, 30);
            }
            repository.updateInstanceSubmission(instanceId, status, empty(jobId), empty(applicationId),
                    empty(trackingUrl), tail(mask(startupLog), 1024 * 1024), null);
            if (!debug) repository.changeTaskStatus(taskId, status);
            Map<String, Object> operationResult = new LinkedHashMap<>();
            operationResult.put("taskInstanceId", instanceId); operationResult.put("instanceId", instanceId);
            operationResult.put("jobId", jobId);
            operationResult.put("yarnApplicationId", applicationId);
            operationResult.put("startType", text(action.getStartType(), "direct"));
            operationResult.put("statePath", text(action.getStatePath()));
            repository.completeOperation(operationId, "SUCCESS", json(operationResult), null);
            if (isProductionLifecycleChange(debug)) {
                repository.addChange(taskId, operationId, versionId, instanceId, actor,
                        "START", "提交同步生产实例");
            }
        } catch (RuntimeException ex) {
            repository.updateInstanceSubmission(instanceId, "failed", null, null, null, null, safe(ex));
            repository.completeOperation(operationId, "FAILED", null, safe(ex));
            if (!debug) repository.changeTaskStatus(taskId, "failed");
            repository.addAlert(taskId, "critical", "同步任务提交失败", safe(ex));
        }
    }

    private void completeStop(long taskId, long instanceId, Map<String, Object> instance,
            String stopType, String jobId, String applicationId, long operationId,
            String actor, boolean debug) {
        try {
            String actualMethod = !jobId.isEmpty()
                    ? ("savepoint".equals(stopType) ? "flink_savepoint" : "flink_cancel")
                    : "yarn_kill";
            boolean fallbackToYarnKill = false;
            boolean canFallbackToYarn = "direct".equals(stopType) && !applicationId.isEmpty()
                    && !jobId.isEmpty();
            CommandResult result;
            String combinedOutput;
            try {
                result = execute(stopCommand(taskId, jobId, applicationId, stopType), 180);
                combinedOutput = result.output;
            } catch (RuntimeException ex) {
                if (!canFallbackToYarn) throw ex;
                fallbackToYarnKill = true;
                actualMethod = "yarn_kill";
                combinedOutput = safe(ex);
                result = execute(
                        List.of(properties.getYarnBin(), "application", "-kill", applicationId), 60);
                combinedOutput = append(combinedOutput, result.output);
            }
            if (result.exitCode != 0 && canFallbackToYarn && !fallbackToYarnKill) {
                fallbackToYarnKill = true;
                actualMethod = "yarn_kill";
                CommandResult fallback = execute(
                        List.of(properties.getYarnBin(), "application", "-kill", applicationId), 60);
                combinedOutput = append(combinedOutput, fallback.output);
                result = fallback;
            }
            if (result.exitCode != 0) {
                throw new IllegalStateException("Flink 停止失败：" + tail(combinedOutput, 4000));
            }
            String savepoint = parseSavepoint(combinedOutput);
            repository.updateSavepointPath(instanceId, savepoint);
            String terminalStatus = debug
                    ? ("debug_success_running".equalsIgnoreCase(text(instance.get("status")))
                            ? "killed_success" : "canceled")
                    : ("savepoint".equals(stopType) ? "finished" : "canceled");
            repository.updateInstanceSubmission(instanceId, terminalStatus, empty(jobId), empty(applicationId),
                    text(instance.get("trackingUrl")),
                    append(text(instance.get("startupLog")), mask(combinedOutput)), null);
            if (!debug) repository.changeTaskStatus(taskId, "not_running");
            Map<String, Object> operationResult = new LinkedHashMap<>();
            operationResult.put("stopType", stopType); operationResult.put("actualMethod", actualMethod);
            operationResult.put("fallbackToYarnKill", fallbackToYarnKill);
            operationResult.put("instanceStatus", terminalStatus);
            operationResult.put("jobId", jobId); operationResult.put("yarnApplicationId", applicationId);
            operationResult.put("savepointPath", savepoint);
            repository.completeOperation(operationId, "SUCCESS", json(operationResult), null);
            if (isProductionLifecycleChange(debug)) {
                repository.addChange(taskId, operationId, null, instanceId, actor,
                        "STOP", savepoint.isEmpty() ? "停止类型：" + stopType
                                : "停止类型：" + stopType + "，savepoint：" + savepoint);
            }
        } catch (RuntimeException ex) {
            repository.updateInstanceRuntime(instanceId, "running", text(instance.get("lastRuntimeLog")), safe(ex));
            repository.completeOperation(operationId, "FAILED", null, safe(ex));
            if (!debug) repository.changeTaskStatus(taskId, "running");
            repository.addAlert(taskId, "warning", "同步任务停止失败", safe(ex));
        }
    }

    public Map<String, Object> refresh(long taskId, long instanceId) {
        return refresh(taskId, instanceId, null);
    }

    static boolean isProductionLifecycleChange(boolean debug) {
        return !debug;
    }

    public Map<String, Object> refresh(long taskId, long instanceId, String actor) {
        Map<String, Object> instance = repository.requiredInstance(taskId, instanceId);
        if (!Boolean.TRUE.equals(instance.get("managed"))) {
            throw new IllegalStateException("历史导入实例为只读，禁止刷新或接管");
        }
        return refreshManaged(instance, true, actor);
    }

    public Object runtime(long taskId, long instanceId) {
        Map<String, Object> instance = repository.requiredInstance(taskId, instanceId);
        String jobId = requiredJobId(instance);
        Map<String, Object> job = objectMap(fetch(instance, "/jobs/" + jobId));
        Map<String, Object> result = runtimeBase(instance);
        result.put("available", true);
        result.put("status", text(job.get("state"), text(instance.get("status"))));
        result.put("uptimeMs", job.get("duration"));
        Topology topology = topology(job);
        List<Map<String, Object>> vertices = new ArrayList<>();
        for (Object value : objectList(job.get("vertices"))) {
            Map<String, Object> vertex = objectMap(value);
            String vertexId = text(vertex.get("id"));
            if (vertexId.isEmpty()) continue;
            try {
                vertices.add(vertexMetrics(instance, jobId, vertex, topology.role(vertexId)));
            } catch (RuntimeException ex) {
                Map<String, Object> row = vertexRow(vertex, topology.role(vertexId));
                row.put("unavailableReason", "算子指标读取失败：" + safe(ex));
                vertices.add(row);
            }
        }
        result.put("vertices", vertices);
        result.put("sync", syncSummary(vertices, topology));
        try {
            Map<String, String> metrics = metricValues(instance, "/jobs/" + jobId + "/metrics", "numRestarts");
            result.put("restartCount", metricNumber(metrics, "numRestarts"));
            if (result.get("restartCount") == null) result.put("restartCountUnavailableReason", "Flink Job 未提供 numRestarts");
        } catch (RuntimeException ex) {
            result.put("restartCountUnavailableReason", "重启次数读取失败：" + safe(ex));
        }
        try {
            Map<String, Object> exceptions = objectMap(fetch(instance,
                    "/jobs/" + jobId + "/exceptions?maxExceptions=1&truncateString=true"));
            List<Object> rows = objectList(exceptions.get("all-exceptions"));
            Map<String, Object> latest = rows.isEmpty() ? exceptions : objectMap(rows.get(0));
            String message = text(latest.get("exception"), text(exceptions.get("root-exception")));
            if (!message.isEmpty()) {
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("exception", message); error.put("timestamp", latest.get("timestamp"));
                error.put("taskName", latest.get("task")); error.put("location", latest.get("location"));
                result.put("latestException", error);
            }
        } catch (RuntimeException ex) {
            result.put("latestExceptionUnavailableReason", safe(ex));
        }
        return result;
    }

    public Object resources(long taskId, long instanceId) {
        Map<String, Object> instance = repository.requiredInstance(taskId, instanceId);
        Map<String, Object> result = runtimeBase(instance);
        Map<String, Object> configured = new LinkedHashMap<>();
        Map<String, Object> snapshot = objectMap(instance.get("config"));
        Map<String, Object> taskConfig = objectMap(snapshot.get("taskConfig"));
        if (taskConfig.isEmpty()) taskConfig = snapshot;
        configured.put("parallelism", taskConfig.get("parallelism"));
        configured.put("checkpointIntervalSeconds", taskConfig.get("checkpointInterval"));
        result.put("configured", configured);
        Map<String, Object> root = objectMap(fetch(instance, "/taskmanagers"));
        List<Map<String, Object>> managers = new ArrayList<>();
        int totalSlots = 0; int freeSlots = 0;
        for (Object value : objectList(root.get("taskmanagers"))) {
            Map<String, Object> manager = objectMap(value);
            Map<String, Object> row = new LinkedHashMap<>();
            String id = text(manager.get("id"));
            int slots = integer(manager.get("slotsNumber"), integer(manager.get("slots-number"), 0));
            int free = integer(manager.get("freeSlots"), integer(manager.get("free-slots"), 0));
            totalSlots += slots; freeSlots += free;
            row.put("id", id); row.put("path", manager.get("path")); row.put("slots", slots); row.put("freeSlots", free);
            Map<String, String> metrics = metricValues(instance, "/taskmanagers/" + encode(id) + "/metrics",
                    "Status.JVM.Memory.Heap.Used", "Status.JVM.Memory.Heap.Committed",
                    "Status.Flink.Memory.Managed.Used", "Status.Shuffle.Netty.UsedMemory",
                    "Status.Shuffle.Netty.AvailableMemory", "Status.Shuffle.Netty.TotalMemory", "Status.JVM.CPU.Load");
            row.put("heapUsed", metricNumber(metrics, "Status.JVM.Memory.Heap.Used"));
            row.put("heapCommitted", metricNumber(metrics, "Status.JVM.Memory.Heap.Committed"));
            row.put("managedMemoryUsed", metricNumber(metrics, "Status.Flink.Memory.Managed.Used"));
            Double networkUsed = metricNumber(metrics, "Status.Shuffle.Netty.UsedMemory");
            if (networkUsed == null) {
                Double total = metricNumber(metrics, "Status.Shuffle.Netty.TotalMemory");
                Double available = metricNumber(metrics, "Status.Shuffle.Netty.AvailableMemory");
                if (total != null && available != null) networkUsed = Math.max(0D, total - available);
            }
            row.put("networkMemoryUsed", networkUsed); row.put("cpuLoad", metricNumber(metrics, "Status.JVM.CPU.Load"));
            managers.add(row);
        }
        Map<String, String> jmMetrics = metricValues(instance, "/jobmanager/metrics",
                "Status.JVM.Memory.Heap.Used", "Status.JVM.Memory.NonHeap.Used", "Status.JVM.CPU.Load");
        Map<String, Object> flink = new LinkedHashMap<>();
        flink.put("jobManagerHeapUsed", metricNumber(jmMetrics, "Status.JVM.Memory.Heap.Used"));
        flink.put("jobManagerNonHeapUsed", metricNumber(jmMetrics, "Status.JVM.Memory.NonHeap.Used"));
        flink.put("jobManagerCpuLoad", metricNumber(jmMetrics, "Status.JVM.CPU.Load"));
        flink.put("taskManagerCount", managers.size()); flink.put("totalSlots", totalSlots);
        flink.put("usedSlots", Math.max(0, totalSlots - freeSlots)); flink.put("taskManagers", managers);
        result.put("flink", flink);
        result.put("yarn", yarnResources(instance)); result.put("available", true);
        return result;
    }

    public Object checkpoints(long taskId, long instanceId) {
        Map<String, Object> instance = repository.requiredInstance(taskId, instanceId);
        Map<String, Object> raw = objectMap(fetch(instance, "/jobs/" + requiredJobId(instance) + "/checkpoints"));
        Map<String, Object> result = runtimeBase(instance);
        result.put("counts", raw.get("counts")); result.put("summary", raw.get("summary"));
        result.put("latest", raw.get("latest")); result.put("history", raw.get("history"));
        result.put("available", true);
        return result;
    }

    public Object stateHistory(long taskId, String type) {
        repository.requiredTask(taskId);
        List<Map<String, Object>> result = new ArrayList<>(stateHistoryReader.list(taskId, type));
        for (Map<String, Object> instance : repository.instances(taskId)) {
            String path = text(instance.get("savepointPath"));
            if (!path.isEmpty() && ("savepoint".equalsIgnoreCase(type) || text(type).isEmpty())) {
                boolean exists = result.stream().anyMatch(item -> path.equals(text(item.get("path"))));
                if (!exists && stateHistoryReader.exists(taskId, "savepoint", path)) result.add(Map.of("type", "savepoint", "path", path,
                        "label", text(instance.get("updateTime")) + " (" + path.substring(path.lastIndexOf('/') + 1) + ")",
                        "instanceId", instance.get("id"), "createTime", instance.get("updateTime")));
            }
        }
        return result;
    }

    public Object logComponents(long taskId, long instanceId) {
        Map<String, Object> instance = repository.requiredInstance(taskId, instanceId);
        List<Map<String, String>> result = new ArrayList<>();
        result.add(Map.of("value", "all", "label", "全部日志"));
        result.add(Map.of("value", "startup", "label", "启动日志"));
        result.add(Map.of("value", "jobmanager", "label", "JobManager"));
        result.add(Map.of("value", "taskmanager", "label", "TaskManager"));
        if (isTerminal(text(instance.get("status"))) || text(instance.get("trackingUrl")).isEmpty()) return result;
        Object rows = objectMap(fetch(instance, "/taskmanagers")).get("taskmanagers");
        if (rows instanceof List) {
            for (Object row : (List<?>) rows) {
                String id = text(objectMap(row).get("id"));
                if (!id.isEmpty()) result.add(Map.of("value", "taskmanager:" + id, "label", "TaskManager " + id));
            }
        }
        return result;
    }

    public Object logs(long taskId, long instanceId, String component, String file) {
        Map<String, Object> instance = repository.requiredInstance(taskId, instanceId);
        String normalized = text(component, "jobmanager").toLowerCase(Locale.ROOT);
        if (isTerminal(text(instance.get("status"))) || "yarn".equals(normalized)) {
            return Map.of("content", text(instance.get("lastRuntimeLog")),
                    "runtimeLog", text(instance.get("lastRuntimeLog")), "source", "cache",
                    "component", "yarn", "imported", !Boolean.TRUE.equals(instance.get("managed")));
        }
        String path;
        String selectedFile;
        if (normalized.startsWith("taskmanager:")) {
            String taskManagerId = component.substring("taskmanager:".length());
            path = "/taskmanagers/" + encode(taskManagerId) + "/log";
            selectedFile = text(file, "taskmanager.log");
        } else {
            path = "/jobmanager/log";
            selectedFile = text(file, "jobmanager.log");
            normalized = "jobmanager";
        }
        String content = fetchText(instance, path);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", content); result.put("runtimeLog", content);
        result.put("source", "flink-rest"); result.put("component", normalized);
        result.put("selectedFile", selectedFile); result.put("truncated", false);
        result.put("imported", !Boolean.TRUE.equals(instance.get("managed")));
        return result;
    }

    public Map<String, Object> logPage(long taskId, long instanceId, String component,
            String containerId, int cursor, int limit) {
        String selected = text(component, "all").toLowerCase(Locale.ROOT);
        if (selected.startsWith("taskmanager:") && text(containerId).isEmpty()) {
            containerId = selected.substring("taskmanager:".length());
            selected = "taskmanager";
        }
        Object payload;
        if ("startup".equals(selected)) {
            payload = Map.of("content", text(repository.requiredInstance(taskId, instanceId).get("startupLog")));
        } else if ("taskmanager".equals(selected) && !text(containerId).isEmpty()) {
            payload = logs(taskId, instanceId, "taskmanager:" + containerId, null);
        } else if ("jobmanager".equals(selected)) {
            payload = logs(taskId, instanceId, "jobmanager", null);
        } else {
            payload = logs(taskId, instanceId, "yarn", null);
            selected = "all";
        }
        String content = payload instanceof Map ? text(((Map<?, ?>) payload).get("content")) : text(payload);
        String[] lines = content.split("\\R", -1);
        int safeCursor = Math.max(0, Math.min(cursor, lines.length));
        int safeLimit = Math.max(20, Math.min(limit, 5000));
        int end = Math.min(lines.length, safeCursor + safeLimit);
        List<String> page = new ArrayList<>();
        for (int index = safeCursor; index < end; index++) page.add(lines[index]);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instanceId", instanceId); result.put("lines", page);
        result.put("nextCursor", end); result.put("truncated", end < lines.length);
        result.put("component", selected); result.put("containerId", empty(text(containerId)));
        result.put("updatedAt", java.time.OffsetDateTime.now().toString());
        return result;
    }

    public String downloadLog(long taskId, long instanceId, String component, String file) {
        Object value = logs(taskId, instanceId, component, file);
        if (value instanceof Map) return text(((Map<?, ?>) value).get("content"));
        return text(value);
    }

    @Scheduled(fixedDelayString = "${app.realtime.sync-delay-ms:10000}")
    public void reconcile() {
        if (!properties.isEnabled() || !properties.isStateSyncEnabled()) return;
        for (Map<String, Object> instance : repository.reconcileManagedInstances()) {
            long taskId = number(instance.get("taskId"));
            if (operationExecutor.isRunning(taskId)) continue;
            try { refreshManaged(instance, false, null); } catch (RuntimeException ignored) { }
        }
    }

    @Scheduled(fixedDelayString = "${app.realtime.operation-recovery-delay-ms:60000}")
    public void recoverTimedOutOperations() {
        if (!properties.isEnabled()) return;
        for (Map<String, Object> operation : repository.expiredActiveOperations()) {
            long taskId = number(operation.get("taskId"));
            if (operationExecutor.isRunning(taskId)) continue;
            Long instanceId = nullableNumber(operation.get("taskInstanceId"));
            if (instanceId == null) {
                repository.addAlertIfOpenAbsent(taskId, "warning", "同步任务操作超时待人工确认",
                        "操作超过截止时间但未关联运行实例，无法确认外部作业状态，任务锁未自动释放");
                continue;
            }
            Map<String, Object> instance;
            try { instance = repository.requiredInstance(taskId, instanceId); }
            catch (RuntimeException ex) {
                repository.addAlertIfOpenAbsent(taskId, "warning", "同步任务操作超时待人工确认",
                        "操作超过截止时间且关联实例不存在，无法确认外部作业状态，任务锁未自动释放");
                continue;
            }
            if (isTerminal(text(instance.get("status")))) {
                repository.timeoutOperation(number(operation.get("id")), "操作超过截止时间，关联实例已确认终态");
                continue;
            }
            try {
                ObservedStatus observed = observeStatus(instance);
                if (isTerminal(observed.status)) {
                    repository.timeoutOperation(number(operation.get("id")),
                            "操作超过截止时间，外部作业已确认终态：" + observed.status);
                }
            } catch (RuntimeException ignored) { }
        }
    }

    private Map<String, Object> refreshManaged(Map<String, Object> instance, boolean manual, String actor) {
        long taskId = number(instance.get("taskId"));
        long instanceId = number(instance.get("id"));
        if (operationExecutor.isRunning(taskId)) {
            if (manual) throw new IllegalStateException("任务后台操作仍在执行，请稍后刷新");
            return repository.requiredInstance(taskId, instanceId);
        }
        ObservedStatus observed = observeStatus(instance);
        repository.updateInstanceIdentifiers(instanceId, observed.jobId,
                observed.applicationId, observed.trackingUrl);
        String status = observed.status;
        if ("DEBUG".equalsIgnoreCase(text(instance.get("executionMode"))) && "running".equals(status)) {
            Map<String, Object> refreshed = new LinkedHashMap<>(instance);
            refreshed.put("jobId", observed.jobId); refreshed.put("yarnApplicationId", observed.applicationId);
            refreshed.put("trackingUrl", observed.trackingUrl);
            if ("debug_success_running".equalsIgnoreCase(text(instance.get("status")))
                    || hasDebugSuccessEvidence(refreshed)) status = "debug_success_running";
        }
        if ("unknown".equals(status)) {
            int failures = statusDetectionFailures.merge(instanceId, 1, Integer::sum);
            if (failures >= 3) {
                repository.addAlertIfOpenAbsent(taskId, "warning", "同步任务状态检测异常",
                        "连续 " + failures + " 次无法从 YARN/Flink 确认实例 " + instanceId + " 的运行状态，数据库状态保持不变");
            }
            return repository.requiredInstance(taskId, instanceId);
        }
        statusDetectionFailures.remove(instanceId);
        String previous = text(instance.get("status"));
        if (isTerminal(previous) && isTerminal(status) && !previous.equals(status)) {
            return repository.requiredInstance(taskId, instanceId);
        }
        if (!previous.equals(status)) {
            String failure = "failed".equals(status) ? tail(mask(observed.output), 4000) : null;
            repository.updateInstanceRuntime(instanceId, status, tail(mask(observed.output), 1024 * 1024), failure);
            if (!"DEBUG".equals(instance.get("executionMode"))) {
                repository.changeTaskStatus(taskId, isTerminal(status) ? ("failed".equals(status) ? "failed" : "not_running") : status);
            }
            if ("stopping".equals(previous) && ("running".equals(status)
                    || "debug_success_running".equals(status) || "restarting".equals(status))) {
                repository.addAlert(taskId, "warning", "同步任务停止未生效",
                        "停止请求后外部作业仍为 " + status + "，任务状态已恢复");
            } else if ("failed".equals(status)) {
                repository.addAlert(taskId, "critical", "同步任务运行失败",
                        failure == null || failure.isEmpty() ? "外部 Flink 作业已失败" : failure);
            } else if (isTerminal(status) && !"stopping".equals(previous)) {
                repository.addAlert(taskId, "warning", "同步任务状态纠偏",
                        "外部作业状态为 " + status + "，数据库状态从 " + previous + " 修正");
            }
            if (!"DEBUG".equalsIgnoreCase(text(instance.get("executionMode")))) {
                String changeActor = actor == null || actor.trim().isEmpty() ? "system" : actor.trim();
                repository.addChange(taskId, null, null, instanceId, changeActor, "REFRESH",
                        (manual ? "手动刷新" : "状态对账") + "：实例状态 " + previous + " → " + status);
            }
        }
        return repository.requiredInstance(taskId, instanceId);
    }

    /** 调试通过必须持续运行到最短时长，并至少完成一次当前运行窗口内的 Checkpoint。 */
    private boolean hasDebugSuccessEvidence(Map<String, Object> instance) {
        String jobId = text(instance.get("jobId"));
        if (jobId.isEmpty() || text(instance.get("trackingUrl")).isEmpty()) return false;
        try {
            Map<String, Object> job = objectMap(fetch(instance, "/jobs/" + jobId));
            Map<String, Object> timestamps = objectMap(job.get("timestamps"));
            long runningTimestamp = number(timestamps.get("RUNNING"));
            long now = number(job.get("now"));
            if (now <= 0) now = System.currentTimeMillis();
            int configured = properties.getDebugSuccessMinRunningMinutes();
            long minimum = Math.max(1, configured) * 60_000L;
            if (runningTimestamp <= 0 || now - runningTimestamp <= minimum) return false;
            Map<String, Object> checkpoint = objectMap(fetch(instance, "/jobs/" + jobId + "/checkpoints"));
            Map<String, Object> counts = objectMap(checkpoint.get("counts"));
            if (number(counts.get("completed")) < 1) return false;
            Map<String, Object> latest = objectMap(checkpoint.get("latest"));
            Map<String, Object> completed = objectMap(latest.get("completed"));
            long acknowledged = number(completed.get("latest_ack_timestamp"));
            return acknowledged >= runningTimestamp && acknowledged <= now;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private ObservedStatus observeStatus(Map<String, Object> source) {
        Map<String, Object> instance = new LinkedHashMap<>(source);
        long taskId = number(instance.get("taskId"));
        String applicationId = text(instance.get("yarnApplicationId"));
        String jobId = text(instance.get("jobId"));
        String trackingUrl = text(instance.get("trackingUrl"));
        String yarn = "unknown";
        String output = "";
        boolean prefixLookupCompleted = false;
        if (applicationId.isEmpty() && "PRODUCTION".equalsIgnoreCase(text(instance.get("executionMode")))) {
            try {
                List<String> applications = liveSyncApplications(taskId);
                prefixLookupCompleted = true;
                if (applications.size() > 1) {
                    repository.addAlertIfOpenAbsent(taskId, "critical", "同步任务疑似双跑",
                            "按任务名前缀检测到多个存活 YARN Application：" + applications);
                }
                if (!applications.isEmpty()) applicationId = applications.get(0);
            } catch (RuntimeException ex) {
                output = safe(ex);
            }
        }
        if (!applicationId.isEmpty()) {
            try {
                CommandResult result = execute(List.of(properties.getYarnBin(), "application", "-status", applicationId), 30);
                output = result.output;
                yarn = yarnStatus(output);
                String discoveredTracking = usableTrackingUrl(match(TRACKING_URL, output, 1));
                if (!discoveredTracking.isEmpty()) trackingUrl = discoveredTracking;
            } catch (RuntimeException ex) {
                output = safe(ex);
            }
        }
        if (jobId.isEmpty() && !trackingUrl.isEmpty()) jobId = discoverJobId(trackingUrl, 1);
        String flink = "unknown";
        if (!trackingUrl.isEmpty() && !jobId.isEmpty()) {
            try {
                instance.put("trackingUrl", trackingUrl);
                Map<String, Object> job = objectMap(fetch(instance, "/jobs/" + jobId));
                flink = flinkStatus(text(job.get("state")));
            } catch (RuntimeException ex) {
                output = append(output, safe(ex));
                if (applicationId.isEmpty()) {
                    return new ObservedStatus(resolveObservedStatus(yarn, flink), output,
                            jobId, applicationId, trackingUrl);
                }
                try {
                    CommandResult listed = execute(List.of(properties.getFlinkBin(), "list", "-t",
                            "yarn-application", "-Dyarn.application.id=" + applicationId), 30);
                    output = append(output, listed.output);
                    if (listed.exitCode == 0) {
                        if (listed.output.contains(jobId)) flink = "running";
                        else if (!withinJobVisibilityGrace(instance)) flink = "canceled";
                    }
                } catch (RuntimeException cliFailure) {
                    output = append(output, safe(cliFailure));
                }
            }
        }
        String status = resolveObservedStatus(yarn, flink);
        if ("unknown".equals(status) && prefixLookupCompleted && applicationId.isEmpty() && jobId.isEmpty()) {
            status = "canceled";
            output = append(output, "未发现任务名前缀对应的存活 YARN Application");
        }
        return new ObservedStatus(status, output, jobId, applicationId, trackingUrl);
    }

    private boolean withinJobVisibilityGrace(Map<String, Object> instance) {
        String value = text(instance.get("startedAt"), text(instance.get("createTime")));
        if (value.isEmpty()) return false;
        try {
            return LocalDateTime.parse(value.replace(' ', 'T')).plusMinutes(5).isAfter(LocalDateTime.now());
        } catch (RuntimeException ignored) {
            try {
                return Instant.parse(value).plus(Duration.ofMinutes(5)).isAfter(Instant.now());
            } catch (RuntimeException ignoredAgain) {
                return false;
            }
        }
    }

    static String resolveObservedStatus(String yarnStatus, String flinkStatus) {
        if (List.of("failed", "canceled", "finished").contains(yarnStatus)) return yarnStatus;
        if (flinkStatus != null && !"unknown".equals(flinkStatus)) return flinkStatus;
        return yarnStatus == null || yarnStatus.isEmpty() ? "unknown" : yarnStatus;
    }

    private Map<String, Object> preview(SubmissionSpec spec) {
        List<String> submitCommand = flinkCommand(spec,
                new StoredSpec(previewSubmissionFile(spec), "<config-sha256>"), false);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("command", shell(submitCommand));
        if ("sync".equalsIgnoreCase(spec.getTask().getTaskType())) {
            PaimonSyncCommandBuilder.Command action = new PaimonSyncCommandBuilder().build(spec);
            result.put("arguments", action.maskedArguments()); result.put("paimonActionJar", action.getJarPath());
        } else {
            result.put("arguments", List.of("--submission-file", "<submission-spec>", "--config-sha256", "<config-sha256>"));
            result.put("runner", spec.getTask().getTaskType());
        }
        result.put("submission", Map.of(
                "taskId", spec.getTaskId(),
                "versionId", spec.getVersionId() == null ? "" : spec.getVersionId(),
                "taskInstanceId", spec.getTaskInstanceId() == null ? "" : spec.getTaskInstanceId(),
                "startType", text(spec.getStartType(), "direct"),
                "executionMode", text(spec.getExecutionMode(), "PRODUCTION")));
        return result;
    }

    private SubmissionSpec spec(Map<String, Object> task, Long versionId, Long instanceId,
            TaskActionRequest action, String mode) {
        SubmissionSpec spec = new SubmissionSpec();
        spec.setTaskId(number(task.get("id"))); spec.setVersionId(versionId); spec.setTaskInstanceId(instanceId);
        spec.setJobName(text(task.get("name"))); spec.setStartType(text(action.getStartType(), "direct"));
        spec.setStatePath(text(action.getStatePath())); spec.setExecutionMode(mode);
        SubmissionSpec.TaskSpec taskSpec = new SubmissionSpec.TaskSpec();
        taskSpec.setId(number(task.get("id"))); taskSpec.setTaskConfig(objectMap(task.get("taskConfig")));
        taskSpec.setTaskType(text(task.get("taskType"), "sync"));
        taskSpec.setSourceType(text(task.get("sourceType"), "sync".equalsIgnoreCase(taskSpec.getTaskType()) ? "mysql-cdc" : "paimon"));
        taskSpec.setTargetType(text(task.get("targetType"), "export".equalsIgnoreCase(taskSpec.getTaskType()) ? "mysql" : "paimon"));
        Object parallelism = taskSpec.getTaskConfig().get("parallelism");
        if (parallelism instanceof Number) taskSpec.setParallelism(((Number) parallelism).intValue());
        Object checkpoint = taskSpec.getTaskConfig().get("checkpointInterval");
        if (checkpoint instanceof Number) taskSpec.setCheckpointInterval(((Number) checkpoint).intValue());
        spec.setTask(taskSpec);
        SubmissionSpec.RuntimeConfig runtime = new SubmissionSpec.RuntimeConfig();
        runtime.setPaimonActionJarPath(properties.getPaimonActionJarPath());
        runtime.setPaimonWarehouse(properties.getPaimonWarehouse());
        runtime.setPaimonDebugWarehouse(properties.getPaimonDebugWarehouse());
        runtime.setTargetDatabase(text(task.get("targetDatabase"), properties.getTargetDatabase()));
        runtime.setCatalogConf(properties.getCatalogConf()); runtime.setDefaultTableConf(properties.getDefaultTableConf());
        runtime.setMysqlDefaultConf(properties.getMysqlDefaultConf()); spec.setRuntimeConfig(runtime);
        if (task.get("sourceServerId") != null) {
            long serverId = number(task.get("sourceServerId")); Map<String, Object> source = repository.requiredServer(serverId, true);
            SubmissionSpec.ServerSnapshot server = new SubmissionSpec.ServerSnapshot();
            server.setId(serverId); server.setName(text(source.get("name"))); server.setAddress(text(source.get("address")));
            server.setDatabaseName(text(source.get("databaseName"))); server.setDatabasePrefix(text(source.get("databasePrefix")));
            server.setAccount(text(source.get("account"))); server.setPassword(text(source.get("password"))); spec.setServers(List.of(server));
        }
        spec.setConfigHash(sha256(json(spec).getBytes(StandardCharsets.UTF_8)));
        return spec;
    }

    private Map<String, Object> effectiveTask(Map<String, Object> task, TaskActionRequest action, boolean debug) {
        Map<String, Object> result = new LinkedHashMap<>(task);
        Map<String, Object> config = new LinkedHashMap<>(objectMap(task.get("taskConfig")));
        if (action.getParallelism() != null) config.put("parallelism", action.getParallelism());
        if (action.getCheckpointInterval() != null) config.put("checkpointInterval", action.getCheckpointInterval());
        if (!text(action.getTaskManagerMemory()).isEmpty()) config.put("taskManagerMemory", action.getTaskManagerMemory());
        if (!text(action.getJobManagerMemory()).isEmpty()) config.put("jobManagerMemory", action.getJobManagerMemory());
        if (action.getFlinkConfOverrides() != null) config.put("flinkConfOverrides", action.getFlinkConfOverrides());
        config.put("startType", text(action.getStartType(), "direct"));
        if (!text(action.getStatePath()).isEmpty()) config.put("statePath", action.getStatePath());
        else config.remove("statePath");
        if (!"sync".equalsIgnoreCase(text(task.get("taskType"), "sync"))) { result.put("taskConfig", config); return result; }
        Map<String, Object> cdc = new LinkedHashMap<>(objectMap(config.get("cdcConfig")));
        if (action.getMysqlConfOverrides() != null) cdc.put("mysqlConfOverrides", action.getMysqlConfOverrides());
        if (action.getTableConfOverrides() != null) cdc.put("tableConfOverrides", action.getTableConfOverrides());
        config.put("cdcConfig", cdc); result.put("taskConfig", config);
        if (debug) applyDebugTarget(result, config, cdc);
        return result;
    }

    private void applyDebugTarget(Map<String, Object> task, Map<String, Object> config,
            Map<String, Object> cdc) {
        String debugDatabase = text(properties.getPaimonDebugTargetDatabase(), "paimon_debug");
        String suffix = text(cdc.get("tableSuffix"));
        String domain = text(cdc.get("domainPrefix"));
        String databasePrefix = "";
        String sourceDatabase = text(cdc.get("databaseName"));
        try {
            Map<String, Object> server = repository.requiredServer(number(task.get("sourceServerId")), true);
            databasePrefix = text(server.get("databasePrefix"));
            if (sourceDatabase.isEmpty()) sourceDatabase = text(server.get("databaseName"));
        }
        catch (RuntimeException ignored) { }
        String prefix;
        if (!domain.isEmpty() && !sourceDatabase.isEmpty()) prefix = debugDatabase + "_"
                + (databasePrefix.isEmpty() ? "" : databasePrefix + "_") + sourceDatabase + "_" + domain + "_";
        else {
            String productionPrefix = text(cdc.get("tablePrefix"));
            String productionDatabase = text(cdc.get("targetDatabase"));
            prefix = !productionPrefix.isEmpty() && !productionDatabase.isEmpty()
                    && productionPrefix.startsWith(productionDatabase + "_")
                    ? debugDatabase + productionPrefix.substring(productionDatabase.length()) : productionPrefix;
        }
        List<String> targets = new ArrayList<>();
        for (Object table : objectList(cdc.get("selectedTables"))) {
            targets.add(prefix + text(table) + suffix);
        }
        cdc.put("targetDatabase", debugDatabase);
        if (!prefix.isEmpty()) cdc.put("tablePrefix", prefix);
        if (!targets.isEmpty()) { cdc.put("targetTableList", targets); if (targets.size() == 1) cdc.put("targetTable", targets.get(0)); }
        config.put("cdcConfig", cdc); task.put("targetDatabase", debugDatabase); task.put("taskConfig", config);
    }

    private StoredSpec store(SubmissionSpec spec) {
        try {
            byte[] bytes = mapper.writeValueAsBytes(spec);
            String hash = sha256(bytes);
            Path dir = Path.of(properties.getSubmissionDir(), "tasks", String.valueOf(spec.getTaskId()),
                    "instances", String.valueOf(spec.getTaskInstanceId()));
            Files.createDirectories(dir);
            Path file = dir.resolve("job-config.json");
            Files.write(file, bytes);
            try { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")); }
            catch (UnsupportedOperationException ignored) { }
            String prefix = text(properties.getSubmissionUriPrefix());
            if (prefix.isEmpty()) return new StoredSpec(file.toUri().toString(), hash);
            String uri = prefix.replaceAll("/+$", "") + "/tasks/" + spec.getTaskId() + "/instances/"
                    + spec.getTaskInstanceId() + "/job-config.json";
            String parent = uri.substring(0, uri.lastIndexOf('/'));
            CommandResult mkdir = execute(List.of("hdfs", "dfs", "-mkdir", "-p", parent), 60);
            if (mkdir.exitCode != 0) throw new IllegalStateException("创建 HDFS 提交目录失败：" + mkdir.output);
            CommandResult put = execute(List.of("hdfs", "dfs", "-put", "-f", file.toString(), uri), 60);
            if (put.exitCode != 0) throw new IllegalStateException("上传 SubmissionSpec 失败：" + put.output);
            CommandResult chmod = execute(List.of("hdfs", "dfs", "-chmod", "600", uri), 60);
            if (chmod.exitCode != 0) throw new IllegalStateException("设置 SubmissionSpec 权限失败：" + chmod.output);
            return new StoredSpec(uri, hash);
        } catch (Exception ex) {
            throw ex instanceof RuntimeException ? (RuntimeException) ex : new IllegalStateException("保存 SubmissionSpec 失败", ex);
        }
    }

    private List<String> flinkCommand(SubmissionSpec spec, StoredSpec stored, boolean dryRun) {
        List<String> command = new ArrayList<>();
        command.add(properties.getFlinkBin()); command.add("run"); command.add("-t");
        command.add("yarn-application"); command.add("-d");
        Map<String, Object> config = spec.getTask().getTaskConfig();
        addFlinkArg(command, "pipeline.name", pipelineName(spec));
        addFlinkArg(command, "yarn.application.name", pipelineName(spec));
        addFlinkArg(command, "parallelism.default", config.get("parallelism"));
        addFlinkArg(command, "taskmanager.memory.process.size", config.get("taskManagerMemory"));
        addFlinkArg(command, "jobmanager.memory.process.size", config.get("jobManagerMemory"));
        addFlinkArg(command, "execution.checkpointing.interval", seconds(text(config.get("checkpointInterval"), "60")));
        if (!text(properties.getCheckpointDir()).isEmpty()) {
            addFlinkArg(command, "execution.checkpointing.storage", "filesystem");
            addFlinkArg(command, "execution.checkpointing.externalized-checkpoint-retention", "RETAIN_ON_CANCELLATION");
            addFlinkArg(command, "execution.checkpointing.dir", checkpointPath(spec));
        }
        objectMap(config.get("flinkConfOverrides")).forEach((key, value) -> {
            if (key.matches("[A-Za-z0-9._-]+") && value != null) addFlinkArg(command, key, value);
        });
        if (!"direct".equalsIgnoreCase(spec.getStartType())) {
            command.add("--fromSavepoint"); command.add(spec.getStatePath());
        }
        command.add("-c"); command.add("com.yjn.sqlagent.realtime.submit.TaskSubmitMain");
        command.add(properties.getSubmitJar()); command.add("--submission-file"); command.add(stored.uri);
        command.add("--config-sha256"); command.add(stored.sha256);
        if (dryRun) command.add("--dry-run");
        return command;
    }

    private String pipelineName(SubmissionSpec spec) {
        String name = text(spec.getJobName());
        String instanceId = spec.getTaskInstanceId() == null ? "<task-instance-id>" : String.valueOf(spec.getTaskInstanceId());
        String taskId = spec.getTaskId() == null || spec.getTaskId() <= 0 ? "<task-id>" : String.valueOf(spec.getTaskId());
        if ("DEBUG".equalsIgnoreCase(spec.getExecutionMode())) {
            return name + "-debug-" + instanceId;
        }
        return "sync-task-" + taskId + "-inst-" + instanceId
                + (name.isEmpty() ? "" : "-" + name);
    }

    private String checkpointPath(SubmissionSpec spec) {
        String root = properties.getCheckpointDir().replaceAll("/+$", "");
        String taskId = spec.getTaskId() == null || spec.getTaskId() <= 0 ? "<task-id>" : String.valueOf(spec.getTaskId());
        String instanceId = spec.getTaskInstanceId() == null ? "<task-instance-id>" : String.valueOf(spec.getTaskInstanceId());
        if ("DEBUG".equalsIgnoreCase(spec.getExecutionMode())) {
            return root + "/debug/task-" + taskId + "/" + instanceId;
        }
        return root + "/task-" + taskId;
    }

    private String previewSubmissionFile(SubmissionSpec spec) {
        String taskId = spec.getTaskId() == null || spec.getTaskId() <= 0
                ? "<task-id>" : String.valueOf(spec.getTaskId());
        String relative = "/tasks/" + taskId + "/instances/<task-instance-id>/job-config.json";
        String prefix = text(properties.getSubmissionUriPrefix());
        if (!prefix.isEmpty()) return prefix.replaceAll("/+$", "") + relative;
        return Path.of(properties.getSubmissionDir(), "tasks", taskId, "instances",
                "<task-instance-id>", "job-config.json").toString();
    }

    private List<String> stopCommand(long taskId, String jobId, String applicationId, String stopType) {
        if (jobId.isEmpty() && applicationId.isEmpty()) throw new IllegalStateException("实例缺少 JobID 和 ApplicationID");
        if (!jobId.isEmpty()) {
            List<String> command = new ArrayList<>(); command.add(properties.getFlinkBin());
            command.add("savepoint".equalsIgnoreCase(stopType) ? "stop" : "cancel");
            if (!applicationId.isEmpty()) command.add("-Dyarn.application.id=" + applicationId);
            if ("savepoint".equalsIgnoreCase(stopType) && !text(properties.getSavepointDir()).isEmpty()) {
                command.add("--savepointPath");
                command.add(properties.getSavepointDir().replaceAll("/+$", "") + "/task-" + taskId);
            }
            command.add(jobId); return command;
        }
        return List.of(properties.getYarnBin(), "application", "-kill", applicationId);
    }

    private Object fetch(Map<String, Object> instance, String path) {
        String value = fetchText(instance, path);
        try { return mapper.readValue(value, Object.class); }
        catch (Exception ex) { return Map.of("raw", value); }
    }
    private String fetchText(Map<String, Object> instance, String path) {
        String base = text(instance.get("trackingUrl"));
        if (base.isEmpty()) throw new IllegalStateException("实例缺少 Tracking URL");
        return fetchText(base, path);
    }

    // 包可见作为只读 REST 观测的测试接缝，生产调用仍统一经过本方法。
    String fetchText(String base, String path) {
        try {
            URI uri = URI.create(base.replaceAll("/+$", "") + path);
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) throw new IllegalStateException("Flink REST 返回 HTTP " + response.statusCode());
            return response.body();
        } catch (Exception ex) {
            throw ex instanceof RuntimeException ? (RuntimeException) ex : new IllegalStateException("读取运行信息失败", ex);
        }
    }

    private Map<String, Object> yarnResources(Map<String, Object> instance) {
        Map<String, Object> result = new LinkedHashMap<>();
        String applicationId = text(instance.get("yarnApplicationId"));
        String yarnWebUrl = text(properties.getYarnWebUrl());
        if (applicationId.isEmpty() || yarnWebUrl.isEmpty()) return result;
        result.put("applicationId", applicationId);
        try {
            Map<String, Object> root = objectMap(mapper.readValue(
                    fetchText(yarnWebUrl, "/ws/v1/cluster/apps/" + encode(applicationId)), Object.class));
            Map<String, Object> app = objectMap(root.get("app"));
            result.put("state", app.get("state"));
            result.put("runningContainers", app.get("runningContainers"));
            result.put("allocatedMemoryMb", app.get("allocatedMB"));
            result.put("allocatedVCores", app.get("allocatedVCores"));
        } catch (Exception ex) {
            result.put("unavailableReason", safe(ex));
        }
        return result;
    }

    private Map<String, Object> runtimeBase(Map<String, Object> instance) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instanceId", instance.get("id")); result.put("jobId", instance.get("jobId"));
        result.put("updatedAt", Instant.now().toString());
        return result;
    }

    private Map<String, Object> vertexMetrics(Map<String, Object> instance, String jobId,
            Map<String, Object> vertex, String role) {
        String id = text(vertex.get("id"));
        String path = "/jobs/" + jobId + "/vertices/" + id + "/subtasks/metrics";
        List<Object> catalog = objectList(fetch(instance, path));
        List<String> selected = new ArrayList<>();
        for (Object value : catalog) {
            String metricId = text(objectMap(value).get("id"));
            if (runtimeMetric(metricId)) selected.add(metricId);
        }
        Map<String, Map<String, Double>> metrics = new LinkedHashMap<>();
        for (int start = 0; start < selected.size(); start += 40) {
            List<String> batch = selected.subList(start, Math.min(selected.size(), start + 40));
            for (Object value : objectList(fetch(instance, path + "?get=" + String.join(",", batch) + "&agg=sum,avg,max"))) {
                Map<String, Object> item = objectMap(value);
                Map<String, Double> aggregate = new LinkedHashMap<>();
                aggregate.put("sum", decimal(item.get("sum"))); aggregate.put("avg", decimal(item.get("avg")));
                aggregate.put("max", decimal(item.get("max"))); metrics.put(text(item.get("id")), aggregate);
            }
        }
        Map<String, Object> row = vertexRow(vertex, role);
        row.put("inputRate", aggregateExact(metrics, "numRecordsInPerSecond", "sum"));
        row.put("outputRate", aggregateExact(metrics, "numRecordsOutPerSecond", "sum"));
        row.put("commitRate", aggregateSuffix(metrics, "sink.numRecordsOutPerSecond", "sum", false));
        row.put("committedRecords", aggregateSuffix(metrics, "sink.numRecordsOut", "sum", false));
        row.put("lastCommitDurationMs", aggregateSuffix(metrics, "commit.lastCommitDuration", "max", true));
        row.put("lastCommitAttempts", aggregateSuffix(metrics, "commit.lastCommitAttempts", "max", true));
        row.put("busyMaxMsPerSecond", aggregateExact(metrics, "busyTimeMsPerSecond", "max"));
        row.put("backpressuredMaxMsPerSecond", aggregateExact(metrics, "backPressuredTimeMsPerSecond", "max"));
        return row;
    }

    private Map<String, Object> vertexRow(Map<String, Object> vertex, String role) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", vertex.get("id")); row.put("name", vertex.get("name"));
        row.put("status", vertex.get("status")); row.put("parallelism", vertex.get("parallelism")); row.put("role", role);
        return row;
    }

    private Map<String, Object> syncSummary(List<Map<String, Object>> vertices, Topology topology) {
        List<Map<String, Object>> sources = new ArrayList<>(); List<Map<String, Object>> sinks = new ArrayList<>();
        for (Map<String, Object> row : vertices) {
            String role = text(row.get("role"));
            if ("source".equals(role) || "source_sink".equals(role)) sources.add(row);
            if ("sink".equals(role) || "source_sink".equals(role)) sinks.add(row);
        }
        Map<String, String> reasons = new LinkedHashMap<>(); Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceOutputRate", completeSum(sources, "outputRate", "sourceOutputRate",
                topology.sources.isEmpty() ? "Flink Job Plan 未返回可识别的 Source 节点" : "Flink 未提供 Source 输出速率", reasons));
        result.put("sinkInputRate", completeSum(sinks, "inputRate", "sinkInputRate",
                topology.sinks.isEmpty() ? "Flink Job Plan 未返回可识别的 Sink 节点" : "Flink 未提供 Sink 输入速率", reasons));
        result.put("committedRate", optionalAggregate(sinks, "commitRate", false, "committedRate", "Paimon 未提供提交速率", reasons));
        result.put("committedRecords", optionalAggregate(sinks, "committedRecords", false, "committedRecords", "Paimon 未提供累计提交记录", reasons));
        result.put("lastCommitDurationMs", optionalAggregate(sinks, "lastCommitDurationMs", true, "lastCommitDurationMs", "Paimon 未提供最近提交耗时", reasons));
        result.put("lastCommitAttempts", optionalAggregate(sinks, "lastCommitAttempts", true, "lastCommitAttempts", "Paimon 未提供提交尝试次数", reasons));
        result.put("busyMaxMsPerSecond", optionalAggregate(vertices, "busyMaxMsPerSecond", true, "busyMaxMsPerSecond", "Flink 未提供 Busy 指标", reasons));
        result.put("backpressuredMaxMsPerSecond", optionalAggregate(vertices, "backpressuredMaxMsPerSecond", true, "backpressuredMaxMsPerSecond", "Flink 未提供反压指标", reasons));
        result.put("unavailableReasons", reasons); return result;
    }

    private Double completeSum(List<Map<String, Object>> rows, String field, String key, String reason,
            Map<String, String> reasons) {
        if (rows.isEmpty()) { reasons.put(key, reason); return null; }
        double total = 0D;
        for (Map<String, Object> row : rows) {
            Double value = decimal(row.get(field));
            if (value == null || row.containsKey("unavailableReason")) { reasons.put(key, text(row.get("unavailableReason"), reason)); return null; }
            total += value;
        }
        return total;
    }

    private Double optionalAggregate(List<Map<String, Object>> rows, String field, boolean maximum,
            String key, String reason, Map<String, String> reasons) {
        Double result = null;
        for (Map<String, Object> row : rows) {
            if (row.containsKey("unavailableReason")) { reasons.put(key, text(row.get("unavailableReason"), reason)); return null; }
            Double value = decimal(row.get(field));
            if (value != null) result = result == null ? value : maximum ? Math.max(result, value) : result + value;
        }
        if (result == null) reasons.put(key, reason);
        return result;
    }

    private boolean runtimeMetric(String id) {
        return id.endsWith("numRecordsInPerSecond") || id.endsWith("numRecordsOutPerSecond")
                || id.endsWith("busyTimeMsPerSecond") || id.endsWith("backPressuredTimeMsPerSecond")
                || id.endsWith("sink.numRecordsOut") || id.endsWith("sink.numRecordsOutPerSecond")
                || id.endsWith("commit.lastCommitDuration") || id.endsWith("commit.lastCommitAttempts");
    }

    private Double aggregateExact(Map<String, Map<String, Double>> values, String id, String aggregate) {
        Map<String, Double> exact = values.get(id);
        if (exact != null) return exact.get(aggregate);
        for (Map.Entry<String, Map<String, Double>> entry : values.entrySet()) {
            if (entry.getKey().endsWith(id)) return entry.getValue().get(aggregate);
        }
        return null;
    }

    private Double aggregateSuffix(Map<String, Map<String, Double>> values, String suffix,
            String aggregate, boolean maximum) {
        Double result = null;
        for (Map.Entry<String, Map<String, Double>> entry : values.entrySet()) {
            Double value = entry.getValue().get(aggregate);
            if (entry.getKey().endsWith(suffix) && value != null) result = result == null ? value
                    : maximum ? Math.max(result, value) : result + value;
        }
        return result;
    }

    private Map<String, String> metricValues(Map<String, Object> instance, String path, String... suffixes) {
        List<Object> catalog = objectList(fetch(instance, path)); List<String> selected = new ArrayList<>();
        for (Object value : catalog) {
            String id = text(objectMap(value).get("id"));
            for (String suffix : suffixes) if (id.endsWith(suffix)) { selected.add(id); break; }
        }
        Map<String, String> result = new LinkedHashMap<>();
        if (selected.isEmpty()) return result;
        for (Object value : objectList(fetch(instance, path + "?get=" + String.join(",", selected)))) {
            Map<String, Object> row = objectMap(value); result.put(text(row.get("id")), text(row.get("value")));
        }
        return result;
    }

    private Double metricNumber(Map<String, String> values, String suffix) {
        for (Map.Entry<String, String> entry : values.entrySet()) if (entry.getKey().endsWith(suffix)) return decimal(entry.getValue());
        return null;
    }

    private Topology topology(Map<String, Object> job) {
        Set<String> nodes = new LinkedHashSet<>(); Set<String> sources = new LinkedHashSet<>(); Set<String> consumed = new LinkedHashSet<>();
        for (Object value : objectList(objectMap(job.get("plan")).get("nodes"))) {
            Map<String, Object> node = objectMap(value); String id = text(node.get("id")); if (id.isEmpty()) continue;
            nodes.add(id); List<Object> inputs = objectList(node.get("inputs"));
            if (inputs.isEmpty()) sources.add(id);
            else for (Object input : inputs) { String inputId = text(objectMap(input).get("id")); if (!inputId.isEmpty()) consumed.add(inputId); }
        }
        Set<String> sinks = new LinkedHashSet<>(nodes); sinks.removeAll(consumed); return new Topology(sources, sinks);
    }

    private Object jsonValue(Object value) {
        if (value == null || value instanceof Map || value instanceof List) return value;
        try { return mapper.readValue(String.valueOf(value), Object.class); } catch (Exception ignored) { return value; }
    }

    @SuppressWarnings("unchecked")
    private List<Object> objectList(Object value) { return value instanceof List ? (List<Object>) value : new ArrayList<>(); }
    private int integer(Object value, int fallback) {
        if (value instanceof Number) return ((Number) value).intValue();
        try { return Integer.parseInt(text(value)); } catch (NumberFormatException ignored) { return fallback; }
    }
    private Double decimal(Object value) {
        if (value instanceof Number) { double number = ((Number) value).doubleValue(); return Double.isFinite(number) ? number : null; }
        try { double number = Double.parseDouble(text(value)); return Double.isFinite(number) ? number : null; }
        catch (NumberFormatException ignored) { return null; }
    }

    // 包可见作为 Flink/YARN 异常分支的测试接缝，生产调用仍统一经过本方法。
    CommandResult execute(List<String> command, long timeoutSeconds) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command); builder.redirectErrorStream(true);
            applyHadoopClasspath(builder, command);
            process = builder.start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Process running = process;
            Thread reader = new Thread(() -> {
                try (InputStream input = running.getInputStream()) { input.transferTo(output); }
                catch (Exception ignored) { }
            }, "realtime-command-reader");
            reader.setDaemon(true); reader.start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly(); throw new IllegalStateException("命令执行超时");
            }
            reader.join(2000);
            return new CommandResult(process.exitValue(), output.toString(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            if (process != null) process.destroyForcibly();
            throw ex instanceof RuntimeException ? (RuntimeException) ex : new IllegalStateException("执行 Flink/YARN 命令失败", ex);
        }
    }

    private void applyHadoopClasspath(ProcessBuilder builder, List<String> command) {
        if (command.isEmpty() || !properties.getFlinkBin().equals(command.get(0))) return;
        String classpath = text(properties.getHadoopClasspath());
        if (classpath.isEmpty()) classpath = loadHadoopClasspath();
        builder.environment().put("HADOOP_CLASSPATH", classpath);
    }

    private String loadHadoopClasspath() {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(properties.getHadoopBin(), "classpath");
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = builder.start();
            byte[] output = process.getInputStream().readAllBytes();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("获取 HADOOP_CLASSPATH 超时");
            }
            String classpath = new String(output, StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0 || classpath.isEmpty()) {
                throw new IllegalStateException("获取 HADOOP_CLASSPATH 失败：" + tail(classpath, 2000));
            }
            return classpath;
        } catch (Exception ex) {
            if (process != null) process.destroyForcibly();
            throw ex instanceof RuntimeException ? (RuntimeException) ex
                    : new IllegalStateException("获取 HADOOP_CLASSPATH 失败", ex);
        }
    }

    private String yarnTrackingUrl(String applicationId) {
        CommandResult result = execute(List.of(properties.getYarnBin(), "application", "-status", applicationId), 30);
        return usableTrackingUrl(match(TRACKING_URL, result.output, 1));
    }
    private String discoverJobId(String trackingUrl, long timeoutSeconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            try {
                URI uri = URI.create(trackingUrl.replaceAll("/+$", "") + "/jobs/overview");
                HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build();
                HttpResponse<String> response = http.send(request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() < 400) {
                    Map<String, Object> body = mapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
                    Object jobs = body.get("jobs");
                    if (jobs instanceof List && !((List<?>) jobs).isEmpty()) {
                        Object first = ((List<?>) jobs).get(0);
                        if (first instanceof Map) {
                            String jobId = text(((Map<?, ?>) first).get("jid"));
                            if (jobId.isEmpty()) jobId = text(((Map<?, ?>) first).get("id"));
                            if (!jobId.isEmpty()) return jobId;
                        }
                    }
                }
            } catch (Exception ignored) { }
            try { Thread.sleep(1000L); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); return ""; }
        }
        return "";
    }
    private CommandResult awaitYarnTerminal(String applicationId, long timeoutSeconds) {
        if (applicationId.isEmpty()) throw new IllegalStateException("DEBUG Dry Run 未返回 YARN ApplicationID");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        CommandResult latest = new CommandResult(-1, "");
        while (System.nanoTime() < deadline) {
            latest = execute(List.of(properties.getYarnBin(), "application", "-status", applicationId), 30);
            String status = yarnStatus(latest.output);
            if (isTerminal(status)) return latest;
            try { Thread.sleep(2000L); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException("等待 YARN 状态被中断", ex); }
        }
        throw new IllegalStateException("等待 DEBUG Dry Run 结束超时：" + tail(latest.output, 4000));
    }

    // 包可见用于验证启动前的 YARN 双跑检测；只查询，不改变外部作业状态。
    List<String> liveSyncApplications(long taskId) {
        CommandResult result;
        try {
            result = execute(List.of(properties.getYarnBin(), "application", "-list", "-appStates",
                    "NEW,NEW_SAVING,SUBMITTED,ACCEPTED,RUNNING"), 30);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("无法确认旧实例状态，请稍后重试或检查 YARN/Flink", ex);
        }
        if (result.exitCode != 0) {
            throw new IllegalStateException("无法确认旧实例状态，请稍后重试或检查 YARN/Flink："
                    + tail(mask(result.output), 2000));
        }
        String prefix = "sync-task-" + taskId + "-inst-";
        Set<String> ids = new LinkedHashSet<>();
        for (String line : result.output.split("\\R")) {
            if (!line.contains(prefix)) continue;
            String applicationId = match(APPLICATION_ID, line, 0);
            if (!applicationId.isEmpty()) ids.add(applicationId);
        }
        return new ArrayList<>(ids);
    }
    private String yarnStatus(String output) {
        String upper = output.toUpperCase(Locale.ROOT);
        if (upper.contains("FINAL-STATE") && upper.contains("SUCCEEDED")) return "finished";
        if (upper.contains("FINAL-STATE") && upper.contains("FAILED")) return "failed";
        if (upper.contains("FINAL-STATE") && upper.contains("KILLED")) return "canceled";
        if (upper.matches("(?s).*STATE\\s*:\\s*RUNNING.*")) return "running";
        if (upper.matches("(?s).*STATE\\s*:\\s*(ACCEPTED|SUBMITTED|NEW).*")) return "submitting";
        return "unknown";
    }
    private String flinkStatus(String state) {
        switch (state.toUpperCase(Locale.ROOT)) {
            case "RUNNING": return "running";
            case "INITIALIZING": case "CREATED": return "submitting";
            case "RESTARTING": case "RECONCILING": return "restarting";
            case "CANCELED": case "CANCELLING": return "canceled";
            case "FINISHED": return "finished";
            case "FAILED": return "failed";
            default: return "unknown";
        }
    }
    private boolean isTerminal(String status) { return List.of("failed", "canceled", "finished", "killed_success").contains(status); }
    private void validateStart(TaskActionRequest action) {
        String start = text(action.getStartType(), "direct").toLowerCase(Locale.ROOT);
        if (!List.of("direct", "checkpoint", "savepoint").contains(start)) throw new IllegalArgumentException("启动类型不正确");
        if (!"direct".equals(start) && text(action.getStatePath()).isEmpty()) throw new IllegalArgumentException("恢复启动必须选择状态路径");
        if (action.getParallelism() != null && (action.getParallelism() < 1 || action.getParallelism() > 128)) {
            throw new IllegalArgumentException("任务并行度必须为 1 到 128 的整数");
        }
        if (action.getCheckpointInterval() != null
                && (action.getCheckpointInterval() < 10 || action.getCheckpointInterval() > 600)) {
            throw new IllegalArgumentException("Checkpoint 间隔必须为 10 到 600 秒的整数");
        }
    }
    private void validateStatePath(long taskId, TaskActionRequest action) {
        String start = text(action.getStartType(), "direct").toLowerCase(Locale.ROOT);
        if ("direct".equals(start)) return;
        String statePath = text(action.getStatePath());
        if (!stateHistoryReader.exists(taskId, start, statePath)) {
            throw new IllegalArgumentException("所选 " + start + " 已不存在或不完整，请刷新历史状态后重新选择：" + statePath);
        }
    }
    private void validateRequiredRecovery(long taskId, TaskActionRequest action) {
        Map<String, Object> policy = repository.editPolicy(taskId);
        if (!Boolean.TRUE.equals(policy.get("syncTableSetChanged"))) return;
        String requiredPath = text(policy.get("requiredStatePath"));
        if (requiredPath.isEmpty()) {
            throw new IllegalStateException("同步表集合已变化，但没有可用的正式 Savepoint，请先恢复原配置运行并通过 Savepoint 停止");
        }
        if (!"savepoint".equalsIgnoreCase(text(action.getStartType()))) {
            throw new IllegalArgumentException("增删同步表后必须从最近一次正式停止产生的 Savepoint 启动");
        }
        if (!requiredPath.equals(text(action.getStatePath()))) {
            throw new IllegalArgumentException("增删同步表后只能使用最近一次正式停止产生的 Savepoint：" + requiredPath);
        }
    }
    private void addFlinkArg(List<String> command, String key, Object value) {
        if (!text(value).isEmpty()) command.add("-D" + key + "=" + value);
    }
    private Object seconds(Object value) { return text(value).isEmpty() ? null : text(value) + "s"; }
    private String requiredJobId(Map<String, Object> instance) {
        String jobId = text(instance.get("jobId"));
        if (jobId.isEmpty()) throw new IllegalStateException("实例缺少 JobID");
        return jobId;
    }
    private String parseSavepoint(String output) {
        Matcher matcher = Pattern.compile("((?:hdfs|file|s3)://\\S+)").matcher(output == null ? "" : output);
        return matcher.find() ? matcher.group(1) : "";
    }
    private String match(Pattern pattern, String value, int group) {
        Matcher matcher = pattern.matcher(value == null ? "" : value);
        return matcher.find() ? matcher.group(group) : "";
    }
    private String shell(List<String> command) {
        StringBuilder result = new StringBuilder();
        for (String part : command) {
            if (result.length() > 0) result.append(' ');
            result.append(part.matches("[A-Za-z0-9_./:=@+-]+") ? part : "'" + part.replace("'", "'\\''") + "'");
        }
        return result.toString();
    }
    private String mask(String value) { return value == null ? "" : value.replaceAll("(?i)(password=)[^\\s]+", "$1******"); }
    private String safe(Throwable value) { return mask(value.getMessage() == null ? value.getClass().getSimpleName() : value.getMessage()); }
    private String tail(String value, int max) { return value == null || value.length() <= max ? text(value) : value.substring(value.length() - max); }
    private String append(String left, String right) { return left.isEmpty() ? right : left + "\n" + right; }
    private String encode(String value) { return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private String empty(String value) { return value.isEmpty() ? null : value; }
    private String usableTrackingUrl(String value) {
        String url = text(value);
        return "N/A".equalsIgnoreCase(url) ? "" : url;
    }
    private long number(Object value) { return value instanceof Number ? ((Number) value).longValue() : Long.parseLong(text(value, "0")); }
    private Long nullableNumber(Object value) {
        if (value == null || text(value).isEmpty()) return null;
        return value instanceof Number ? ((Number) value).longValue() : Long.parseLong(text(value));
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); } catch (Exception ex) { throw new IllegalStateException("JSON 序列化失败", ex); }
    }
    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) { return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>(); }
    private String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value); StringBuilder result = new StringBuilder();
            for (byte item : digest) result.append(String.format("%02x", item)); return result.toString();
        } catch (Exception ex) { throw new IllegalStateException("SHA-256 计算失败", ex); }
    }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private String text(Object value, String fallback) { return text(value).isEmpty() ? fallback : text(value); }
    private String memory(Object value) {
        if (value == null) return null;
        String raw = String.valueOf(value).trim().replaceAll("(?i)gb?$", "");
        try {
            java.math.BigDecimal gb = new java.math.BigDecimal(raw).stripTrailingZeros();
            if (gb.scale() <= 0) return gb.toPlainString() + "GB";
            return gb.multiply(java.math.BigDecimal.valueOf(1024)).setScale(0,
                    java.math.RoundingMode.HALF_UP).toPlainString() + "MB";
        } catch (NumberFormatException ignored) {
            return raw + "GB";
        }
    }
    private String taskLabel(Map<String, Object> task) {
        String type = text(task.get("taskType"), "sync");
        if ("compute".equalsIgnoreCase(type)) return "实时计算任务";
        if ("export".equalsIgnoreCase(type)) return "实时出仓任务";
        return "实时同步任务";
    }

    private static final class StoredSpec {
        private final String uri; private final String sha256;
        private StoredSpec(String uri, String sha256) { this.uri = uri; this.sha256 = sha256; }
    }
    static final class CommandResult {
        private final int exitCode; private final String output;
        CommandResult(int exitCode, String output) { this.exitCode = exitCode; this.output = output; }
    }
    private static final class ObservedStatus {
        private final String status; private final String output; private final String jobId;
        private final String applicationId; private final String trackingUrl;
        private ObservedStatus(String status, String output, String jobId,
                String applicationId, String trackingUrl) {
            this.status = status; this.output = output; this.jobId = jobId;
            this.applicationId = applicationId; this.trackingUrl = trackingUrl;
        }
    }
    private static final class Topology {
        private final Set<String> sources; private final Set<String> sinks;
        private Topology(Set<String> sources, Set<String> sinks) { this.sources = sources; this.sinks = sinks; }
        private String role(String id) {
            boolean source = sources.contains(id); boolean sink = sinks.contains(id);
            if (source && sink) return "source_sink";
            if (source) return "source";
            if (sink) return "sink";
            return "operator";
        }
    }
}
