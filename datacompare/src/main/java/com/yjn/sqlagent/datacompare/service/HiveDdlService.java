package com.yjn.sqlagent.datacompare.service;

import com.yjn.sqlagent.datacompare.model.TableColumn;
import com.yjn.sqlagent.parsesql.SqlDialect;
import com.yjn.sqlagent.parsesql.SqlLineageParser;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 验数与联合发布共用的 Hive DDL 白名单解析器。
 *
 * <p>这里只接受 ALTER TABLE ADD COLUMNS 与 CHANGE COLUMN；目标表必须能被明确解析，
 * 因而不会把未知 DDL 或删除类语句带入调测、发布链路。</p>
 */
@Service
public class HiveDdlService {
    private static final String IDENTIFIER = "`?[A-Za-z_][A-Za-z0-9_$]*`?(?:\\.`?[A-Za-z_][A-Za-z0-9_$]*`?)?";
    private static final Pattern ADD = Pattern.compile(
            "(?is)^\\s*ALTER\\s+TABLE\\s+(" + IDENTIFIER + ")\\s+ADD\\s+(?:COLUMN|COLUMNS)\\s+(.+?)\\s*$");
    private static final Pattern CHANGE = Pattern.compile(
            "(?is)^\\s*ALTER\\s+TABLE\\s+(" + IDENTIFIER + ")\\s+CHANGE\\s+(?:COLUMN\\s+)?(.+?)\\s*$");
    private final SqlLineageParser sqlParser;

    public HiveDdlService() {
        this(new SqlLineageParser());
    }

    @Autowired
    public HiveDdlService(SqlLineageParser sqlParser) {
        this.sqlParser = sqlParser;
    }

    public List<DdlStatement> parse(String script) {
        List<DdlStatement> result = new ArrayList<>();
        if (script == null || script.trim().isEmpty()) return result;
        for (String raw : sqlParser.splitStatements(script, SqlDialect.HIVE)) {
            String statement = sqlParser.stripComments(raw);
            if (statement.isEmpty()) continue;
            Matcher add = ADD.matcher(statement);
            Matcher change = CHANGE.matcher(statement);
            if (add.matches()) {
                result.add(new DdlStatement("ADD", normalizeTable(add.group(1)), add.group(2).trim()));
            } else if (change.matches()) {
                result.add(new DdlStatement("CHANGE", normalizeTable(change.group(1)), change.group(2).trim()));
            } else {
                throw new IllegalArgumentException("表结构变更仅支持 Hive ALTER TABLE ADD COLUMNS 或 CHANGE COLUMN："
                        + compact(statement));
            }
        }
        return result;
    }

    public Set<String> affectedTables(String script) {
        Set<String> tables = new LinkedHashSet<>();
        for (DdlStatement statement : parse(script)) tables.add(statement.getTable().toLowerCase(Locale.ROOT));
        return tables;
    }

    public String rewrite(String script, Map<String, String> replacements) {
        StringBuilder result = new StringBuilder();
        for (DdlStatement statement : parse(script)) {
            String target = findReplacement(replacements, statement.getTable());
            if (target == null) {
                throw new IllegalArgumentException("DDL 影响的表必须包含在已选 Step 输出中：" + statement.getTable());
            }
            result.append(statement.toSql(target)).append(";\n");
        }
        return result.toString();
    }

    public void requireNoOverlap(String unionDdl, Collection<String> memberDdls) {
        Set<String> used = new LinkedHashSet<>(affectedTables(unionDdl));
        for (String ddl : memberDdls) {
            for (String table : affectedTables(ddl)) {
                if (!used.add(table)) throw new IllegalArgumentException("联合 DDL 与成员 DDL 不能重复修改表：" + table);
            }
        }
    }

