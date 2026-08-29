package com.yjn.sqlagent.model.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import lombok.Data;

@Data
public class SqlStructurePreviewDTO {
    @NotBlank
    @Size(max = 1000000)
    private String sql;
    private List<SqlTaskParameterDTO> parameterSchema = new ArrayList<>();
    private Map<String, Object> parameters = new LinkedHashMap<>();
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate businessDate;
    private Boolean validateParameterValues = Boolean.TRUE;
}
