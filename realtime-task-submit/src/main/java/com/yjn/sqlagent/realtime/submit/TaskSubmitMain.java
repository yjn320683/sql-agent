package com.yjn.sqlagent.realtime.submit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjn.sqlagent.realtime.common.PaimonSyncCommandBuilder;
import com.yjn.sqlagent.realtime.common.SubmissionSpec;
import com.yjn.sqlagent.realtime.common.DebugReport;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.paimon.flink.action.Action;
import org.apache.paimon.flink.action.ActionFactory;

/** 实时同步、计算和出仓任务的统一提交入口。 */
public final class TaskSubmitMain {

    private TaskSubmitMain() {
    }

    public static void main(String[] args) throws Exception {
        run(args, System.out);
    }

    static void run(String[] args, PrintStream output) throws Exception {
        Arguments parsed = Arguments.parse(args);
        byte[] bytes = read(parsed.submissionFile);
        if (!parsed.configSha256.isEmpty() && !parsed.configSha256.equals(sha256(bytes))) {
            throw new IllegalArgumentException("SubmissionSpec SHA-256 校验失败");
        }
        SubmissionSpec spec = new ObjectMapper().readValue(bytes, SubmissionSpec.class);
        validate(spec);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("taskId", spec.getTaskId());
        snapshot.put("taskInstanceId", spec.getTaskInstanceId());
        snapshot.put("startType", spec.getStartType());
        snapshot.put("taskType", spec.getTask().getTaskType());
        if ("sync".equals(spec.getTask().getTaskType())) {
            PaimonSyncCommandBuilder.Command command = new PaimonSyncCommandBuilder().build(spec);
            snapshot.put("jarPath", command.getJarPath()); snapshot.put("args", command.maskedArguments());
        } else snapshot.put("runner", spec.getTask().getTaskType());
        output.println(new ObjectMapper().writeValueAsString(snapshot));
        TaskRunner runner = runner(spec);
        if (parsed.dryRun) {
            try {
                writeDebugReport(parsed, output, runner.validate(spec));
            } catch (Exception ex) {
                DebugReport failed = new DebugReport(); failed.setTaskType(spec.getTask().getTaskType());
                failed.setSummary(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
                failed.diagnostic("DEBUG_VALIDATION_FAILED", spec.getTask().getTaskType(), failed.getSummary());
                writeDebugReport(parsed, output, failed);
                throw ex;
            }
            return;
        }
        runner.execute(spec);
    }

    private static void writeDebugReport(Arguments parsed, PrintStream output, DebugReport report) throws Exception {
        String reportJson = new ObjectMapper().writeValueAsString(report);
        if (!parsed.debugReportFile.isEmpty()) {
            write(parsed.debugReportFile, reportJson.getBytes(StandardCharsets.UTF_8));
        }
        output.println("DEBUG_REPORT=" + reportJson);
    }

    private static void validate(SubmissionSpec spec) {
        if (spec == null || spec.getTaskId() == null || spec.getTaskInstanceId() == null) {
            throw new IllegalArgumentException("提交标识不能为空");
        }
        String taskType = spec.getTask() == null ? "" : spec.getTask().getTaskType();
        if (!java.util.List.of("sync", "compute", "export").contains(taskType)) {
            throw new IllegalArgumentException("任务类型暂未实现：" + taskType);
        }
        String startType = text(spec.getStartType()).toLowerCase(Locale.ROOT);
        if (!startType.equals("direct") && !startType.equals("checkpoint") && !startType.equals("savepoint")) {
            throw new IllegalArgumentException("不支持的启动方式：" + startType);
        }
        if (!"direct".equals(startType) && text(spec.getStatePath()).isEmpty()) {
            throw new IllegalArgumentException("恢复启动必须指定 statePath");
        }
    }

    private static TaskRunner runner(SubmissionSpec spec) {
        String type = spec.getTask().getTaskType();
        if ("compute".equals(type)) return new ComputeTaskRunner();
        if ("export".equals(type)) return new ExportTaskRunner();
        return new TaskRunner() {
            @Override public DebugReport validate(SubmissionSpec value) {
                new PaimonSyncCommandBuilder().build(value);
                DebugReport report = new DebugReport(); report.setTaskType("sync");
                report.setSummary("同步任务提交快照与 Paimon Action 参数校验通过");
                return report.check("SUBMISSION_SPEC", String.valueOf(value.getTaskId()), "提交快照可构建");
            }
            @Override public void execute(SubmissionSpec value) throws Exception {
                PaimonSyncCommandBuilder.Command command = new PaimonSyncCommandBuilder().build(value);
                Optional<Action> action = ActionFactory.createAction(command.getArguments().toArray(new String[0]));
                if (action.isEmpty()) throw new IllegalArgumentException("无法创建 Paimon mysql_sync_database Action");
                action.get().run();
            }
        };
    }

    private static byte[] read(String location) throws Exception {
        URI uri = URI.create(location);
        if (uri.getScheme() == null || "file".equalsIgnoreCase(uri.getScheme())) {
            Path path = uri.getScheme() == null ? Path.of(location) : Path.of(uri);
            return Files.readAllBytes(path);
        }
        Configuration configuration = new Configuration();
        org.apache.hadoop.fs.Path path = new org.apache.hadoop.fs.Path(uri);
        try (FileSystem fs = path.getFileSystem(configuration);
                InputStream input = fs.open(path);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            input.transferTo(output);
            return output.toByteArray();
        }
    }

    private static void write(String location, byte[] value) throws Exception {
        URI uri = URI.create(location);
        if (uri.getScheme() == null || "file".equalsIgnoreCase(uri.getScheme())) {
            Path path = uri.getScheme() == null ? Path.of(location) : Path.of(uri);
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Files.write(path, value); return;
        }
        Configuration configuration = new Configuration();
        org.apache.hadoop.fs.Path path = new org.apache.hadoop.fs.Path(uri);
        try (FileSystem fs = path.getFileSystem(configuration)) {
            if (path.getParent() != null) fs.mkdirs(path.getParent());
            try (java.io.OutputStream output = fs.create(path, true)) { output.write(value); }
        }
    }

    private static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder result = new StringBuilder();
        for (byte item : digest) result.append(String.format("%02x", item));
        return result.toString();
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }

    static final class Arguments {
        private final String submissionFile;
        private final String configSha256;
        private final boolean dryRun;
        private final String debugReportFile;

        private Arguments(String submissionFile, String configSha256, boolean dryRun, String debugReportFile) {
            this.submissionFile = submissionFile;
            this.configSha256 = configSha256;
            this.dryRun = dryRun;
            this.debugReportFile = debugReportFile;
        }

        static Arguments parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            for (int index = 0; index < args.length; index++) {
                String name = args[index];
                if ("--dry-run".equals(name)) { values.put(name, "true"); continue; }
                if (!"--submission-file".equals(name) && !"--config-sha256".equals(name)
                        && !"--debug-report-file".equals(name)) {
                    throw new IllegalArgumentException("未知参数：" + name);
                }
                if (index + 1 >= args.length) throw new IllegalArgumentException("参数缺少值：" + name);
                values.put(name, args[++index]);
            }
            String file = text(values.get("--submission-file"));
            if (file.isEmpty()) throw new IllegalArgumentException("--submission-file 不能为空");
            String hash = text(values.get("--config-sha256")).toLowerCase(Locale.ROOT);
            if (!hash.isEmpty() && !hash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("--config-sha256 格式不正确");
            }
            return new Arguments(file, hash, Boolean.parseBoolean(values.getOrDefault("--dry-run", "false")),
                    text(values.get("--debug-report-file")));
        }
    }
}
