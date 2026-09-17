package com.yjn.sqlagent.realtime.service;

import com.yjn.sqlagent.realtime.config.RealtimeProperties;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelVisitor;
import org.apache.calcite.rel.core.TableScan;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.StatementSet;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.internal.TableEnvironmentImpl;
import org.apache.flink.table.catalog.ObjectIdentifier;
import org.apache.flink.table.delegation.Parser;
import org.apache.flink.table.operations.ModifyOperation;
import org.apache.flink.table.operations.Operation;
import org.apache.flink.table.operations.QueryOperation;
import org.apache.flink.table.operations.SinkModifyOperation;
import org.apache.flink.table.operations.SourceQueryOperation;
import org.apache.flink.table.planner.operations.PlannerQueryOperation;
import org.springframework.stereotype.Service;

/** 使用与运行时一致的 Flink Parser/Planner 校验受管 SQL，并从解析树提取表依赖。 */
@Service
public class ManagedFlinkPlannerService {
    private static final Pattern SET = Pattern.compile(
            "(?is)^SET\\s+(?:'((?:''|[^'])+)'|([A-Za-z0-9_.-]+))\\s*=\\s*(?:'((?:''|[^'])*)'|([^\\s]+))$");
    private final RealtimeProperties properties;
    private final ManagedSqlAnalyzer policy;

    public ManagedFlinkPlannerService(RealtimeProperties properties, ManagedSqlAnalyzer policy) {
        this.properties = properties;
        this.policy = policy;
    }

    public Analysis analyze(String sql, String defaultDatabase) {
        return plan(sql, defaultDatabase, true);
    }

    public Analysis validate(String sql, String defaultDatabase) {
        return plan(sql, defaultDatabase, false);
    }

    private Analysis plan(String sql, String defaultDatabase, boolean includeExplain) {
        ManagedSqlAnalyzer.Analysis expected = policy.analyze(sql, defaultDatabase);
        try {
            TableEnvironment table = TableEnvironment.create(
                    EnvironmentSettings.newInstance().inStreamingMode().build());
            table.executeSql(catalogDdl());
            table.useCatalog("paimon");
            table.useDatabase(defaultDatabase);
            Parser parser = ((TableEnvironmentImpl) table).getParser();
            StatementSet statementSet = table.createStatementSet();
            Set<String> inputs = new LinkedHashSet<>();
            Set<String> outputs = new LinkedHashSet<>();
            int insertCount = 0;
            for (String raw : policy.splitStatements(sql)) {
                String statement = unwrapStatementSet(raw);
                if (statement.isEmpty()) continue;
                String upper = statement.toUpperCase(Locale.ROOT);
                if (upper.startsWith("SET ")) {
                    applySet(table, statement);
                } else if (upper.startsWith("CREATE TEMPORARY VIEW ")) {
                    table.executeSql(statement);
                } else if (upper.startsWith("INSERT ")) {
                    for (Operation operation : parser.parse(statement)) {
                        collect(operation, inputs, outputs);
                    }
                    statementSet.addInsertSql(statement);
                    insertCount++;
                }
            }
            if (insertCount == 0) throw new IllegalArgumentException("计算 SQL 至少需要一个 INSERT 输出");
            requireSameLineage("输入", expected.getInputs(), inputs);
            requireSameLineage("输出", expected.getOutputs(), outputs);
            String plan = includeExplain ? statementSet.explain() : "";
            return new Analysis(new ArrayList<>(inputs), new ArrayList<>(outputs), insertCount,
                    expected.getStatementCount(), plan, expected.getLineageFacts());
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Flink SQL 解析或执行计划校验失败：" + rootMessage(ex), ex);
        }
    }

    /** AST 负责平台安全策略，Flink Planner 负责真实可执行计划；两侧依赖不一致时拒绝提交。 */
    private void requireSameLineage(String role, List<String> astTables, Set<String> plannerTables) {
        Set<String> ast = normalized(astTables);
        Set<String> planner = normalized(plannerTables);
        if (!ast.equals(planner)) {
            Set<String> onlyAst = new LinkedHashSet<>(ast);
            onlyAst.removeAll(planner);
            Set<String> onlyPlanner = new LinkedHashSet<>(planner);
            onlyPlanner.removeAll(ast);
            throw new IllegalArgumentException("SQL 血缘与 Flink Planner 的" + role + "表不一致，"
                    + "仅 AST=" + onlyAst + "，仅 Planner=" + onlyPlanner);
        }
    }

    private Set<String> normalized(Iterable<String> tables) {
        Set<String> result = new LinkedHashSet<>();
        for (String table : tables) result.add(text(table).toLowerCase(Locale.ROOT));
        return result;
    }

