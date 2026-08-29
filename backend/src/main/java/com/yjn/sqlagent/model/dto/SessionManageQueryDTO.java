package com.yjn.sqlagent.model.dto;

import lombok.Data;

@Data
public class SessionManageQueryDTO {

    private String status = "active";
    private String keyword;
    private Integer page = 1;
    private Integer pageSize = 20;
    private String sortBy = "lastActiveAt";
    private String sortOrder = "desc";
}
