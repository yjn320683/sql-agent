package com.yjn.sqlagent.service;

import com.yjn.sqlagent.model.vo.ExecutionLogChunkVO;
import java.nio.file.Path;

public interface TaskExecutionLogService {
    ExecutionLogChunkVO read(long executionId, long offset, int limit);
    ExecutionLogChunkVO readStep(long executionId, int stepNo, long offset, int limit);
    Path resolveExisting(long executionId);
    Path resolveStepExisting(long executionId, int stepNo);
}