    private void collect(Operation operation, Set<String> inputs, Set<String> outputs) {
        if (!(operation instanceof ModifyOperation)) {
            throw new IllegalArgumentException("受管 SQL 的 INSERT 未解析为 Flink 写入操作");
        }
        if (!(operation instanceof SinkModifyOperation)) {
            throw new IllegalArgumentException("受管 SQL 暂不支持该 Flink 写入类型：" + operation.getClass().getSimpleName());
        }
        SinkModifyOperation sink = (SinkModifyOperation) operation;
        outputs.add(identifier(sink.getContextResolvedTable().getIdentifier()));
        collectSources(sink.getChild(), inputs);
    }

    private void collectSources(QueryOperation query, Set<String> inputs) {
        if (query instanceof PlannerQueryOperation) {
            collectCalciteSources(((PlannerQueryOperation) query).getCalciteTree(), inputs);
        }
        if (query instanceof SourceQueryOperation) {
            SourceQueryOperation source = (SourceQueryOperation) query;
            if (!source.getContextResolvedTable().isTemporary()) {
                inputs.add(identifier(source.getContextResolvedTable().getIdentifier()));
            }
        }
        for (QueryOperation child : query.getChildren()) collectSources(child, inputs);
    }

    private void collectCalciteSources(RelNode root, Set<String> inputs) {
        new RelVisitor() {
            @Override
            public void visit(RelNode node, int ordinal, RelNode parent) {
                if (node instanceof TableScan) {
                    List<String> parts = ((TableScan) node).getTable().getQualifiedName();
                    if (parts.size() >= 3) {
                        int size = parts.size();
                        inputs.add(parts.get(size - 3) + "." + parts.get(size - 2) + "." + parts.get(size - 1));
                    }
                }
                super.visit(node, ordinal, parent);
            }
        }.go(root);
    }

    private String identifier(ObjectIdentifier identifier) {
        return identifier.getCatalogName() + "." + identifier.getDatabaseName() + "." + identifier.getObjectName();
    }

    private String catalogDdl() {
        String warehouse = text(properties.getPaimonWarehouse());
        if (warehouse.isEmpty()) throw new IllegalStateException("未配置 app.realtime.paimon-warehouse");
        StringBuilder ddl = new StringBuilder("CREATE CATALOG paimon WITH ('type'='paimon','warehouse'='")
                .append(escape(warehouse)).append("'");
        properties.getCatalogConf().forEach((key, value) -> ddl.append(",'" )
                .append(escape(key)).append("'='").append(escape(value)).append("'"));
        return ddl.append(")").toString();
    }

    private void applySet(TableEnvironment table, String statement) {
        Matcher matcher = SET.matcher(statement.trim());
        if (!matcher.matches()) throw new IllegalArgumentException("SET 语法不正确：" + statement);
        String key = unescape(matcher.group(1) == null ? matcher.group(2) : matcher.group(1));
        String value = unescape(matcher.group(3) == null ? matcher.group(4) : matcher.group(3));
        table.getConfig().getConfiguration().setString(key, value);
    }

    private String unwrapStatementSet(String sql) {
        String value = sql.trim();
        String upper = value.toUpperCase(Locale.ROOT);
        String prefix = "EXECUTE STATEMENT SET BEGIN";
        if (upper.startsWith(prefix)) return value.substring(prefix.length()).trim();
        return "END".equals(upper) ? "" : value;
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return text(current.getMessage()).isEmpty() ? current.getClass().getSimpleName() : text(current.getMessage());
    }

    private String escape(String value) { return text(value).replace("'", "''"); }
    private String unescape(String value) { return value == null ? "" : value.replace("''", "'"); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }

    public static final class Analysis {
        private final List<String> inputs;
        private final List<String> outputs;
        private final int insertCount;
        private final int statementCount;
        private final String plan;
        private final java.util.Map<String, Object> lineageFacts;

        Analysis(List<String> inputs, List<String> outputs, int insertCount, int statementCount, String plan) {
            this(inputs, outputs, insertCount, statementCount, plan, java.util.Map.of());
        }

        Analysis(List<String> inputs, List<String> outputs, int insertCount, int statementCount, String plan,
                 java.util.Map<String, Object> lineageFacts) {
            this.inputs = inputs; this.outputs = outputs; this.insertCount = insertCount;
            this.statementCount = statementCount; this.plan = plan; this.lineageFacts = lineageFacts;
        }
        public List<String> getInputs() { return inputs; }
        public List<String> getOutputs() { return outputs; }
        public int getInsertCount() { return insertCount; }
        public int getStatementCount() { return statementCount; }
        public String getPlan() { return plan; }
        public java.util.Map<String, Object> getLineageFacts() { return lineageFacts; }
    }
}
