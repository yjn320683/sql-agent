package com.yjn.sqlagent.model.dto;

import lombok.Data;

@Data
public class AgentChatRequestDTO {

    private String sessionId;

    private Long taskId;

    private Long executionId;

    private Integer versionNo;

    private String obId;

    private String command;

    private String message;

    private AiContextDTO context;

    private String intent;

}
