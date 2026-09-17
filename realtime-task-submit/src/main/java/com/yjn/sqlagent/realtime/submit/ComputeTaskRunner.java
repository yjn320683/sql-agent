package com.yjn.sqlagent.realtime.submit;

import com.yjn.sqlagent.realtime.common.SubmissionSpec;
import com.yjn.sqlagent.realtime.common.DebugReport;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.flink.table.api.StatementSet;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;

final class ComputeTaskRunner implements TaskRunner {
    private static final Pattern SET = Pattern.compile(
            "(?is)^SET\\s+(?:'((?:''|[^'])+)'|([A-Za-z0-9_.-]+))\\s*=\\s*(?:'((?:''|[^'])*)'|([^\\s]+))$");

    @Override
    public DebugReport validate(SubmissionSpec spec) throws Exception {
        return build(spec, false);
    }

    @Override
    public void execute(SubmissionSpec spec) throws Exception {
        build(spec, true);
    }

    private DebugReport build(SubmissionSpec spec, boolean execute) throws Exception {
        Map<String, Object> compute = FlinkSqlSupport.map(spec.getTask().getTaskConfig().get("computeConfig"));
        String database = FlinkSqlSupport.text(compute.get("defaultDatabase"));
        String sql = FlinkSqlSupport.text(compute.get("sql"));
        if (database.isEmpty() || sql.isEmpty()) {
            throw new IllegalArgumentException("计算任务缺少默认数据库或 SQL");
        }

        StreamTableEnvironment table = FlinkSqlSupport.environment(spec);
        table.useDatabase(database);
        StatementSet inserts = table.createStatementSet();
        int count = 0;
        DebugReport report = new DebugReport();
        report.setTaskType("compute");
        List<String> statements = FlinkSqlSupport.statements(sql);
        for (String raw : statements) {
            String statement = unwrapStatementSet(raw);
            if (statement.isEmpty()) continue;
            String upper = statement.trim().toUpperCase(Locale.ROOT);
            if (upper.startsWith("INSERT ")) {
                inserts.addInsertSql(statement);
                count++;
            } else if (upper.startsWith("SET ")) {
                applySet(table, statement);
            } else if (upper.startsWith("CREATE TEMPORARY VIEW ")) {
                table.executeSql(statement);
            } else {
                throw new IllegalArgumentException("提交器拒绝非受管 SQL：" + first(upper));
            }
        }
        if (count == 0) throw new IllegalArgumentException("计算任务至少需要一个 INSERT");
        if (execute) {
            inserts.execute();
            return report;
        }
        String plan = inserts.explain();
        report.setLogicalPlan(plan);
        report.setPhysicalPlan(plan);
        applyManagedTableReferences(spec, report);
        report.check("FLINK_SQL_PARSED", database, "Flink Parser/Planner 已解析全部受管 SQL")
                .check("STATEMENT_SET_PLANNED", String.valueOf(count), "已生成 " + count + " 路 INSERT 执行计划");
        report.setSummary("计算 SQL、Paimon Catalog 与 " + count + " 路执行计划校验通过，未执行写入");
        return report;
    }

    private void applyManagedTableReferences(SubmissionSpec spec, DebugReport report) {
        List<String> inputs = new ArrayList<>();
        List<String> outputs = new ArrayList<>();
        for (Map<String, Object> reference : FlinkSqlSupport.maps(
                spec.getTask().getTaskConfig().get("_tableReferences"))) {
            String table = FlinkSqlSupport.text(reference.get("databaseName")) + "."
                    + FlinkSqlSupport.text(reference.get("tableName"));
            if (table.startsWith(".") || table.endsWith(".")) continue;
            String role = FlinkSqlSupport.text(reference.get("referenceRole"));
            if ("INPUT".equalsIgnoreCase(role)) inputs.add(table);
            else if ("OUTPUT".equalsIgnoreCase(role)) outputs.add(table);
        }
        report.setInputs(inputs);
        report.setOutputs(outputs);
    }

    private void applySet(StreamTableEnvironment table, String statement) {
        Matcher matcher = SET.matcher(statement.trim());
        if (!matcher.matches()) throw new IllegalArgumentException("SET 语法不正确：" + statement);
        String key = unescape(matcher.group(1) == null ? matcher.group(2) : matcher.group(1));
        String value = unescape(matcher.group(3) == null ? matcher.group(4) : matcher.group(3));
        table.getConfig().getConfiguration().setString(key, value);
    }

    private String unescape(String value) {
        return value == null ? "" : value.replace("''", "'");
    }

    private String unwrapStatementSet(String sql){String value=sql.trim();String upper=value.toUpperCase(Locale.ROOT);String prefix="EXECUTE STATEMENT SET BEGIN";if(upper.startsWith(prefix))return value.substring(prefix.length()).trim();if("END".equals(upper))return "";return value;}
    private String first(String sql){int i=sql.indexOf(' ');return i<0?sql:sql.substring(0,i);}
}
