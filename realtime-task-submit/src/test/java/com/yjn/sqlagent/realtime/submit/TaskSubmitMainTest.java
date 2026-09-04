package com.yjn.sqlagent.realtime.submit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjn.sqlagent.realtime.common.SubmissionSpec;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskSubmitMainTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsUnknownArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> TaskSubmitMain.Arguments.parse(new String[] {"--conf", "x"}));
    }

    @Test
    void requiresSubmissionFile() {
        assertThrows(IllegalArgumentException.class,
                () -> TaskSubmitMain.Arguments.parse(new String[] {"--dry-run"}));
    }

    @Test
    void dryRunValidatesSnapshotAndNeverPrintsPassword() throws Exception {
        byte[] bytes = new ObjectMapper().writeValueAsBytes(validSpec());
        Path file = tempDir.resolve("job-config.json");
        Files.write(file, bytes);
        ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();

        TaskSubmitMain.run(new String[] {"--submission-file", file.toUri().toString(),
                "--config-sha256", sha256(bytes), "--dry-run"},
                new PrintStream(bytesOut, true, StandardCharsets.UTF_8));

        String output = bytesOut.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("mysql_sync_database"));
        assertTrue(output.contains("password=******"));
        assertFalse(output.contains("不能泄露的密码"));
    }

    @Test
    void rejectsChangedSnapshotAndUnimplementedTaskType() throws Exception {
        SubmissionSpec spec = validSpec();
        byte[] bytes = new ObjectMapper().writeValueAsBytes(spec);
        Path file = tempDir.resolve("job-config.json");
        Files.write(file, bytes);
        assertThrows(IllegalArgumentException.class, () -> TaskSubmitMain.run(new String[] {
                "--submission-file", file.toUri().toString(), "--config-sha256", "0".repeat(64), "--dry-run"
        }, System.out));

        spec.getTask().setTaskType("compute");
        Files.write(file, new ObjectMapper().writeValueAsBytes(spec));
        assertThrows(IllegalArgumentException.class, () -> TaskSubmitMain.run(new String[] {
                "--submission-file", file.toUri().toString(), "--dry-run"
        }, System.out));
    }

    private SubmissionSpec validSpec() {
        SubmissionSpec spec = new SubmissionSpec();
        spec.setTaskId(1L); spec.setTaskInstanceId(2L);
        SubmissionSpec.TaskSpec task = new SubmissionSpec.TaskSpec();
        task.setTaskConfig(Map.of("sourceServerId", 3L, "cdcConfig", Map.of(
                "targetDatabase", "ods", "domainPrefix", "trade", "selectedTables", List.of("orders"))));
        spec.setTask(task);
        SubmissionSpec.RuntimeConfig runtime = new SubmissionSpec.RuntimeConfig();
        runtime.setPaimonActionJarPath("/data/action.jar"); runtime.setPaimonWarehouse("hdfs:///warehouse");
        spec.setRuntimeConfig(runtime);
        SubmissionSpec.ServerSnapshot server = new SubmissionSpec.ServerSnapshot();
        server.setId(3L); server.setAddress("mysql:3306"); server.setDatabaseName("sales");
        server.setDatabasePrefix("sale"); server.setAccount("cdc"); server.setPassword("不能泄露的密码");
        spec.setServers(List.of(server));
        return spec;
    }

    private String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder result = new StringBuilder();
        for (byte item : digest) result.append(String.format("%02x", item));
        return result.toString();
    }
}
