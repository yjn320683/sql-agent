package com.yjn.sqlagent.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjn.sqlagent.exception.BusinessException;
import com.yjn.sqlagent.mapper.SqlTaskExecutionMapper;
import com.yjn.sqlagent.mapper.SqlTaskExecutionStepMapper;
import com.yjn.sqlagent.model.dto.TaskExecutionCreateDTO;
import com.yjn.sqlagent.model.dto.ExecutionCenterQueryDTO;
import com.yjn.sqlagent.model.entity.SqlTask;
import com.yjn.sqlagent.model.entity.SqlTaskExecution;
import com.yjn.sqlagent.model.vo.ExecutionSummaryVO;
import com.yjn.sqlagent.model.vo.TaskExecutionPageVO;
import com.yjn.sqlagent.model.vo.TaskExecutionVO;
import com.yjn.sqlagent.service.AgentProxyService;
import com.yjn.sqlagent.service.SqlTaskService;
import com.yjn.sqlagent.service.SqlTaskVersionService;
import com.yjn.sqlagent.service.TaskSqlStructureService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;

@ExtendWith(MockitoExtension.class)
class TaskExecutionServiceImplTest {

    @Mock
    private SqlTaskExecutionMapper mapper;
    @Mock
    private SqlTaskService taskService;
    @Mock
    private SqlTaskExecutionStepMapper stepMapper;
    @Mock
    private AgentProxyService agentProxyService;
    @Mock
    private SqlTaskVersionService versionService;

    @Test
    void createPersistsTaskSnapshotBeforeCallingAgent() {
        SqlTask task = new SqlTask();
        task.setId(9L);
        task.setName("订单任务");
        task.setSqlContent("select * from dw.orders");
        task.setParameterSchema("[]");
        task.setRevision(3L);
        task.setArchived(false);
        task.setEnabled(true);
        when(taskService.require(9L)).thenReturn(task);
        doAnswer(invocation -> {
            SqlTaskExecution row = invocation.getArgument(0);
            row.setId(88L);
            return 1;
        }).when(mapper).insert(any(SqlTaskExecution.class));
        SqlTaskExecution stored = new SqlTaskExecution();
        stored.setId(88L);
        stored.setTaskId(9L);
        stored.setTaskNameSnapshot("订单任务");
        stored.setStatus("QUEUED");
        stored.setRequestedBy("138284");
        stored.setApplicationIds("[]");
        stored.setJobIds("[]");
        when(mapper.selectById(88L)).thenReturn(stored);
        when(stepMapper.listByExecution(88L)).thenReturn(Collections.emptyList());
        TaskExecutionCreateDTO request = new TaskExecutionCreateDTO();
        request.setRevision(3L);
        TaskExecutionServiceImpl service = service();

        TaskExecutionVO result = service.create("138284", 9L, request);

        ArgumentCaptor<SqlTaskExecution> captor = ArgumentCaptor.forClass(SqlTaskExecution.class);
        verify(mapper).insert(captor.capture());
        assertEquals("select * from dw.orders", captor.getValue().getSqlSnapshot());
        assertEquals("PENDING", captor.getValue().getStatus());
        assertEquals("138284", captor.getValue().getRequestedBy());
        assertEquals("EFFECTIVE", captor.getValue().getSourceType());
        verify(agentProxyService).startTaskExecution(88L);
        assertEquals("QUEUED", result.getStatus());
    }

    @Test
    void createRejectsDisabledTask() {
        SqlTask task = new SqlTask();
        task.setId(9L);
        task.setArchived(false);
        task.setEnabled(false);
        when(taskService.require(9L)).thenReturn(task);
        TaskExecutionCreateDTO request = new TaskExecutionCreateDTO();
        request.setRevision(3L);
        TaskExecutionServiceImpl service = service();

        assertThrows(BusinessException.class, () -> service.create("138284", 9L, request));
    }

    @Test
    void requireBelongsToTaskRejectsMismatchedExecution() {
        SqlTaskExecution stored = new SqlTaskExecution();
        stored.setId(88L);
        stored.setTaskId(9L);
        when(mapper.selectById(88L)).thenReturn(stored);
        when(stepMapper.listByExecution(88L)).thenReturn(Collections.emptyList());
        TaskExecutionServiceImpl service = service();

        assertThrows(BusinessException.class, () -> service.requireBelongsToTask(88L, 10L));
    }

    @Test
    void listAllNormalizesKeywordAndUsesGlobalPagination() {
        ExecutionCenterQueryDTO query = new ExecutionCenterQueryDTO();
        query.setStatus("active");
        query.setKeyword("  orders  ");
        query.setPage(2);
        query.setPageSize(10);
        when(mapper.countAll("active", "orders")).thenReturn(21L);
        SqlTaskExecution stored = new SqlTaskExecution();
        stored.setId(88L);
        stored.setTaskId(9L);
        stored.setTaskNameSnapshot("订单任务");
        stored.setStatus("RUNNING");
        when(mapper.listAll("active", "orders", 10L, 10))
                .thenReturn(Collections.singletonList(stored));
        TaskExecutionServiceImpl service = service();

        TaskExecutionPageVO result = service.listAll(query);

        assertEquals(21L, result.getTotal());
        assertEquals(2, result.getPage());
        assertEquals("RUNNING", result.getItems().get(0).getStatus());
    }

    @Test
    void listAllRejectsUnknownStatus() {
        ExecutionCenterQueryDTO query = new ExecutionCenterQueryDTO();
        query.setStatus("UNKNOWN");
        TaskExecutionServiceImpl service = service();

        assertThrows(BusinessException.class, () -> service.listAll(query));
    }

    @Test
    void summaryUsesCurrentExecutionTableAggregation() {
        ExecutionSummaryVO summary = new ExecutionSummaryVO();
        summary.setTotal(12L);
        summary.setActive(2L);
        when(mapper.summarize()).thenReturn(summary);
        TaskExecutionServiceImpl service = service();

        ExecutionSummaryVO result = service.summary();

        assertEquals(12L, result.getTotal());
        assertEquals(2L, result.getActive());
    }

    private TaskExecutionServiceImpl service() {
        ObjectMapper objectMapper = new ObjectMapper();
        return new TaskExecutionServiceImpl(
                mapper, stepMapper, taskService, versionService, agentProxyService,
                new TaskSqlStructureService(objectMapper), objectMapper);
    }
}
