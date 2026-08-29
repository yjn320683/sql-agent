package com.yjn.sqlagent.model.dto;

import java.util.ArrayList;
import java.util.List;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import lombok.Data;

@Data
public class TaskVersionUnionSaveDTO {
    private Long revision;
    @Size(max = 1000000) private String unionDdl;
    @NotNull @Size(min = 2, message = "联合版本至少包含两个任务版本")
    @Valid private List<TaskVersionUnionMemberDTO> members = new ArrayList<>();
}
