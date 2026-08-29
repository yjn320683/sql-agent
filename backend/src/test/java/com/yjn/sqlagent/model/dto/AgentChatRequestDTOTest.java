package com.yjn.sqlagent.model.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AgentChatRequestDTOTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesTaskAndExecutionForAgent() throws Exception {
        AgentChatRequestDTO dto = new AgentChatRequestDTO();
        dto.setSessionId("11111111-1111-4111-8111-111111111111");
        dto.setObId("u001");
        dto.setTaskId(42L);
        dto.setExecutionId(99L);
        dto.setCommand("sql_optimize");
        dto.setMessage("帮我优化");

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(dto));

        assertEquals("sql_optimize", json.path("command").asText());
        assertEquals(42L, json.path("taskId").asLong());
        assertEquals(99L, json.path("executionId").asLong());
    }
}
