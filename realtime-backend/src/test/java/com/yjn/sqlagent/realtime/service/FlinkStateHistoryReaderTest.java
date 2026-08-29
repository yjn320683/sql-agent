package com.yjn.sqlagent.realtime.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yjn.sqlagent.realtime.config.RealtimeProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FlinkStateHistoryReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void listsOnlyCompleteTaskScopedCheckpointAndSavepointDirectories() throws Exception {
        Path checkpoints = tempDir.resolve("checkpoints");
        Path savepoints = tempDir.resolve("savepoints");
        Path checkpoint = checkpoints.resolve("task-8/job/chk-12");
        Path incompleteCheckpoint = checkpoints.resolve("task-8/job/chk-13");
        Path otherTaskCheckpoint = checkpoints.resolve("task-9/job/chk-99");
        Path savepoint = savepoints.resolve("task-8/savepoint-a1");
        Files.createDirectories(checkpoint); Files.writeString(checkpoint.resolve("_metadata"), "checkpoint");
        Files.createDirectories(incompleteCheckpoint);
        Files.createDirectories(otherTaskCheckpoint); Files.writeString(otherTaskCheckpoint.resolve("_metadata"), "other");
        Files.createDirectories(savepoint); Files.writeString(savepoint.resolve("_metadata"), "savepoint");

        RealtimeProperties properties = new RealtimeProperties();
        properties.setCheckpointDir(checkpoints.toString());
        properties.setSavepointDir(savepoints.toString());
        FlinkStateHistoryReader reader = new FlinkStateHistoryReader(properties);

        assertEquals(1, reader.list(8L, "checkpoint").size());
        assertEquals(checkpoint.toString(), reader.list(8L, "checkpoint").get(0).get("path"));
        assertEquals(1, reader.list(8L, "savepoint").size());
        assertTrue(reader.exists(8L, "checkpoint", checkpoint.toString()));
        assertTrue(reader.exists(8L, "savepoint", savepoint.toString()));
        assertFalse(reader.exists(8L, "checkpoint", otherTaskCheckpoint.toString()));
        assertFalse(reader.exists(8L, "checkpoint", incompleteCheckpoint.toString()));
    }
}