    /**
     * 仅供联合发布失败续跑使用。全部目标字段已按预期存在时可跳过；部分存在或类型冲突时拒绝续跑。
     */
    public boolean alreadyApplied(DdlStatement statement, List<TableColumn> actualColumns) {
        List<ExpectedColumn> expected = expectedColumns(statement);
        if (expected.isEmpty()) return false;
        Map<String, TableColumn> actual = new java.util.LinkedHashMap<>();
        for (TableColumn column : actualColumns) {
            actual.put(column.getName().toLowerCase(Locale.ROOT), column);
        }
        int present = 0;
        for (ExpectedColumn column : expected) {
            TableColumn current = actual.get(column.name.toLowerCase(Locale.ROOT));
            if (current == null) continue;
            present++;
            if (!normalizeType(current.getType()).equals(normalizeType(column.type))) {
                throw new IllegalArgumentException("Hive DDL 续跑检测到字段类型冲突：" + statement.getTable()
                        + "." + column.name + "，期望 " + column.type + "，实际 " + current.getType());
            }
        }
        if (present == 0) return false;
        if (present != expected.size()) {
            throw new IllegalArgumentException("Hive DDL 续跑检测到语句部分生效，需人工核对："
                    + statement.toProductionSql());
        }
        return true;
    }

    private List<ExpectedColumn> expectedColumns(DdlStatement statement) {
        String clause = statement.getClause().trim();
        if ("ADD".equals(statement.getType())) {
            if (clause.startsWith("(") && clause.endsWith(")")) {
                clause = clause.substring(1, clause.length() - 1).trim();
            }
            List<ExpectedColumn> result = new ArrayList<>();
            for (String definition : splitTopLevel(clause)) {
                ExpectedColumn column = parseDefinition(definition, 0);
                if (column != null) result.add(column);
            }
            return result;
        }
        ExpectedColumn changed = parseDefinition(clause, 1);
        return changed == null ? java.util.Collections.emptyList()
                : java.util.Collections.singletonList(changed);
    }

    /** CHANGE 的 offset=1 会跳过旧字段名，以新字段名和类型核对结果。 */
    private ExpectedColumn parseDefinition(String value, int offset) {
        List<String> tokens = splitWhitespace(value.trim());
        if (tokens.size() <= offset + 1) return null;
        return new ExpectedColumn(tokens.get(offset).replace("`", ""), tokens.get(offset + 1));
    }

    private List<String> splitTopLevel(String value) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int angle = 0, round = 0;
        boolean quoted = false;
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch == '\'' && (index == 0 || value.charAt(index - 1) != '\\')) quoted = !quoted;
            if (!quoted) {
                if (ch == '<') angle++;
                else if (ch == '>') angle--;
                else if (ch == '(') round++;
                else if (ch == ')') round--;
                else if (ch == ',' && angle == 0 && round == 0) {
                    result.add(current.toString().trim()); current.setLength(0); continue;
                }
            }
            current.append(ch);
        }
        if (current.length() > 0) result.add(current.toString().trim());
        return result;
    }

    private List<String> splitWhitespace(String value) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int angle = 0, round = 0;
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch == '<') angle++;
            else if (ch == '>') angle--;
            else if (ch == '(') round++;
            else if (ch == ')') round--;
            if (Character.isWhitespace(ch) && angle == 0 && round == 0) {
                if (current.length() > 0) { result.add(current.toString()); current.setLength(0); }
            } else current.append(ch);
        }
        if (current.length() > 0) result.add(current.toString());
        return result;
    }

    private String normalizeType(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private String findReplacement(Map<String, String> replacements, String table) {
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(table)) return entry.getValue();
        }
        return null;
    }

    private static String normalizeTable(String table) {
        return table.replace("`", "");
    }

    private static String compact(String value) {
        String result = value.replaceAll("\\s+", " ").trim();
        return result.substring(0, Math.min(300, result.length()));
    }

    public static final class DdlStatement {
        private final String type;
        private final String table;
        private final String clause;

        DdlStatement(String type, String table, String clause) {
            this.type = type;
            this.table = table;
            this.clause = clause;
        }

        public String getType() { return type; }
        public String getTable() { return table; }
        public String getClause() { return clause; }
        public String toSql(String targetTable) {
            return "ALTER TABLE " + HiveJdbcClient.quote(targetTable) + " "
                    + ("ADD".equals(type) ? "ADD COLUMNS " : "CHANGE COLUMN ") + clause;
        }
        public String toProductionSql() { return toSql(table); }
    }

    private static final class ExpectedColumn {
        private final String name;
        private final String type;
        private ExpectedColumn(String name, String type) { this.name = name; this.type = type; }
    }
}
