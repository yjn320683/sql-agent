package com.yjn.sqlagent.model.dto;

import javax.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TaskRevisionDTO {
    @NotNull(message = "revision不能为空")
    private Long revision;
}
