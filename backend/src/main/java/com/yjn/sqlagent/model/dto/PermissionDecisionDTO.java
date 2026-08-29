package com.yjn.sqlagent.model.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import lombok.Data;

@Data
public class PermissionDecisionDTO {

    @NotBlank(message = "decision 不能为空")
    @Pattern(regexp = "allow|deny", message = "decision 只能是 allow 或 deny")
    private String decision;
}
