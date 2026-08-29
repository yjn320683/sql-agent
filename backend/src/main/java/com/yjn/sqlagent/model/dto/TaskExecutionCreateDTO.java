package com.yjn.sqlagent.model.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;

@Data
public class TaskExecutionCreateDTO {
    private Integer versionNo;
    private Long revision;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate businessDate;
    private Map<String, Object> parameters = new LinkedHashMap<>();
}
