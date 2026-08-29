package com.yjn.sqlagent.model.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import lombok.Data;

@Data
public class LoginRequestDTO {

    @NotBlank(message = "obId 不能为空")
    @Pattern(regexp = "^[0-9]{1,20}$", message = "obId 必须为 1-20 位数字")
    private String obId;
}
