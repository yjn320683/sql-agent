package com.yjn.sqlagent.model.dto;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import lombok.Data;

@Data
public class SqlCompletionRequestDTO {
    @NotNull
    @Size(max = 1000000)
    private String sql;
    @NotNull
    @Min(0)
    private Integer cursor;
    @Size(max = 256)
    private String defaultDb;
    @Min(1)
    @Max(200)
    private Integer limit = 100;
}
