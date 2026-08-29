package com.yjn.sqlagent.model.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import lombok.Data;

@Data
public class SqlTaskParameterDTO {
    @NotBlank(message = "参数名称不能为空")
    @Pattern(regexp = "^[A-Za-z_][A-Za-z0-9_]{0,63}$", message = "参数名称只能包含字母、数字和下划线")
    private String name;

    @NotBlank(message = "参数类型不能为空")
    private String type;

    @Size(max = 256, message = "参数说明不能超过256个字符")
    private String description;

    private Boolean required = Boolean.TRUE;
    private Object defaultValue;
}
