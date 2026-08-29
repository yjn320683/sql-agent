package com.yjn.sqlagent.model.vo;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class SessionVO {

    private String sessionId;
    private String title;
    private LocalDateTime createdAt;
    private LocalDateTime lastActiveAt;
    private Boolean archived;
}
