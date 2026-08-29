package com.yjn.sqlagent.model.dto;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import lombok.Data;

@Data
public class TaskVersionUnionMemberDTO {
    @NotNull @Min(1) private Long taskId;
    @NotNull @Min(1) private Integer versionNo;
    @NotNull @Min(1) private Long versionRevision;
    @Size(max = 1000000) private String ddl;
}
