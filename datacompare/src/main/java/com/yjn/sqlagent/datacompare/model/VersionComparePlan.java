package com.yjn.sqlagent.datacompare.model;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** 数据库持久化的不可变版本验数计划。 */
@Data
public class VersionComparePlan {
    private String token;
    private long taskId;
    private Integer baselineVersionNo;
    private int candidateVersionNo;
    private String baselineChecksum;
    private String candidateChecksum;
    private Long unionId;
    private String unionDdlChecksum;
    private String originalBaselineSql;
    private String originalCandidateSql;
    private String generatedBaselineSql;
    private String generatedCandidateSql;
    private List<String> baselineSteps = new ArrayList<>();
    private List<String> candidateSteps = new ArrayList<>();
    private List<CompareTableRequest> tableMappings = new ArrayList<>();
    private List<String> temporaryTables = new ArrayList<>();
}
