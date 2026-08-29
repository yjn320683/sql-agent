package com.yjn.sqlagent.datacompare.model;

import java.util.ArrayList;
import java.util.List;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateCompareRequest {
    @NotBlank private String compareType;
    private String planToken;
    private Long taskId;
    private Integer baselineVersionNo;
    private Integer candidateVersionNo;
    private String baselineTable;
    private String candidateTable;
    private List<String> baselineSteps = new ArrayList<>();
    private List<String> candidateSteps = new ArrayList<>();
    @NotNull private Boolean onlyCompareSameColumn;
    @NotNull @Valid private List<CompareTableRequest> tables = new ArrayList<>();
}
