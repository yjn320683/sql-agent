package com.yjn.sqlagent.realtime.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class UnifiedTaskRequestTest {

    @Test
    void normalizesMemoryValuesForFlinkCli() {
        UnifiedTaskRequest request = new UnifiedTaskRequest();
        request.setFlinkConf(Map.of("taskManagerMemoryGb", 2.0, "jobManagerMemoryGb", 1.5));

        TaskActionRequest action = request.toActionRequest();

        assertEquals("2GB", action.getTaskManagerMemory());
        assertEquals("1536MB", action.getJobManagerMemory());
    }
}
