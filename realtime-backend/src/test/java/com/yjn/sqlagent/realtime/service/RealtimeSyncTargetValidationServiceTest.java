package com.yjn.sqlagent.realtime.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RealtimeSyncTargetValidationServiceTest {
    @Test
    void checksOnlyTargetsForNewlyAddedSourceTables() {
        RealtimePaimonCatalogService catalog = mock(RealtimePaimonCatalogService.class);
        when(catalog.existingTables("ods_real", List.of("ods_customers")))
                .thenReturn(Set.of("ods_customers"));
        RealtimeSyncTargetValidationService service = new RealtimeSyncTargetValidationService(catalog);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.validateAddedTargets(
                        config(List.of("orders"), List.of("ods_orders")),
                        config(List.of("orders", "customers"),
                                List.of("ods_orders", "ods_customers"))));

        assertEquals("新增同步表失败：以下源表对应的目标 Paimon 表已存在，系统不会覆盖已有表："
                + "customers → ods_real.ods_customers。请确认目标表归属并处理后重试", error.getMessage());
        verify(catalog).existingTables("ods_real", List.of("ods_customers"));
    }

    @Test
    void skipsPhysicalLookupWhenNoSourceTableWasAdded() {
        RealtimePaimonCatalogService catalog = mock(RealtimePaimonCatalogService.class);
        RealtimeSyncTargetValidationService service = new RealtimeSyncTargetValidationService(catalog);
        Map<String, Object> saved = config(List.of("orders"), List.of("ods_orders"));

        service.validateAddedTargets(saved, saved);

        verify(catalog, never()).existingTables(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void rejectsMismatchedSourceAndTargetLists() {
        RealtimeSyncTargetValidationService service = new RealtimeSyncTargetValidationService(
                mock(RealtimePaimonCatalogService.class));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.validateAddedTargets(Map.of(),
                        config(List.of("orders", "customers"), List.of("ods_orders"))));

        assertEquals("源表列表与目标Paimon表列表不一致", error.getMessage());
    }

    private Map<String, Object> config(List<String> sources, List<String> targets) {
        return Map.of("targetDatabase", "ods_real", "cdcConfig", Map.of(
                "selectedTables", sources, "targetTableList", targets,
                "targetDatabase", "ods_real"));
    }
}
