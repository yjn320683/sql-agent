package com.yjn.sqlagent.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjn.sqlagent.exception.BusinessException;
import com.yjn.sqlagent.mapper.SqlTaskMapper;
import com.yjn.sqlagent.mapper.SqlTaskVersionMapper;
import com.yjn.sqlagent.mapper.SqlTaskVersionStepMapper;
import com.yjn.sqlagent.model.dto.SqlTaskVersionActivateDTO;
import com.yjn.sqlagent.model.dto.SqlTaskVersionSaveDTO;
import com.yjn.sqlagent.model.entity.SqlTask;
import com.yjn.sqlagent.model.entity.SqlTaskVersion;
import com.yjn.sqlagent.service.SqlTaskVersionService;
import com.yjn.sqlagent.service.TaskSqlStructureService;
import com.yjn.sqlagent.service.TaskVersionUnionService;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SqlTaskVersionServiceImplTest {

    @Mock
    private SqlTaskMapper taskMapper;
    @Mock
    private SqlTaskVersionMapper versionMapper;
    @Mock
    private SqlTaskVersionStepMapper stepMapper;
    @Mock
    private TaskVersionUnionService unionService;

    @Test
    void createDraftAlwaysCopiesCurrentEffectiveCode() {
        SqlTask task = task(7L, "orders", "select 1", 2, "effective-checksum");
        when(taskMapper.selectByIdForUpdate(7L)).thenReturn(task);
        when(versionMapper.nextVersionNo(7L)).thenReturn(3);
        when(stepMapper.listByVersion(7L, 3)).thenReturn(Collections.emptyList());

        service().create("138284", 7L, "  优化 join 逻辑  ", 1L);

        ArgumentCaptor<SqlTaskVersion> captor = ArgumentCaptor.forClass(SqlTaskVersion.class);
        verify(versionMapper).insert(captor.capture());
        SqlTaskVersion version = captor.getValue();
        assertEquals(3, version.getVersionNo());
        assertEquals(2, version.getBaseEffectiveVersionNo());
        assertEquals("effective-checksum", version.getBaseEffectiveChecksum());
        assertEquals("select 1", version.getSqlContent());
        assertEquals("ALTER TABLE orders ADD COLUMNS (source STRING)", version.getDdlContent());
        assertEquals("DRAFT", version.getStatus());
        assertEquals("优化 join 逻辑", version.getVersionNote());
        assertEquals("138284", version.getCreatedBy());
    }

    @Test
    void saveUpdatesSameVersionInsteadOfCreatingSnapshot() {
        SqlTask task = task(7L, "orders", "select 1", 2, "effective-checksum");
        SqlTaskVersion draft = draft(7L, 3, 2L, "select 1", 2, "effective-checksum");
        when(taskMapper.selectByIdForUpdate(7L)).thenReturn(task);
        when(versionMapper.selectVersionForUpdate(7L, 3)).thenReturn(draft);
        when(versionMapper.updateDraftOptimistically(any(SqlTaskVersion.class), eq(2L))).thenReturn(1);
        SqlTaskVersion saved = draft(7L, 3, 3L, "select 2", 2, "effective-checksum");
        when(taskMapper.selectById(7L)).thenReturn(task);
        when(versionMapper.selectVersion(7L, 3)).thenReturn(saved);
        when(stepMapper.listByVersion(7L, 3)).thenReturn(Collections.emptyList());
        SqlTaskVersionSaveDTO request = new SqlTaskVersionSaveDTO();
        request.setName("orders");
        request.setSql("select 2");
        request.setDdl("ALTER TABLE orders ADD COLUMNS (channel STRING)");
        request.setRevision(2L);

        service().save("138284", 7L, 3, request);

        ArgumentCaptor<SqlTaskVersion> captor = ArgumentCaptor.forClass(SqlTaskVersion.class);
        verify(versionMapper).updateDraftOptimistically(captor.capture(), eq(2L));
        assertEquals("select 2", captor.getValue().getSqlContent());
        assertEquals("ALTER TABLE orders ADD COLUMNS (channel STRING)", captor.getValue().getDdlContent());
        verify(stepMapper).deleteByVersion(7L, 3);
    }

    @Test
    void saveRejectsDraftBasedOnOldEffectiveCode() {
        SqlTask task = task(7L, "orders", "select 2", 4, "new-checksum");
        SqlTaskVersion stale = draft(7L, 3, 2L, "select 1", 2, "old-checksum");
        when(taskMapper.selectByIdForUpdate(7L)).thenReturn(task);
        when(versionMapper.selectVersionForUpdate(7L, 3)).thenReturn(stale);
        SqlTaskVersionSaveDTO request = new SqlTaskVersionSaveDTO();
        request.setName("orders");
        request.setSql("select 3");
        request.setRevision(2L);

        assertThrows(BusinessException.class, () -> service().save("138284", 7L, 3, request));
    }

    @Test
    void activateMovesVersionToEffectiveAndExpiresOtherDrafts() {
        SqlTask task = task(7L, "orders", "select 1", 2, "effective-checksum");
        SqlTaskVersion draft = draft(7L, 3, 2L, "select 2", 2, "effective-checksum");
        when(taskMapper.selectByIdForUpdate(7L)).thenReturn(task);
        when(versionMapper.selectVersionForUpdate(7L, 3)).thenReturn(draft);
        when(taskMapper.activateVersionOptimistically(any(SqlTask.class), eq(1L))).thenReturn(1);
        when(versionMapper.markEffective(7L, 3, 2L, "138284")).thenReturn(1);
        SqlTask effectiveTask = task(7L, "orders", "select 2", 3, draft.getSqlChecksum());
        effectiveTask.setRevision(2L);
        SqlTaskVersion effective = draft(7L, 3, 3L, "select 2", 2, "effective-checksum");
        effective.setStatus("EFFECTIVE");
        when(taskMapper.selectById(7L)).thenReturn(effectiveTask);
        when(versionMapper.selectVersion(7L, 3)).thenReturn(effective);
        when(stepMapper.listByVersion(7L, 3)).thenReturn(Collections.emptyList());
        SqlTaskVersionActivateDTO request = new SqlTaskVersionActivateDTO();
        request.setTaskRevision(1L);
        request.setVersionRevision(2L);

        service().activate("138284", 7L, 3, request);

        ArgumentCaptor<SqlTask> captor = ArgumentCaptor.forClass(SqlTask.class);
        verify(taskMapper).activateVersionOptimistically(captor.capture(), eq(1L));
        assertEquals(3, captor.getValue().getEffectiveVersionNo());
        assertEquals("select 2", captor.getValue().getSqlContent());
        assertEquals("ALTER TABLE orders ADD COLUMNS (source STRING)", captor.getValue().getDdlContent());
        verify(versionMapper).markPreviousEffectiveHistorical(7L, 3, "138284");
        verify(versionMapper).markOtherDraftsStale(7L, 3, "138284");
        verify(versionMapper).markEffective(7L, 3, 2L, "138284");
    }

    @Test
    void activateRejectsMemberThatMustBePublishedByUnion() {
        SqlTask task = task(7L, "orders", "select 1", 2, "effective-checksum");
        SqlTaskVersion draft = draft(7L, 3, 2L, "select 2", 2, "effective-checksum");
        when(taskMapper.selectByIdForUpdate(7L)).thenReturn(task);
        when(versionMapper.selectVersionForUpdate(7L, 3)).thenReturn(draft);
        when(unionService.isActiveMember(7L, 3)).thenReturn(true);
        SqlTaskVersionActivateDTO request = new SqlTaskVersionActivateDTO();
        request.setTaskRevision(1L);
        request.setVersionRevision(2L);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service().activate("138284", 7L, 3, request));
        assertEquals("该版本已加入联合版本，只能从联合版本页统一发布", error.getMessage());
    }

    private SqlTask task(long id, String name, String sql, Integer effectiveVersionNo, String checksum) {
        SqlTask task = new SqlTask();
        task.setId(id);
        task.setName(name);
        task.setSqlContent(sql);
        task.setDdlContent("ALTER TABLE orders ADD COLUMNS (source STRING)");
        task.setParameterSchema("[]");
        task.setSqlChecksum(checksum);
        task.setEffectiveVersionNo(effectiveVersionNo);
        task.setRevision(1L);
        task.setArchived(false);
        return task;
    }

    private SqlTaskVersion draft(long taskId, int versionNo, long revision, String sql,
                                 Integer baseVersionNo, String baseChecksum) {
        SqlTaskVersion version = new SqlTaskVersion();
        version.setTaskId(taskId);
        version.setVersionNo(versionNo);
        version.setRevision(revision);
        version.setName("orders");
        version.setSqlContent(sql);
        version.setDdlContent("ALTER TABLE orders ADD COLUMNS (source STRING)");
        version.setParameterSchema("[]");
        version.setSqlChecksum(new TaskSqlStructureService(new ObjectMapper()).checksum(sql, "[]"));
        version.setBaseEffectiveVersionNo(baseVersionNo);
        version.setBaseEffectiveChecksum(baseChecksum);
        version.setVersionNote("优化");
        version.setStatus("DRAFT");
        return version;
    }

    private SqlTaskVersionService service() {
        ObjectMapper objectMapper = new ObjectMapper();
        return new SqlTaskVersionServiceImpl(
                taskMapper, versionMapper, stepMapper,
                new TaskSqlStructureService(objectMapper), objectMapper, unionService);
    }
}
