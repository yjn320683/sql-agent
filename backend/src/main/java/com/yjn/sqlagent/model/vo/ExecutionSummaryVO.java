package com.yjn.sqlagent.model.vo;

import lombok.Data;

@Data
public class ExecutionSummaryVO {
    private Long total;
    private Long active;
    private Long succeeded24h;
    private Long failed24h;
    private Long cancelled24h;
}
