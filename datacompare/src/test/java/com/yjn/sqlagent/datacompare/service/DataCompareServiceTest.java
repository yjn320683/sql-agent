package com.yjn.sqlagent.datacompare.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjn.sqlagent.datacompare.config.DataCompareProperties;
import com.yjn.sqlagent.datacompare.model.GenerateVersionPlanRequest;
import com.yjn.sqlagent.datacompare.model.PrepareVersionRequest;
import com.yjn.sqlagent.datacompare.model.TaskVersionContent;
import com.yjn.sqlagent.datacompare.model.VersionComparePlan;
import com.yjn.sqlagent.datacompare.repository.DataCompareRepository;
import com.yjn.sqlagent.parsesql.SqlLineageParser;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DataCompareServiceTest {
    private DataCompareRepository repository;
    private DataCompareService service;
    private TaskVersionContent baseline;
    private TaskVersionContent candidate;

    @BeforeEach
    void setUp() {
        repository = mock(DataCompareRepository.class);
        ObjectMapper json = new ObjectMapper();
        DataCompareProperties properties = new DataCompareProperties();
        properties.setHiveTempDatabase("verify_tmp");
        service = new DataCompareService(repository, new SqlStepParser(new SqlLineageParser()),
                new HiveDdlService(), null, null, new SqlParameterRenderer(json), null, json, properties);
        String baselineSql = "====step:1====\nINSERT OVERWRITE TABLE tmp.stage SELECT id FROM ods.source;\n"
                + "====step:2====\nINSERT OVERWRITE TABLE dw.target SELECT id FROM tmp.stage;";
        String candidateSql = "====step:1====\nINSERT OVERWRITE TABLE tmp.stage SELECT id FROM ods.source;\n"
                + "====step:2====\nINSERT OVERWRITE TABLE dw.target SELECT id, 'ok' remark FROM tmp.stage;";
        baseline = new TaskVersionContent(42, 0, "EFFECTIVE", "task", null, null,
                baselineSql, null, "[]", "baseline-checksum", 7, null, "baseline-checksum");
        candidate = new TaskVersionContent(42, 3, "DRAFT", "task", null, "candidate",
                candidateSql, "ALTER TABLE dw.target ADD COLUMNS (remark STRING)", "[]",
                "candidate-checksum", 4, null, "baseline-checksum");
        when(repository.effective(42)).thenReturn(baseline);
        when(repository.version(42, 3)).thenReturn(candidate);
        when(repository.unionContext(42, 3)).thenReturn(Collections.emptyMap());
        when(repository.versionStepScript(anyLong(), anyInt())).thenReturn(null);
        doNothing().when(repository).createPlan(any(VersionComparePlan.class), eq("admin"));
    }

    @Test
    void prepareFixesInitialCodeAsBaseline() {
        PrepareVersionRequest request = new PrepareVersionRequest();
        request.setTaskId(42L); request.setCandidateVersionNo(3);
        Map<String, Object> result = service.prepare(request);
        assertEquals(0, ((TaskVersionContent) result.get("baselineVersion")).getVersionNo());
        assertEquals(3, ((TaskVersionContent) result.get("candidateVersion")).getVersionNo());
    }

    @Test
    void generateAddsDependenciesRewritesDdlAndCreatesImmutablePlan() {
        GenerateVersionPlanRequest request = new GenerateVersionPlanRequest();
        request.setTaskId(42L); request.setCandidateVersionNo(3);
        request.setBaselineSteps(Collections.singletonList("Step(2-1)"));
        request.setCandidateSteps(Collections.singletonList("Step(2-1)"));
        Map<String, Object> result = service.generate(request, "admin");

        @SuppressWarnings("unchecked") java.util.List<String> selected =
                (java.util.List<String>) result.get("selectedCandidateSteps");
        assertEquals(java.util.Arrays.asList("Step(1-1)", "Step(2-1)"), selected);
        String generated = String.valueOf(result.get("generatedCandidateSql"));
        assertTrue(generated.contains("verify_tmp.dc_"));
        assertTrue(generated.contains("ADD COLUMNS (remark STRING)"));
        assertFalse(generated.toUpperCase().contains("DROP"));

        ArgumentCaptor<VersionComparePlan> plan = ArgumentCaptor.forClass(VersionComparePlan.class);
        org.mockito.Mockito.verify(repository).createPlan(plan.capture(), eq("admin"));
        assertEquals("candidate-checksum", plan.getValue().getCandidateChecksum());
        assertEquals(2, plan.getValue().getTableMappings().size());
        assertTrue(plan.getValue().getTableMappings().stream().anyMatch(item -> item.isRequiredByDdl()
                && "dw.target".equals(item.getCandidateSourceTable())));
    }

    @Test
    void rejectsCandidateWhoseEffectiveBaselineChanged() {
        candidate.setBaseEffectiveChecksum("old-checksum");
        PrepareVersionRequest request = new PrepareVersionRequest();
        request.setTaskId(42L); request.setCandidateVersionNo(3);
        assertThrows(IllegalArgumentException.class, () -> service.prepare(request));
    }
}
