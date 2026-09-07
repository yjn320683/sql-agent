package com.yjn.sqlagent.realtime.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yjn.sqlagent.realtime.common.RealtimeActorProvider;
import com.yjn.sqlagent.realtime.service.RealtimeSyncObservabilityService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RealtimeSyncObservabilityControllerTest {
    private final RealtimeSyncObservabilityService service = mock(RealtimeSyncObservabilityService.class);
    private final RealtimeActorProvider actors = mock(RealtimeActorProvider.class);
    private final RealtimeSyncObservabilityController controller =
            new RealtimeSyncObservabilityController(service, actors);

    @Test
    void readsStoredSchemaEventsWithoutTriggeringDetectionByDefault() {
        when(actors.requireActor()).thenReturn("admin");
        when(service.schemaChanges(9L)).thenReturn(List.of());

        controller.schemaChanges(9L, false);

        verify(service).schemaChanges(9L);
        verify(service, never()).detectSchemaChanges(9L);
    }

    @Test
    void refreshExplicitlyDetectsSourceAndTargetDifferences() {
        when(actors.requireActor()).thenReturn("admin");
        when(service.detectSchemaChanges(9L)).thenReturn(List.of());

        controller.schemaChanges(9L, true);

        verify(service).detectSchemaChanges(9L);
        verify(service, never()).schemaChanges(9L);
    }

    @Test
    void progressAndDirtyRecordEndpointsPreserveTaskInstanceAndActorContext() {
        when(actors.requireActor()).thenReturn("admin");
        when(service.progress(9L, 22L, true)).thenReturn(Map.of("sourceLagMs", 120L));
        Map<String, Object> body = Map.of("errorMessage", "转换失败", "rawPayload", Map.of("id", 1));
        when(service.addDirty(9L, 22L, body)).thenReturn(77L);

        controller.progress(9L, 22L, true);
        controller.addDirty(9L, 22L, body);
        controller.resolveDirty(9L, 77L);

        verify(service).progress(9L, 22L, true);
        verify(service).addDirty(9L, 22L, body);
        verify(service).resolveDirty(9L, 77L, "admin");
    }

    @Test
    void applyingSchemaEvolutionUsesCurrentActor() {
        when(actors.requireActor()).thenReturn("admin");
        when(service.applySchemaChange(9L, 17L, "admin")).thenReturn(Map.of("applied", true));

        controller.apply(9L, 17L);

        verify(service).applySchemaChange(9L, 17L, "admin");
    }
}
