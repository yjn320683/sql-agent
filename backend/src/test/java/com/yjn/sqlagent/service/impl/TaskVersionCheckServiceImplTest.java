package com.yjn.sqlagent.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjn.sqlagent.mapper.SqlTaskVersionCheckMapper;
import com.yjn.sqlagent.model.entity.SqlTaskVersionCheck;
import com.yjn.sqlagent.model.vo.SqlTaskVersionVO;
import com.yjn.sqlagent.model.vo.TaskVersionCheckSummaryVO;
import com.yjn.sqlagent.service.SqlTaskVersionService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TaskVersionCheckServiceImplTest {
    @Mock private SqlTaskVersionCheckMapper mapper;
    @Mock private SqlTaskVersionService versionService;

    @Test
    void recordsQualityCountsAndPassedWithWarnings() {
        when(versionService.get(7L, 3)).thenReturn(version(true));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "PASSED_WITH_WARNINGS"); result.put("passed", true); result.put("complete", true);
        result.put("summary", Map.of("error", 0, "warning", 2, "info", 1));

        service().record(7L, 3, "QUALITY", result, "admin");

        ArgumentCaptor<SqlTaskVersionCheck> captor = ArgumentCaptor.forClass(SqlTaskVersionCheck.class);
        verify(mapper).upsert(captor.capture());
        assertEquals("PASSED_WITH_WARNINGS", captor.getValue().getStatus());
        assertTrue(captor.getValue().getPassed());
        assertEquals(2, captor.getValue().getWarningCount());
    }

    @Test
    void recordsValidationErrorsWarningsAndCurrentDraftIdentity() {
        when(versionService.get(7L, 3)).thenReturn(version(true));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", false); result.put("complete", true); result.put("compilationMs", 37L);
        result.put("errors", List.of(Map.of("message", "字段不存在")));
        result.put("warnings", List.of("建议限定分区"));

        service().record(7L, 3, "VALIDATE", result, "admin");

        ArgumentCaptor<SqlTaskVersionCheck> captor = ArgumentCaptor.forClass(SqlTaskVersionCheck.class);
        verify(mapper).upsert(captor.capture());
        SqlTaskVersionCheck check = captor.getValue();
        assertEquals(4L, check.getVersionRevision());
        assertEquals("checksum-3", check.getVersionChecksum());
        assertEquals("FAILED", check.getStatus());
        assertFalse(check.getPassed());
        assertEquals(1, check.getErrorCount());
        assertEquals(1, check.getWarningCount());
        assertEquals(37L, check.getDurationMs());
    }

    @Test
    void ignoresChecksWithoutVersionContext() {
        service().record(7L, null, "VALIDATE", Map.of("valid", true), "admin");

        verify(versionService, never()).get(7L, 0);
        verify(mapper, never()).upsert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void readinessRequiresCurrentBaselineAndBothRequiredChecks() {
        when(versionService.get(7L, 3)).thenReturn(version(true));
        when(mapper.listLatest(7L, 3)).thenReturn(List.of(check("VALIDATE", true), check("QUALITY", true)));
        when(mapper.selectLatestCompareStatus(7L, 3, "checksum-3")).thenReturn("PASSED");

        TaskVersionCheckSummaryVO summary = service().summary(7L, 3);

        assertTrue(summary.isAllPassed()); assertTrue(summary.isReadyToActivate());
        assertEquals(3, summary.getChecks().size());
    }

    @Test
    void staleDraftNeverBecomesReadyEvenWhenChecksPass() {
        when(versionService.get(7L, 3)).thenReturn(version(false));
        when(mapper.listLatest(7L, 3)).thenReturn(List.of(check("VALIDATE", true), check("QUALITY", true)));

        TaskVersionCheckSummaryVO summary = service().summary(7L, 3);

        assertTrue(summary.isAllPassed()); assertFalse(summary.isReadyToActivate());
        assertEquals("版本不是基于最新生效代码的可发布草稿", summary.getBlockingReason());
    }

    @Test
    void checksFromAnOlderDraftRevisionAreIgnored() {
        when(versionService.get(7L, 3)).thenReturn(version(true));
        SqlTaskVersionCheck oldValidate = check("VALIDATE", true);
        oldValidate.setVersionRevision(3L);
        oldValidate.setVersionChecksum("checksum-2");
        SqlTaskVersionCheck oldQuality = check("QUALITY", true);
        oldQuality.setVersionRevision(3L);
        oldQuality.setVersionChecksum("checksum-2");
        when(mapper.listLatest(7L, 3)).thenReturn(List.of(oldValidate, oldQuality));

        TaskVersionCheckSummaryVO summary = service().summary(7L, 3);

        assertFalse(summary.isAllPassed());
        assertFalse(summary.isReadyToActivate());
        assertEquals("请先完成编译校验和质量检查", summary.getBlockingReason());
    }

    @Test
    void failedRequiredCheckBlocksActivationEvenWhenBothChecksCompleted() {
        when(versionService.get(7L, 3)).thenReturn(version(true));
        SqlTaskVersionCheck quality = check("QUALITY", false);
        quality.setStatus("FAILED"); quality.setErrorCount(2);
        when(mapper.listLatest(7L, 3)).thenReturn(List.of(check("VALIDATE", true), quality));

        TaskVersionCheckSummaryVO summary = service().summary(7L, 3);

        assertFalse(summary.isAllPassed());
        assertFalse(summary.isReadyToActivate());
        assertEquals("存在未通过的版本检查", summary.getBlockingReason());
    }

    private TaskVersionCheckServiceImpl service() { return new TaskVersionCheckServiceImpl(mapper, versionService, new ObjectMapper()); }
    private SqlTaskVersionVO version(boolean canActivate) { SqlTaskVersionVO value = new SqlTaskVersionVO(); value.setCanActivate(canActivate); value.setRevision(4L); value.setSqlChecksum("checksum-3"); return value; }
    private SqlTaskVersionCheck check(String type, boolean passed) { SqlTaskVersionCheck value = new SqlTaskVersionCheck(); value.setCheckType(type); value.setStatus("PASSED"); value.setPassed(passed); value.setComplete(true); value.setErrorCount(0); value.setWarningCount(0); value.setVersionRevision(4L); value.setVersionChecksum("checksum-3"); return value; }
}
