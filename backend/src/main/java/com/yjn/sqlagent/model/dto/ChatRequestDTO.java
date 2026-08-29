package com.yjn.sqlagent.model.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import lombok.Data;

@Data
public class ChatRequestDTO {

    @NotBlank(message = "sessionId 不能为空")
    @javax.validation.constraints.Pattern(
            regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$",
            message = "sessionId 必须为标准 UUID")
    private String sessionId;

    @NotNull(message = "taskId 不能为空")
    @Min(value = 1, message = "taskId 必须大于0")
    private Long taskId;

    @Min(value = 1, message = "executionId 必须大于0")
    private Long executionId;

    @Min(value = 1, message = "versionNo 必须大于0")
    private Integer versionNo;

    @NotBlank(message = "message 不能为空")
    @Size(max = 200000, message = "message 不能超过 200000 个字符")
    private String message;

    private String command;

}
