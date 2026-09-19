package com.yjn.sqlagent.realtime.service;

import com.yjn.sqlagent.parsesql.ColumnLineage;
import com.yjn.sqlagent.parsesql.LineageDiagnostic;
import com.yjn.sqlagent.parsesql.ParseMode;
import com.yjn.sqlagent.parsesql.ParseRequest;
import com.yjn.sqlagent.parsesql.SqlDialect;
import com.yjn.sqlagent.parsesql.SqlLineageParser;
import com.yjn.sqlagent.parsesql.SqlScriptLineage;
import com.yjn.sqlagent.parsesql.SqlStatementType;
import com.yjn.sqlagent.parsesql.StatementLineage;
import com.yjn.sqlagent.parsesql.SourceColumn;
import com.yjn.sqlagent.parsesql.TableAccess;
import com.yjn.sqlagent.parsesql.TableIdentifier;
import com.yjn.sqlagent.parsesql.TableMetadataProvider;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 受管 Flink SQL 的 AST 安全策略；最终可执行性仍由 Flink Parser/Planner 校验。 */
@Service
public class ManagedSqlAnalyzer {
    private static final Set<String> SET_PREFIXES = Set.of("table.", "execution.", "pipeline.");
    private static final Pattern SET_KEY = Pattern.compile(
            "(?is)^\\s*SET\\s+(?:'((?:''|[^'])+)'|\"((?:\"\"|[^\"])+)\"|([A-Za-z0-9_.-]+))\\s*=");

    private final SqlLineageParser parser;
    private final TableMetadataProvider metadata;

    ManagedSqlAnalyzer() {
        this(new SqlLineageParser(), TableMetadataProvider.NONE);
    }

    @Autowired
    public ManagedSqlAnalyzer(SqlLineageParser parser, RealtimeTableMetadataProvider metadata) {
        this(parser, (TableMetadataProvider) metadata);
    }

    ManagedSqlAnalyzer(SqlLineageParser parser, TableMetadataProvider metadata) {
        this.parser = parser;
        this.metadata = metadata;
    }

    public Analysis analyze(String sql, String defaultDatabase) {
        if (text(sql).isEmpty()) throw new IllegalArgumentException("计算 SQL 不能为空");
        SqlScriptLineage script = parser.parseScript(request(sql, defaultDatabase));
        if (script.getStatements().isEmpty()) throw new IllegalArgumentException("计算 SQL 不能为空");

        Set<String> temporaryViews = new LinkedHashSet<>();
        Set<String> inputs = new LinkedHashSet<>();
        Set<String> outputs = new LinkedHashSet<>();
        int inserts = 0;
        for (StatementLineage statement : script.getStatements()) {
            SqlStatementType type = statement.getStatementType();
            if (type == SqlStatementType.SET) {
                validateSet(statement.getSql());
                continue;
            }
            if (type == SqlStatementType.CREATE_TEMPORARY_VIEW) {
                String temporaryView = temporaryView(statement);
                if (!temporaryView.isEmpty()) temporaryViews.add(temporaryView.toLowerCase(Locale.ROOT));
                inputs.addAll(statement.getInputTables());
                continue;
            }
            boolean insert = type == SqlStatementType.INSERT || type == SqlStatementType.REPLACE
                    || (type == SqlStatementType.WITH && !statement.getOutputTables().isEmpty());
            if (!insert) {
                throw new IllegalArgumentException(
                        "受管 SQL 只允许 SET、CREATE TEMPORARY VIEW、INSERT 或 EXECUTE STATEMENT SET");
            }
            inputs.addAll(statement.getInputTables());
            outputs.addAll(statement.getOutputTables());
            inserts++;
        }
        inputs.removeIf(value -> temporaryViews.contains(last(value).toLowerCase(Locale.ROOT)));
        if (inserts == 0 || outputs.isEmpty()) throw new IllegalArgumentException("计算 SQL 至少需要一个 INSERT 输出");
        return new Analysis(new ArrayList<>(inputs), new ArrayList<>(outputs), inserts,
                script.getStatements().size(), facts(script, inputs, outputs));
    }

    public List<String> splitStatements(String sql) {
        return parser.splitStatements(sql, SqlDialect.FLINK);
    }

    public Map<String, Object> explain(String sql, String defaultDatabase) {
        Analysis analysis = analyze(sql, defaultDatabase);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", true);
        result.put("inputs", analysis.inputs);
        result.put("outputs", analysis.outputs);
        result.put("statementCount", analysis.statementCount);
        result.put("insertCount", analysis.insertCount);
        result.put("plan", "受管流式 SQL：" + analysis.inputs.size() + " 个输入，" + analysis.outputs.size()
                + " 个输出，提交时由 Flink Planner 生成物理计划");
        return result;
    }

    private ParseRequest request(String sql, String defaultDatabase) {
        return ParseRequest.builder(sql).dialect(SqlDialect.FLINK).defaultCatalog("paimon")
                .defaultDatabase(text(defaultDatabase).isEmpty() ? "default" : defaultDatabase)
                .metadataProvider(metadata).mode(ParseMode.STRICT).build();
    }

    private void validateSet(String sql) {
        Matcher matcher = SET_KEY.matcher(sql);
        if (!matcher.find()) throw new IllegalArgumentException("SET 语句缺少配置项");
        String key = first(matcher.group(1), matcher.group(2), matcher.group(3)).replace("''", "'")
                .replace("\"\"", "\"").toLowerCase(Locale.ROOT);
        if (SET_PREFIXES.stream().noneMatch(key::startsWith)) {
            throw new IllegalArgumentException("SET 参数不在白名单中：" + key);
        }
    }

