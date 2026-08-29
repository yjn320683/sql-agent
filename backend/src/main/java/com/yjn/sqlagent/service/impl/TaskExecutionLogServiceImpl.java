package com.yjn.sqlagent.service.impl;

import com.yjn.sqlagent.common.ErrorCode;
import com.yjn.sqlagent.config.TaskExecutionLogProperties;
import com.yjn.sqlagent.exception.BusinessException;
import com.yjn.sqlagent.model.vo.ExecutionLogChunkVO;
import com.yjn.sqlagent.service.TaskExecutionLogService;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.stereotype.Service;

@Service
public class TaskExecutionLogServiceImpl implements TaskExecutionLogService {

    private static final long MAX_LOG_BYTES = 50L * 1024 * 1024;
    private final Path root;

    public TaskExecutionLogServiceImpl(TaskExecutionLogProperties properties) {
        this.root = Paths.get(properties.getDir()).toAbsolutePath().normalize();
    }

    @Override
    public ExecutionLogChunkVO read(long executionId, long offset, int limit) {
        return readPath(resolve(executionId), offset, limit);
    }

    @Override
    public ExecutionLogChunkVO readStep(long executionId, int stepNo, long offset, int limit) {
        return readPath(resolveStep(executionId, stepNo), offset, limit);
    }

    private ExecutionLogChunkVO readPath(Path path, long offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 262144) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "日志 offset 或 limit 非法");
        }
        if (!Files.exists(path)) {
            return new ExecutionLogChunkVO("", 0, true, false);
        }
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            long length = file.length();
            long start = Math.min(offset, length);
            int size = (int) Math.min(limit, length - start);
            byte[] bytes = new byte[size];
            file.seek(start);
            file.readFully(bytes);
            long next = start + size;
            return new ExecutionLogChunkVO(
                    new String(bytes, StandardCharsets.UTF_8), next, next >= length, length >= MAX_LOG_BYTES);
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR.getCode(), "读取执行日志失败");
        }
    }

    @Override
    public Path resolveExisting(long executionId) {
        return requireExisting(resolve(executionId));
    }

    @Override
    public Path resolveStepExisting(long executionId, int stepNo) {
        return requireExisting(resolveStep(executionId, stepNo));
    }

    private Path requireExisting(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "执行日志不存在");
        }
        return path;
    }

    private Path resolve(long executionId) {
        if (executionId <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "executionId 非法");
        }
        Path path = root.resolve(executionId + ".log").normalize();
        if (!path.startsWith(root)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "日志路径非法");
        }
        return path;
    }

    private Path resolveStep(long executionId, int stepNo) {
        if (executionId <= 0 || stepNo < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "executionId 或 stepNo 非法");
        }
        Path path = root.resolve(String.valueOf(executionId)).resolve("step-" + stepNo + ".log").normalize();
        if (!path.startsWith(root)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "日志路径非法");
        }
        return path;
    }
}
