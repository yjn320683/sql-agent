package com.yjn.sqlagent.model.dto;

import lombok.Data;

@Data
public class SqlTaskQueryDTO {
    private String keyword = "";
    private String status = "active";
    private String updatedBy;
    private Integer page = 1;
    private Integer pageSize = 20;
}
