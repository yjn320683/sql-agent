package com.yjn.sqlagent.datacompare.model;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateCompareRuleRequest {
    @NotNull @Valid private CompareRule rule;
}
