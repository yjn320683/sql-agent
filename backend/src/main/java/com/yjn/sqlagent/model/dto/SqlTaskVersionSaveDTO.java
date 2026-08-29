package com.yjn.sqlagent.model.dto;

import java.util.ArrayList;
import java.util.List;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import lombok.Data;

@Data
public class SqlTaskVersionSaveDTO {

    @NotBlank(message = "任务名称不能为空")
    @Size(max = 128, message = "任务名称不能超过128个字符")
    private String name;

    @Size(max = 1024, message = "任务描述不能超过1024个字符")
    private String description;

    @NotBlank(message = "SQL不能为空")
    @Size(max = 1000000, message = "SQL不能超过1000000个字符")
    private String sql;

    @Size(max = 1000000, message = "DDL不能超过1000000个字符")
    private String ddl;

    @Size(max = 512, message = "版本说明不能超过512个字符")
    private String note;

    @NotNull(message = "revision不能为空")
    private Long revision;

    @Valid
    private List<SqlTaskParameterDTO> parameters = new ArrayList<>();
}
