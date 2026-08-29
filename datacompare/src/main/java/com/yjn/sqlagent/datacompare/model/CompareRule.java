package com.yjn.sqlagent.datacompare.model;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class CompareRule {
    private boolean onlyCompareSamePrimaryKey;
    private boolean ignoreNullPrimaryKey;
    private List<String> primaryKeyList = new ArrayList<>();
    private List<String> compareColumnList = new ArrayList<>();
    private List<String> probeColumnList = new ArrayList<>();
}
