package com.yjn.sqlagent.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("chat_session")
public class ChatSession {

    @TableId
    private String sessionId;

    private String obId;

    private String title;

    private String contextType;

    private String contextId;

    private String contextTitle;

    private LocalDateTime createdAt;

    private LocalDateTime lastActiveAt;

    private Integer archived;
}
