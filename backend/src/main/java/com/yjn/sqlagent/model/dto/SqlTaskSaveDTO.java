package com.yjn.sqlagent.model.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import javax.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class SqlTaskSaveDTO {

    @NotBlank(message = "任务名称不能为空")
    @Size(max = 128, message = "任务名称不能超过128个字符")
    private String name;

    @Size(max = 1024, message = "任务描述不能超过1024个字符")
    private String description;

    @Size(max = 32, message = "任务类型不能超过32个字符")
    private String taskType;

    @Size(max = 128, message = "执行频率不能超过128个字符")
    private String executionFrequency;

    @Size(max = 64, message = "负责人不能超过64个字符")
    private String owner;

    @NotBlank(message = "SQL不能为空")
    @Size(max = 1000000, message = "SQL不能超过1000000个字符")
    private String sql;

    @Size(max = 1000000, message = "DDL不能超过1000000个字符")
    private String ddl;

    private Long revision;

    @Valid
    private List<SqlTaskParameterDTO> parameters = new ArrayList<>();
}
