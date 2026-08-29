package com.yjn.sqlagent.model.dto;

import javax.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SqlTaskVersionActivateDTO {

    @NotNull(message = "taskRevision不能为空")
    private Long taskRevision;

    @NotNull(message = "versionRevision不能为空")
    private Long versionRevision;
}
