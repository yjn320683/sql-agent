package com.yjn.sqlagent.model.dto;

import java.util.LinkedHashMap;
import java.util.Map;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SqlTaskScheduleSaveDTO {
    @NotBlank private String scheduleType;
    private String cronExpression;
    @NotBlank private String timezone = "Asia/Shanghai";
    @NotNull private Boolean enabled;
    @NotBlank private String concurrencyPolicy = "FORBID";
    @NotNull @Min(0) @Max(10) private Integer maxRetries = 0;
    @NotNull @Min(10) @Max(86400) private Integer retryIntervalSeconds = 60;
    private Map<String, Object> parameters = new LinkedHashMap<>();
    private Long revision;
}
