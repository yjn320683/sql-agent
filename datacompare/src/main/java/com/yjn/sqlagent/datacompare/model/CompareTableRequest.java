package com.yjn.sqlagent.datacompare.model;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CompareTableRequest {
    private String originalTable;
    private String baselineSourceTable;
    private String candidateSourceTable;
    private boolean requiredByDdl;
    @NotBlank private String baselineTable;
    @NotBlank private String candidateTable;
    @Valid private CompareRule rule = new CompareRule();
}
