package com.yjn.sqlagent.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.yjn.sqlagent.config.TaskExecutionLogProperties;
import com.yjn.sqlagent.exception.BusinessException;
import com.yjn.sqlagent.model.vo.ExecutionLogChunkVO;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskExecutionLogServiceImplTest {

    @TempDir
    Path tempDir;

    @Test
    void readsLogIncrementally() throws Exception {
        Files.write(tempDir.resolve("12.log"), "abcdef".getBytes(StandardCharsets.UTF_8));
        TaskExecutionLogServiceImpl service = service();

        ExecutionLogChunkVO first = service.read(12, 0, 3);
        ExecutionLogChunkVO second = service.read(12, first.getNextOffset(), 3);

        assertEquals("abc", first.getContent());
        assertEquals(3L, first.getNextOffset());
        assertEquals("def", second.getContent());
        assertEquals(true, second.isEof());
    }

    @Test
    void rejectsInvalidExecutionIdAndReadLimit() {
        TaskExecutionLogServiceImpl service = service();

        assertThrows(BusinessException.class, () -> service.read(0, 0, 1024));
        assertThrows(BusinessException.class, () -> service.read(1, -1, 1024));
        assertThrows(BusinessException.class, () -> service.read(1, 0, 262145));
    }

    @Test
    void readsStepLogFromExecutionDirectory() throws Exception {
        Path stepDir = Files.createDirectories(tempDir.resolve("12"));
        Files.write(stepDir.resolve("step-0.log"), "step log".getBytes(StandardCharsets.UTF_8));
        TaskExecutionLogServiceImpl service = service();

        ExecutionLogChunkVO result = service.readStep(12, 0, 0, 1024);

        assertEquals("step log", result.getContent());
        assertThrows(BusinessException.class, () -> service.readStep(12, -1, 0, 1024));
    }

    private TaskExecutionLogServiceImpl service() {
        TaskExecutionLogProperties properties = new TaskExecutionLogProperties();
        properties.setDir(tempDir.toString());
        return new TaskExecutionLogServiceImpl(properties);
    }
}
