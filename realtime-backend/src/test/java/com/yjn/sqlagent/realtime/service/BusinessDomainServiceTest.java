package com.yjn.sqlagent.realtime.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yjn.sqlagent.realtime.repository.BusinessDomainRepository;
import com.yjn.sqlagent.realtime.repository.RealtimeTableRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BusinessDomainServiceTest {
    private BusinessDomainRepository repository;
    private RealtimeTableRepository tables;
    private BusinessDomainService service;

    @BeforeEach
    void setUp() {
        repository = mock(BusinessDomainRepository.class);
        tables = mock(RealtimeTableRepository.class);
        service = new BusinessDomainService(repository, tables);
    }

    @Test
    void createValidatesStableDomainCode() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create(new LinkedHashMap<>(Map.of("code", "Order-Domain", "name", "订单"))));
    }

    @Test
    void disabledDomainCannotReceiveNewAsset() {
        when(repository.required(3L)).thenReturn(Map.of("id", 3L, "enabled", 0));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("assetType", "HIVE");
        request.put("catalogName", "hive");
        request.put("databaseName", "ods");
        request.put("tableName", "orders");
        request.put("domainId", 3L);

        assertThrows(IllegalStateException.class, () -> service.assign(request, "tester"));
    }

    @Test
    void paimonAssignmentMustResolveManagedRealtimeTable() {
        when(repository.required(2L)).thenReturn(Map.of("id", 2L, "enabled", 1));
        when(tables.required(19L)).thenReturn(Map.of(
                "id", 19L, "catalogName", "paimon", "databaseName", "dwd", "tableName", "orders"));
        when(repository.asset("PAIMON", "paimon.dwd.orders"))
                .thenReturn(Map.of("domainId", 2L, "assetKey", "paimon.dwd.orders"));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("assetType", "PAIMON");
        request.put("catalogName", "ignored");
        request.put("databaseName", "ignored");
        request.put("tableName", "ignored");
        request.put("realtimeTableId", 19L);
        request.put("domainId", 2L);

        Map<String, Object> result = service.assign(request, "tester");

        assertEquals(2L, result.get("domainId"));
        verify(repository).assign(eq("PAIMON"), eq("paimon.dwd.orders"), eq(19L), eq("paimon"),
                eq("dwd"), eq("orders"), eq(2L), eq("tester"));
    }

    @Test
    void unmanagedPaimonAssetIsRejected() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("assetType", "PAIMON");
        request.put("databaseName", "dwd");
        request.put("tableName", "orders");
        request.put("domainId", 2L);

        assertThrows(IllegalArgumentException.class, () -> service.assign(request, "tester"));
    }
}
