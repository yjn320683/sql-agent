package com.yjn.sqlagent.realtime.service;

import com.yjn.sqlagent.parsesql.ColumnLineage;
import com.yjn.sqlagent.parsesql.ParseMode;
import com.yjn.sqlagent.parsesql.ParseRequest;
import com.yjn.sqlagent.parsesql.SqlDialect;
import com.yjn.sqlagent.parsesql.SqlLineageParser;
import com.yjn.sqlagent.parsesql.SqlScriptLineage;
import com.yjn.sqlagent.parsesql.SqlStatementType;
import com.yjn.sqlagent.parsesql.StatementLineage;
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
        return new Analysis(new ArrayList<>(inputs), new ArrayList<>(outputs), inserts, script.getStatements().size());
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

    public static final class Analysis {
        private final List<String> inputs;
        private final List<String> outputs;
        private final int insertCount;
        private final int statementCount;

        Analysis(List<String> inputs, List<String> outputs, int insertCount, int statementCount) {
            this.inputs = inputs;
            this.outputs = outputs;
            this.insertCount = insertCount;
            this.statementCount = statementCount;
        }

        public List<String> getInputs() { return inputs; }
        public List<String> getOutputs() { return outputs; }
        public int getInsertCount() { return insertCount; }
        public int getStatementCount() { return statementCount; }
    }
}
