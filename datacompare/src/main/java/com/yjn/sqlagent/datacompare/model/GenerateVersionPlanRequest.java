package com.yjn.sqlagent.datacompare.model;

import java.util.ArrayList;
import java.util.List;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import lombok.Data;

/** 生成不可变版本验数计划，实际提交只允许消费该计划。 */
@Data
public class GenerateVersionPlanRequest {
    @NotNull @Min(1) private Long taskId;
    @NotNull @Min(1) private Integer candidateVersionNo;
    private List<String> baselineSteps = new ArrayList<>();
    private List<String> candidateSteps = new ArrayList<>();
}
