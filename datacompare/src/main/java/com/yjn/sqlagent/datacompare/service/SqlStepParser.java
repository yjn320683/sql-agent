package com.yjn.sqlagent.datacompare.service;

import com.yjn.sqlagent.datacompare.model.SqlStep;
import com.yjn.sqlagent.parsesql.HiveSqlParser;
import com.yjn.sqlagent.parsesql.SqlParseResult;
import com.yjn.sqlagent.parsesql.SqlStatementType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class SqlStepParser {
    private static final java.util.regex.Pattern STEP = java.util.regex.Pattern.compile(
            "(?i)^\\s*====\\s*step\\s*:\\s*(\\d+).*?====\\s*$");
    private final HiveSqlParser sqlParser;

    public SqlStepParser(HiveSqlParser sqlParser) {
        this.sqlParser = sqlParser;
    }

    public List<SqlStep> parse(String sql) {
        Map<Integer, StringBuilder> groups = splitGroups(sql == null ? "" : sql);
        List<SqlStep> result = new ArrayList<>();
        for (Map.Entry<Integer, StringBuilder> entry : groups.entrySet()) {
            List<String> statements = splitStatements(entry.getValue().toString());
            for (int index = 0; index < statements.size(); index++) {
                String statement = statements.get(index).trim();
                if (statement.isEmpty()) continue;
                SqlParseResult parsed = parseStatement(statement);
                if (parsed.getStatementType() == SqlStatementType.SET) continue;
                SqlStep step = new SqlStep();
                step.setGroup(entry.getKey());
                step.setOrder(index + 1);
                step.setName("Step(" + entry.getKey() + "-" + (index + 1) + ")");
                step.setSql(statement.endsWith(";") ? statement : statement + ";");
                step.getOutputTables().addAll(parsed.getOutputTables());
                step.getInputTables().addAll(parsed.getInputTables());
                if (!step.getOutputTables().isEmpty()) result.add(step);
            }
        }
        wireDependencies(result);
        return result;
    }

    public List<SqlStep> selected(List<SqlStep> all, Collection<String> requested) {
        Set<String> names = new LinkedHashSet<>(requested == null ? java.util.Collections.emptyList() : requested);
        if (names.isEmpty()) names.addAll(all.stream().map(SqlStep::getName).collect(Collectors.toList()));
        boolean changed;
        do {
            changed = false;
            for (SqlStep step : all) {
                if (names.contains(step.getName()) && names.addAll(step.getRequiredSteps())) changed = true;
            }
        } while (changed);
        return all.stream().filter(step -> names.contains(step.getName()))
                .sorted(Comparator.comparingInt(SqlStep::getGroup).thenComparingInt(SqlStep::getOrder))
                .collect(Collectors.toList());
    }

    public String rewrite(List<SqlStep> selected, Map<String, String> replacements) {
        StringBuilder result = new StringBuilder();
        for (SqlStep step : selected) {
            String statement = step.getSql();
            statement = sqlParser.rewriteTables(statement, replacements);
            result.append(statement.trim()).append('\n');
        }
        return result.toString();
    }

    private Map<Integer, StringBuilder> splitGroups(String sql) {
        Map<Integer, StringBuilder> groups = new LinkedHashMap<>();
        int current = 0;
        groups.put(current, new StringBuilder());
        for (String line : sql.split("\\R", -1)) {
            Matcher matcher = STEP.matcher(line);
            if (matcher.matches()) {
                current = Integer.parseInt(matcher.group(1));
                groups.computeIfAbsent(current, ignored -> new StringBuilder());
            } else {
                groups.get(current).append(line).append('\n');
            }
        }
        return groups;
    }

    private List<String> splitStatements(String sql) {
        return sqlParser.splitStatements(sql);
    }

    private SqlParseResult parseStatement(String statement) {
        try {
            SqlParseResult parsed = sqlParser.parseStatement(statement);
            if (parsed.hasUnresolvedTableReferences()) {
                throw new IllegalArgumentException("SQL包含动态表名："
                        + String.join(", ", parsed.getUnresolvedTableReferences()));
            }
            return parsed;
        } catch (Exception e) {
            throw new IllegalArgumentException("SQL关系解析失败，不能安全生成验数SQL", e);
        }
    }

    private void wireDependencies(List<SqlStep> steps) {
        Map<String, String> producer = new LinkedHashMap<>();
        Map<String, Set<String>> inherited = new LinkedHashMap<>();
        for (SqlStep step : steps) {
            for (String input : step.getInputTables()) {
                String dependency = producer.get(input.toLowerCase(Locale.ROOT));
                if (dependency != null) {
                    step.getRequiredSteps().add(dependency);
                    step.getRequiredSteps().addAll(inherited.getOrDefault(dependency, java.util.Collections.emptySet()));
                }
            }
            step.getRequiredSteps().add(step.getName());
            inherited.put(step.getName(), new LinkedHashSet<>(step.getRequiredSteps()));
            for (String output : step.getOutputTables()) producer.put(output.toLowerCase(Locale.ROOT), step.getName());
        }
    }
}
