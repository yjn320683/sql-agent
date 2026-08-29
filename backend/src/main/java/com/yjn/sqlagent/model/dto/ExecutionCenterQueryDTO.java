package com.yjn.sqlagent.model.dto;

import lombok.Data;

@Data
public class ExecutionCenterQueryDTO {
    private String status = "all";
    private String keyword;
    private Integer page = 1;
    private Integer pageSize = 20;
}
