package com.yjn.sqlagent.service;

import com.yjn.sqlagent.model.dto.ExecutionQueryDTO;
import com.yjn.sqlagent.model.dto.ExecutionCenterQueryDTO;
import com.yjn.sqlagent.model.dto.TaskExecutionCreateDTO;
import com.yjn.sqlagent.model.vo.ExecutionSummaryVO;
import com.yjn.sqlagent.model.vo.TaskExecutionPageVO;
import com.yjn.sqlagent.model.vo.TaskExecutionVO;

public interface TaskExecutionService {
    TaskExecutionVO create(String obId, long taskId, TaskExecutionCreateDTO request);
    TaskExecutionVO rerun(String obId, long executionId);
    TaskExecutionPageVO list(long taskId, ExecutionQueryDTO query);
    TaskExecutionPageVO listAll(ExecutionCenterQueryDTO query);
    ExecutionSummaryVO summary();
    TaskExecutionVO get(long executionId);
    TaskExecutionVO requireBelongsToTask(long executionId, long taskId);
    void cancel(long executionId);
}
