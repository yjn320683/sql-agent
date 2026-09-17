package com.yjn.sqlagent.model.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Min;
import javax.validation.constraints.Max;
import lombok.Data;

@Data
public class SqlTaskBackfillCreateDTO {
    @NotNull @JsonFormat(pattern = "yyyy-MM-dd") private LocalDate startDate;
    @NotNull @JsonFormat(pattern = "yyyy-MM-dd") private LocalDate endDate;
    private Map<String, Object> parameters = new LinkedHashMap<>();
    @NotNull @Min(1) @Max(20) private Integer maxConcurrency = 3;
}