    private String temporaryView(StatementLineage statement) {
        for (ColumnLineage lineage : statement.getColumnLineages()) {
            if (lineage.getTargetTable() != null) return lineage.getTargetTable().getTable();
        }
        return "";
    }

    private String first(String... values) {
        for (String value : values) if (value != null) return value;
        return "";
    }

    private String last(String value) {
        int index = value.lastIndexOf('.');
        return index < 0 ? value : value.substring(index + 1);
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }

    /** 可持久化的解析事实不包含 SQL 正文，只保存表、字段关系、子句用途和诊断。 */
    private Map<String, Object> facts(SqlScriptLineage script, Set<String> inputs, Set<String> outputs) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("statementCount", script.getStatements().size());
        result.put("inputs", namedTables(inputs));
        result.put("outputs", namedTables(outputs));
        List<Map<String, Object>> statements = new ArrayList<>();
        int statementIndex = 0;
        for (StatementLineage statement : script.getStatements()) {
            statementIndex++;
            final int currentStatementIndex = statementIndex;
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("statementIndex", statementIndex);
            value.put("statementType", statement.getStatementType().name());
            List<Map<String, Object>> accesses = new ArrayList<>();
            for (TableAccess access : statement.getTableAccesses()) {
                Map<String, Object> item = table(access.getTable());
                item.put("role", access.getRole().name());
                item.put("dynamic", access.isDynamic());
                item.put("startOffset", access.getStartOffset());
                item.put("endOffset", access.getEndOffset());
                accesses.add(item);
            }
            value.put("tableAccesses", accesses);
            List<Map<String, Object>> columnLineages = new ArrayList<>();
            for (ColumnLineage lineage : statement.getColumnLineages()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("targetTable", lineage.getTargetTable() == null ? null : table(lineage.getTargetTable()));
                item.put("targetColumn", lineage.getTargetColumn());
                item.put("ordinal", lineage.getOrdinal());
                item.put("expression", lineage.getExpression());
                item.put("sources", columns(lineage.getSources()));
                columnLineages.add(item);
            }
            value.put("columnLineages", columnLineages);
            List<Map<String, Object>> usages = new ArrayList<>();
            statement.getColumnUsages().forEach(usage -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("type", usage.getType().name());
                item.put("expression", usage.getExpression());
                item.put("columns", columns(usage.getColumns()));
                usages.add(item);
            });
            value.put("columnUsages", usages);
            List<Map<String, Object>> joins = new ArrayList<>();
            statement.getJoins().forEach(join -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("joinType", join.getJoinType());
                item.put("condition", join.getCondition());
                item.put("leftColumns", columns(join.getLeftColumns()));
                item.put("rightColumns", columns(join.getRightColumns()));
                joins.add(item);
            });
            value.put("joins", joins);
            List<Map<String, Object>> diagnostics = new ArrayList<>();
            statement.getDiagnostics().forEach(item -> diagnostics.add(diagnostic(item, currentStatementIndex)));
            value.put("diagnostics", diagnostics);
            statements.add(value);
        }
        List<Map<String, Object>> diagnostics = new ArrayList<>();
        script.getDiagnostics().forEach(item -> diagnostics.add(diagnostic(item, null)));
        result.put("statements", statements);
        result.put("diagnostics", diagnostics);
        result.put("complete", diagnostics.isEmpty()
                && script.getStatements().stream().noneMatch(StatementLineage::isPartial));
        return result;
    }

    private List<Map<String, Object>> namedTables(Set<String> values) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String value : values) {
            String[] parts = value.split("\\.", 3);
            TableIdentifier identifier = parts.length == 3
                    ? new TableIdentifier(parts[0], parts[1], parts[2])
                    : TableIdentifier.parse(value, "paimon", "default");
            Map<String, Object> item = table(identifier);
            item.put("dynamic", false);
            result.add(item);
        }
        return result;
    }

    private Map<String, Object> table(TableIdentifier value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("catalog", value.getCatalog().isEmpty() ? null : value.getCatalog());
        result.put("db", value.getDatabase().isEmpty() ? null : value.getDatabase());
        result.put("table", value.getTable());
        result.put("qualifiedName", value.qualifiedName());
        return result;
    }

    private List<Map<String, Object>> columns(List<SourceColumn> values) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (SourceColumn value : values) {
            Map<String, Object> item = table(value.getTable());
            item.put("column", value.getColumn());
            item.put("direct", value.isDirect());
            result.add(item);
        }
        return result;
    }

    private Map<String, Object> diagnostic(LineageDiagnostic value, Integer statementIndex) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", value.getCode());
        result.put("severity", value.getSeverity().name());
        result.put("message", value.getMessage());
        result.put("line", value.getLine());
        result.put("column", value.getColumn());
        result.put("startOffset", value.getStartOffset());
        result.put("endOffset", value.getEndOffset());
        if (statementIndex != null) result.put("statementIndex", statementIndex);
        return result;
    }

    public static final class Analysis {
        private final List<String> inputs;
        private final List<String> outputs;
        private final int insertCount;
        private final int statementCount;
        private final Map<String, Object> lineageFacts;

        Analysis(List<String> inputs, List<String> outputs, int insertCount, int statementCount) {
            this(inputs, outputs, insertCount, statementCount, Map.of());
        }

        Analysis(List<String> inputs, List<String> outputs, int insertCount, int statementCount,
                 Map<String, Object> lineageFacts) {
            this.inputs = inputs;
            this.outputs = outputs;
            this.insertCount = insertCount;
            this.statementCount = statementCount;
            this.lineageFacts = lineageFacts;
        }

        public List<String> getInputs() { return inputs; }
        public List<String> getOutputs() { return outputs; }
        public int getInsertCount() { return insertCount; }
        public int getStatementCount() { return statementCount; }
        public Map<String, Object> getLineageFacts() { return lineageFacts; }
    }
}
