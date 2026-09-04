package com.yjn.sqlagent.realtime.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yjn.sqlagent.realtime.common.RealtimeActorProvider;
import com.yjn.sqlagent.realtime.service.RealtimeRuntimeService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RealtimeFlinkCommonControllerTest {
    private RealtimeRuntimeService runtime;
    private RealtimeFlinkCommonController controller;

    @BeforeEach
    void setUp() {
        runtime = mock(RealtimeRuntimeService.class);
        RealtimeActorProvider actors = mock(RealtimeActorProvider.class);
        when(actors.requireActor()).thenReturn("tester");
        controller = new RealtimeFlinkCommonController(runtime, actors);
    }

    @Test
    void delegatesCheckpointAndSavepointToSharedHistoryReader() {
        List<Map<String, Object>> checkpoint = List.of(Map.of("path", "/checkpoint/1"));
        List<Map<String, Object>> savepoint = List.of(Map.of("path", "/savepoint/1"));
        when(runtime.stateHistory(9L, "checkpoint")).thenReturn(checkpoint);
        when(runtime.stateHistory(9L, "savepoint")).thenReturn(savepoint);

        assertEquals(checkpoint, controller.listCheckpoint(9L).getData());
        assertEquals(savepoint, controller.listSavepoint(9L).getData());
        verify(runtime).stateHistory(9L, "checkpoint");
        verify(runtime).stateHistory(9L, "savepoint");
    }
}
