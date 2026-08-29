package com.yjn.sqlagent.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import com.yjn.sqlagent.exception.BusinessException;
import com.yjn.sqlagent.mapper.SqlTaskMapper;
import com.yjn.sqlagent.model.dto.SqlTaskQueryDTO;
import com.yjn.sqlagent.model.dto.SqlTaskSaveDTO;
import com.yjn.sqlagent.model.entity.SqlTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SqlTaskServiceImplTest {

    @Mock
    private SqlTaskMapper mapper;

    @Test
    void createTrimsFieldsAndCreatesInitialEffectiveCode() {
        SqlTaskSaveDTO request = new SqlTaskSaveDTO();
        request.setName("  订单日汇总  ");
        request.setDescription("  每日聚合  ");
        request.setSql("  select 1  ");
        SqlTaskServiceImpl service = new SqlTaskServiceImpl(mapper);

        service.create("138284", request);

        ArgumentCaptor<SqlTask> captor = ArgumentCaptor.forClass(SqlTask.class);
        verify(mapper).insert(captor.capture());
        SqlTask task = captor.getValue();
        assertEquals("订单日汇总", task.getName());
        assertEquals("每日聚合", task.getDescription());
        assertEquals("select 1", task.getSqlContent());
        assertEquals("RUN_HIVE", task.getTaskType());
        assertEquals("手动执行", task.getExecutionFrequency());
        assertEquals("138284", task.getOwner());
        assertEquals(true, task.getEnabled());
        assertEquals("138284", task.getCreatedBy());
        assertEquals("138284", task.getUpdatedBy());
    }

    @Test
    void listUsesGlobalTaskQueryWithoutObIdFilter() {
        SqlTaskQueryDTO query = new SqlTaskQueryDTO();
        query.setKeyword("  1024  ");
        when(mapper.countTasks("1024", "active", null)).thenReturn(0L);
        SqlTaskServiceImpl service = new SqlTaskServiceImpl(mapper);

        service.list(query);

        verify(mapper).countTasks("1024", "active", null);
    }

    @Test
    void listRejectsOversizedPage() {
        SqlTaskQueryDTO query = new SqlTaskQueryDTO();
        query.setPageSize(101);
        SqlTaskServiceImpl service = new SqlTaskServiceImpl(mapper);

        assertThrows(BusinessException.class, () -> service.list(query));
    }

    @Test
    void updateOnlyChangesTaskMetadata() {
        SqlTask existing = new SqlTask();
        existing.setId(7L);
        existing.setName("old");
        existing.setTaskType("RUN_HIVE");
        existing.setExecutionFrequency("每天 07:00");
        existing.setOwner("100001");
        existing.setEnabled(false);
        existing.setSqlContent("select 1");
        existing.setParameterSchema("[]");
        existing.setRevision(2L);
        existing.setArchived(false);
        existing.setCreatedBy("100001");
        when(mapper.selectById(7L)).thenReturn(existing);
        when(mapper.updateMetadataOptimistically(any(SqlTask.class), org.mockito.ArgumentMatchers.eq(2L))).thenReturn(1);
        SqlTaskSaveDTO request = new SqlTaskSaveDTO();
        request.setName("old");
        request.setSql("select 1");
        request.setOwner("138284");
        request.setRevision(2L);
        SqlTaskServiceImpl service = new SqlTaskServiceImpl(mapper);

        service.update("138284", 7L, request);

        assertEquals("100001", existing.getCreatedBy());
        ArgumentCaptor<SqlTask> captor = ArgumentCaptor.forClass(SqlTask.class);
        verify(mapper).updateMetadataOptimistically(captor.capture(), org.mockito.ArgumentMatchers.eq(2L));
        assertEquals("138284", captor.getValue().getUpdatedBy());
        assertEquals("RUN_HIVE", captor.getValue().getTaskType());
        assertEquals("每天 07:00", captor.getValue().getExecutionFrequency());
        assertEquals("138284", captor.getValue().getOwner());
        assertEquals(false, captor.getValue().getEnabled());
    }

    @Test
    void updateRejectsDirectEffectiveSqlChange() {
        SqlTask existing = new SqlTask();
        existing.setId(7L);
        existing.setName("orders");
        existing.setTaskType("RUN_HIVE");
        existing.setExecutionFrequency("手动执行");
        existing.setOwner("100001");
        existing.setSqlContent("select 1");
        existing.setParameterSchema("[]");
        existing.setRevision(2L);
        existing.setArchived(false);
        when(mapper.selectById(7L)).thenReturn(existing);
        SqlTaskSaveDTO request = new SqlTaskSaveDTO();
        request.setName("orders");
        request.setSql("select 2");
        request.setRevision(2L);

        assertThrows(BusinessException.class,
                () -> new SqlTaskServiceImpl(mapper).update("138284", 7L, request));

        verify(mapper, never()).updateMetadataOptimistically(any(SqlTask.class),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void setEnabledUsesIndependentOptimisticUpdate() {
        SqlTask existing = new SqlTask();
        existing.setId(7L);
        existing.setRevision(3L);
        existing.setArchived(false);
        existing.setEnabled(true);
        when(mapper.selectById(7L)).thenReturn(existing);
        when(mapper.updateEnabledOptimistically(7L, 3L, false, "138284")).thenReturn(1);
        SqlTaskServiceImpl service = new SqlTaskServiceImpl(mapper);

        service.setEnabled("138284", 7L, 3L, false);

        verify(mapper).updateEnabledOptimistically(7L, 3L, false, "138284");
    }
}
