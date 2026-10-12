package com.yjn.sqlagent.realtime.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.yjn.sqlagent.realtime.common.RealtimeActorProvider;
import com.yjn.sqlagent.realtime.repository.RealtimeSyncRepository;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RealtimeSyncDdlExceptionControllerTest {
    @Test
    void rejectsMissingOrWrongTokenBeforeReadingTask() {
        RealtimeSyncRepository repository = mock(RealtimeSyncRepository.class);
        RealtimeSyncDdlExceptionController controller = new RealtimeSyncDdlExceptionController(
                repository, mock(RealtimeActorProvider.class), "secret");
        Map<String, Object> payload = Map.of("taskId", 1101, "eventId", "e");
        assertEquals(401, controller.report(null, payload).getStatusCodeValue());
        assertEquals(401, controller.report("wrong", payload).getStatusCodeValue());
        verify(repository, never()).requiredTask(1101);
    }

    @Test
    void acceptsMatchingTokenAndForwardsEvent() {
        RealtimeSyncRepository repository = mock(RealtimeSyncRepository.class);
        RealtimeSyncDdlExceptionController controller = new RealtimeSyncDdlExceptionController(
                repository, mock(RealtimeActorProvider.class), "secret");
        String eventId = "00000000-0000-0000-0000-000000000001";
        Map<String, Object> payload = Map.of("taskId", 1101, "eventId", eventId,
                "databaseName", "source", "tableName", "orders", "ddlSql", "ALTER TABLE orders DROP COLUMN name");
        assertEquals(200, controller.report("secret", payload).getStatusCodeValue());
        verify(repository, never()).requiredTask(1101);
        verify(repository).reportDdlException(eventId, 1101, "source", "orders",
                "ALTER TABLE orders DROP COLUMN name");
    }
}
