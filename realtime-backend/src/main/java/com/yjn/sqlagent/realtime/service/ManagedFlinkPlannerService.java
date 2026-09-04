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
        policy.analyze(sql, defaultDatabase);
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
            for (String raw : statements(sql)) {
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
            String plan = includeExplain ? statementSet.explain() : "";
            return new Analysis(new ArrayList<>(inputs), new ArrayList<>(outputs), insertCount, plan);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Flink SQL 解析或执行计划校验失败：" + rootMessage(ex), ex);
        }
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

    private List<String> statements(String sql) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean single = false, quoted = false, backtick = false, line = false, block = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i), next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (line) { if (c == '\n') { line = false; current.append(c); } continue; }
            if (block) { if (c == '*' && next == '/') { block = false; i++; } continue; }
            if (!single && !quoted && !backtick && c == '-' && next == '-') { line = true; i++; continue; }
            if (!single && !quoted && !backtick && c == '/' && next == '*') { block = true; i++; continue; }
            if (c == '\'' && !quoted && !backtick) single = !single;
            if (c == '"' && !single && !backtick) quoted = !quoted;
            if (c == '`' && !single && !quoted) backtick = !backtick;
            if (c == ';' && !single && !quoted && !backtick) {
                add(result, current); current = new StringBuilder();
            } else current.append(c);
        }
        add(result, current);
        return result;
    }

    private void add(List<String> result, StringBuilder value) {
        String statement = value.toString().trim();
        if (!statement.isEmpty()) result.add(statement);
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
        private final String plan;

        Analysis(List<String> inputs, List<String> outputs, int insertCount, String plan) {
            this.inputs = inputs; this.outputs = outputs; this.insertCount = insertCount; this.plan = plan;
        }
        public List<String> getInputs() { return inputs; }
        public List<String> getOutputs() { return outputs; }
        public int getInsertCount() { return insertCount; }
        public String getPlan() { return plan; }
    }
}
