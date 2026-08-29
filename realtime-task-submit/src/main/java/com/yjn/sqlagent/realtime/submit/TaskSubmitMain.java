package com.yjn.sqlagent.realtime.submit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjn.sqlagent.realtime.common.PaimonSyncCommandBuilder;
import com.yjn.sqlagent.realtime.common.SubmissionSpec;
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
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.paimon.flink.action.Action;
import org.apache.paimon.flink.action.ActionFactory;

/** 独立同步任务提交入口；计算和出仓任务在首期明确拒绝。 */
public final class TaskSubmitMain {

    private TaskSubmitMain() {
    }

    public static void main(String[] args) throws Exception {
        run(args, System.out, true);
    }

    static void run(String[] args, PrintStream output) throws Exception {
        run(args, output, false);
    }

    private static void run(String[] args, PrintStream output, boolean executeDryRunJob) throws Exception {
        Arguments parsed = Arguments.parse(args);
        byte[] bytes = read(parsed.submissionFile);
        if (!parsed.configSha256.isEmpty() && !parsed.configSha256.equals(sha256(bytes))) {
            throw new IllegalArgumentException("SubmissionSpec SHA-256 校验失败");
        }
        SubmissionSpec spec = new ObjectMapper().readValue(bytes, SubmissionSpec.class);
        validate(spec);
        PaimonSyncCommandBuilder.Command command = new PaimonSyncCommandBuilder().build(spec);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("taskId", spec.getTaskId());
        snapshot.put("jobInstanceId", spec.getJobInstanceId());
        snapshot.put("startType", spec.getStartType());
        snapshot.put("jarPath", command.getJarPath());
        snapshot.put("args", command.maskedArguments());
        output.println(new ObjectMapper().writeValueAsString(snapshot));
        if (parsed.dryRun) {
            if (executeDryRunJob) runDryRunJob(spec);
            return;
        }
        Optional<Action> action = ActionFactory.createAction(command.getArguments().toArray(new String[0]));
        if (action.isEmpty()) throw new IllegalArgumentException("无法创建 Paimon mysql_sync_database Action");
        action.get().run();
    }

    private static void runDryRunJob(SubmissionSpec spec) throws Exception {
        StreamExecutionEnvironment environment = StreamExecutionEnvironment.getExecutionEnvironment();
        environment.setParallelism(1);
        environment.fromData("submission-spec-validated").print("realtime-sync-dry-run");
        environment.execute("realtime-sync-dry-run-" + spec.getTaskId() + "-" + spec.getJobInstanceId());
    }

    private static void validate(SubmissionSpec spec) {
        if (spec == null || spec.getTaskId() == null || spec.getJobInstanceId() == null) {
            throw new IllegalArgumentException("提交标识不能为空");
        }
        String taskType = spec.getTask() == null ? "" : spec.getTask().getTaskType();
        if (!"sync".equals(taskType)) {
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

        private Arguments(String submissionFile, String configSha256, boolean dryRun) {
            this.submissionFile = submissionFile;
            this.configSha256 = configSha256;
            this.dryRun = dryRun;
        }

        static Arguments parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            for (int index = 0; index < args.length; index++) {
                String name = args[index];
                if ("--dry-run".equals(name)) { values.put(name, "true"); continue; }
                if (!"--submission-file".equals(name) && !"--config-sha256".equals(name)) {
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
            return new Arguments(file, hash, Boolean.parseBoolean(values.getOrDefault("--dry-run", "false")));
        }
    }
}
