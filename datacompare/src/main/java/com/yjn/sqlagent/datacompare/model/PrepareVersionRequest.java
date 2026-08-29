package com.yjn.sqlagent.datacompare.model;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import lombok.Data;

@Data
public class PrepareVersionRequest {
    @NotNull @Min(1) private Long taskId;
    @NotNull @Min(1) private Integer candidateVersionNo;
}
