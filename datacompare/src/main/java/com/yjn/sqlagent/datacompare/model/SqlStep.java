package com.yjn.sqlagent.datacompare.model;

import java.util.LinkedHashSet;
import java.util.Set;
import lombok.Data;

@Data
public class SqlStep {
    private String name;
    private int group;
    private int order;
    private String sql;
    private Set<String> inputTables = new LinkedHashSet<>();
    private Set<String> outputTables = new LinkedHashSet<>();
    private Set<String> requiredSteps = new LinkedHashSet<>();
}
