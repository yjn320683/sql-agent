package com.yjn.sqlagent.model.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import lombok.Data;

@Data
public class SqlTaskVersionCreateDTO {

    @NotBlank(message = "版本备注不能为空")
    @Size(max = 512, message = "版本备注不能超过512个字符")
    private String note;

    @javax.validation.constraints.NotNull(message = "revision不能为空")
    private Long revision;
}
