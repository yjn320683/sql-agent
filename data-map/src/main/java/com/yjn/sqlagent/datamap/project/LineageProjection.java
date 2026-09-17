package com.yjn.sqlagent.datamap.project;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

final class LineageProjection {
    final List<Map<String, Object>> inputs = new ArrayList<>();
    final List<Map<String, Object>> outputs = new ArrayList<>();
    final List<Map<String, Object>> derivations = new ArrayList<>();
    final List<Map<String, Object>> usages = new ArrayList<>();
    final List<Map<String, Object>> joins = new ArrayList<>();
    boolean complete;
    List<Map<String, Object>> diagnostics = Collections.emptyList();
}
