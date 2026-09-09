package com.yjn.sqlagent.parsesql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * 使用数开真实 SQL 对比三套解析器共同具备的表级血缘能力。
 * 报告不保存 SQL 正文，只保留任务 ID 和各解析器的归一化结果。
 */
class CrossParserTableLineageCorpusTest {
    private static final String DEFAULT_QUERY = "select t1.action_id,t1.git_file from "
            + "(select id,action_id,git_file from sql_change_detail_info "
            + "where git_file is not null and git_file!='') t1 "
            + "join (select action_id,max(id) max_id from sql_change_detail_info "
            + "where is_online=1 group by action_id) latest on t1.id=latest.max_id "
            + "join (select action_id from action_desc where exportType in (4,67,110) and progress=1) enabled "
            + "on t1.action_id=enabled.action_id";
    private static final int PARSER_TIMEOUT_SECONDS = 8;

    @Test
    void comparesCommonReadAndWriteTablesForConfiguredCorpus() throws Exception {
        String corpusUrl = setting("sql.corpus.jdbc.url", "SQL_CORPUS_JDBC_URL");
        Assumptions.assumeTrue(!corpusUrl.isEmpty(), "配置 SQL_CORPUS_JDBC_URL 后执行三方语料对比");

        Path antlrClasses = Path.of(settingOrDefault("sql.reference.antlr.classes",
                "SQL_REFERENCE_ANTLR_CLASSES", "/private/tmp/antlr4study-classes"));
        Path antlrClasspath = Path.of(settingOrDefault("sql.reference.antlr.classpath",
                "SQL_REFERENCE_ANTLR_CLASSPATH", "/private/tmp/antlr4study.cp"));
        Path relationClasses = Path.of(settingOrDefault("sql.reference.relation.classes",
                "SQL_REFERENCE_RELATION_CLASSES",
                "/opt/project/gs/parse-sql-relation/target/classes"));
        Path relationClasspath = Path.of(settingOrDefault("sql.reference.relation.classpath",
                "SQL_REFERENCE_RELATION_CLASSPATH", "/private/tmp/parse-sql-relation.cp"));
        Assumptions.assumeTrue(Files.isDirectory(antlrClasses) && Files.isRegularFile(antlrClasspath),
                "先构建 Antlr4Study 解析包及 classpath");
        Assumptions.assumeTrue(Files.isDirectory(relationClasses) && Files.isRegularFile(relationClasspath),
                "先构建 parse-sql-relation 及 classpath");

        SqlLineageParser current = new SqlLineageParser();
        Map<String, Integer> counters = new LinkedHashMap<>();
        List<Mismatch> mismatches = new ArrayList<>();
        ExecutorService timeoutPool = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "cross-parser-corpus");
            thread.setDaemon(true);
            return thread;
        });

        try (ReferenceParser antlr = ReferenceParser.antlr(antlrClasses, antlrClasspath);
             ReferenceParser relation = ReferenceParser.relation(relationClasses, relationClasspath);
             Connection connection = DriverManager.getConnection(corpusUrl,
                     setting("sql.corpus.jdbc.user", "SQL_CORPUS_JDBC_USER"),
                     setting("sql.corpus.jdbc.password", "SQL_CORPUS_JDBC_PASSWORD"));
             Statement query = connection.createStatement()) {
            query.setQueryTimeout(60);
            query.setFetchSize(Integer.MIN_VALUE);
            String corpusQuery = settingOrDefault("sql.corpus.comparison.query",
                    "SQL_CORPUS_COMPARISON_QUERY", DEFAULT_QUERY);
            try (ResultSet rows = query.executeQuery(corpusQuery)) {
                while (rows.next()) {
                    String taskId = rows.getString(1);
                    String script = rows.getString(2);
                    if (script == null || script.trim().isEmpty()) continue;
                    increment(counters, "tasks");
                    int statementIndex = 0;
                    for (String sql : current.splitStatements(script, SqlDialect.HIVE)) {
                        if (sql.trim().isEmpty()) continue;
                        statementIndex++;
                        increment(counters, "statements");
                        ParserResult currentResult = parseCurrent(current, sql);
                        if (!currentResult.comparable) {
                            increment(counters, "NOT_COMMON_STATEMENT");
                            continue;
                        }
                        increment(counters, "compared");
                        Future<ParserResult> antlrFuture = timeoutPool.submit(() -> antlr.parse(sql));
                        Future<ParserResult> relationFuture = timeoutPool.submit(() -> relation.parse(sql));
                        ParserResult antlrResult = timed(antlrFuture);
                        ParserResult relationResult = timed(relationFuture);
                        Comparison comparison = compare(currentResult, antlrResult, relationResult);
                        increment(counters, comparison.category);
                        if (!comparison.agreement) {
                            mismatches.add(new Mismatch(taskId, statementIndex, comparison.decision,
                                    currentResult, antlrResult, relationResult));
                        }
                    }
                    if (counters.get("tasks") % 100 == 0) {
                        writeReport(counters, mismatches);
                        System.out.println("[parse-sql comparison] tasks=" + counters.get("tasks")
                                + ", mismatches=" + mismatches.size());
                    }
                }
            }
        } finally {
            timeoutPool.shutdownNow();
        }

        writeReport(counters, mismatches);
        assertEquals(0, counters.getOrDefault("CURRENT_EXCEPTION", 0),
                "当前解析器存在未捕获异常，任务 ID 见 target/sql-table-lineage-comparison.txt");
    }

    private ParserResult parseCurrent(SqlLineageParser parser, String sql) {
        try {
            SqlScriptLineage script = parser.parseScript(ParseRequest.builder(sql)
                    .dialect(SqlDialect.HIVE).mode(ParseMode.TOLERANT).build());
            Set<String> reads = new TreeSet<>();
            Set<String> writes = new TreeSet<>();
            List<String> diagnostics = new ArrayList<>();
            boolean comparable = false;
            for (StatementLineage statement : script.getStatements()) {
                comparable |= isCommonStatement(statement.getStatementType());
                // 对比解析能力时保留动态表名占位符；业务 API 的 input/output 列表会有意排除它们。
                for (TableAccess access : statement.getTableAccesses()) {
                    String table = normalize(access.getTable().qualifiedName());
                    if (access.getRole() == TableAccessRole.READ
                            || access.getRole() == TableAccessRole.READ_WRITE) reads.add(table);
                    if (access.getRole() == TableAccessRole.WRITE
                            || access.getRole() == TableAccessRole.READ_WRITE) writes.add(table);
                }
                statement.getDiagnostics().forEach(item -> diagnostics.add(item.getCode()));
            }
            script.getDiagnostics().forEach(item -> diagnostics.add(item.getCode()));
            return ParserResult.success(reads, writes, diagnostics, comparable);
        } catch (Throwable error) {
            return ParserResult.failure("CURRENT_EXCEPTION", error);
        }
    }

    private boolean isCommonStatement(SqlStatementType type) {
        return type == SqlStatementType.SELECT || type == SqlStatementType.WITH
                || type == SqlStatementType.INSERT || type == SqlStatementType.REPLACE
                || type == SqlStatementType.CREATE_TABLE_AS_SELECT
                || type == SqlStatementType.UPDATE || type == SqlStatementType.DELETE;
    }

    private ParserResult timed(Future<ParserResult> future) {
        try {
            return future.get(PARSER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException error) {
            future.cancel(true);
            return ParserResult.failure("TIMEOUT", error);
        } catch (ExecutionException error) {
            return ParserResult.failure("EXCEPTION", error.getCause());
        } catch (InterruptedException error) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return ParserResult.failure("INTERRUPTED", error);
        }
    }

    private Comparison compare(ParserResult current, ParserResult antlr, ParserResult relation) {
        if (!current.success) return new Comparison(false, "CURRENT_EXCEPTION", "CURRENT_SUSPECT");
        if (!antlr.success && !relation.success) {
            return new Comparison(false, "BOTH_REFERENCES_UNSUPPORTED",
                    "NEEDS_REVIEW_REFERENCE_UNSUPPORTED");
        }
        boolean currentAntlr = current.sameTables(antlr);
        boolean currentRelation = current.sameTables(relation);
        boolean currentAntlrCompatible = current.placeholderCompatible(antlr);
        boolean currentRelationCompatible = current.placeholderCompatible(relation);
        boolean referencesAgree = antlr.sameTables(relation);
        if (antlr.success && relation.success && currentAntlr && currentRelation) {
            return new Comparison(true, "ALL_AGREE", "AGREE");
        }
        if (antlr.success && relation.success && currentAntlrCompatible && currentRelationCompatible) {
            return new Comparison(false, "REFERENCE_PLACEHOLDER_TRUNCATION", "CURRENT_CONFIRMED");
        }
        if (!antlr.success && currentRelationCompatible) {
            return new Comparison(false, "ANTLR_UNSUPPORTED", "REFERENCE_UNSUPPORTED");
        }
        if (!relation.success && currentAntlrCompatible) {
            return new Comparison(false, "RELATION_UNSUPPORTED", "REFERENCE_UNSUPPORTED");
        }
        if ((currentAntlr && !currentRelation) || (currentRelation && !currentAntlr)) {
            return new Comparison(false, "ONE_REFERENCE_DIFFERS", "REFERENCE_DIVERGENCE");
        }
        if (antlr.success && relation.success && referencesAgree) {
            return new Comparison(false, "REFERENCES_AGREE_CURRENT_DIFFERS", "CURRENT_SUSPECT");
        }
        return new Comparison(false, "THREE_WAY_DIFFERENCE", "NEEDS_REVIEW");
    }

    private void writeReport(Map<String, Integer> counters, List<Mismatch> mismatches) throws Exception {
        StringBuilder report = new StringBuilder("# parse-sql common table-lineage comparison\n");
        report.append("# SQL bodies and credentials are intentionally omitted.\n\n");
        counters.forEach((key, value) -> report.append(key).append('=').append(value).append('\n'));
        report.append("mismatch.count=").append(mismatches.size()).append("\n\n");
        for (Mismatch mismatch : mismatches) {
            report.append("taskId=").append(safe(mismatch.taskId)).append('\n');
            report.append("statementIndex=").append(mismatch.statementIndex).append('\n');
            report.append("decision=").append(mismatch.decision).append('\n');
            appendResult(report, "current", mismatch.current);
            appendResult(report, "antlr4Study", mismatch.antlr);
            appendResult(report, "parseSqlRelation", mismatch.relation);
            report.append('\n');
        }
        Path output = Path.of("target", "sql-table-lineage-comparison.txt");
        Files.createDirectories(output.getParent());
        Files.writeString(output, report.toString(), StandardCharsets.UTF_8);
    }

    private void appendResult(StringBuilder report, String name, ParserResult result) {
        report.append(name).append(".status=").append(result.status).append('\n');
        report.append(name).append(".read=").append(result.reads).append('\n');
        report.append(name).append(".write=").append(result.writes).append('\n');
        if (!result.diagnostics.isEmpty()) {
            report.append(name).append(".diagnostics=").append(result.diagnostics).append('\n');
        }
    }

    private static String setting(String property, String environment) {
        String value = System.getProperty(property);
        if (value == null || value.trim().isEmpty()) value = System.getenv(environment);
        return value == null ? "" : value.trim();
    }

    private static String settingOrDefault(String property, String environment, String fallback) {
        String value = setting(property, environment);
        return value.isEmpty() ? fallback : value;
    }

    private static String normalize(String table) {
        if (table == null) return "";
        return table.replace("`", "").replace("\"", "")
                .replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static String safe(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n=]", "_");
    }

    private static void increment(Map<String, Integer> counters, String key) {
        counters.put(key, counters.getOrDefault(key, 0) + 1);
    }

    private static URL[] urls(Path classes, Path classpathFile) throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(classes.toUri().toURL());
        String classpath = Files.readString(classpathFile, StandardCharsets.UTF_8).trim();
        if (!classpath.isEmpty()) {
            for (String item : classpath.split(java.io.File.pathSeparator)) {
                if (!item.isEmpty()) urls.add(Path.of(item).toUri().toURL());
            }
        }
        return urls.toArray(new URL[0]);
    }

    private interface ReferenceParser extends AutoCloseable {
        ParserResult parse(String sql) throws Exception;

        static ReferenceParser antlr(Path classes, Path classpath) throws Exception {
            URLClassLoader loader = new URLClassLoader(urls(classes, classpath), ClassLoader.getPlatformClassLoader());
            Class<?> utility = loader.loadClass("com.yjn.parseTrinoSql.parse.ParseSqlAntlrUtil");
            Method parse = utility.getMethod("parseSql", String.class, boolean.class);
            return new ReferenceParser() {
                @Override public ParserResult parse(String sql) throws Exception {
                    Object listener = parse.invoke(null, sql, false);
                    if (listener == null) return ParserResult.failure("UNSUPPORTED", null);
                    Set<String> reads = new TreeSet<>();
                    Set<String> writes = new TreeSet<>();
                    Object inputMap = listener.getClass().getMethod("getInputTblMap").invoke(listener);
                    if (inputMap instanceof Map) {
                        for (Object key : ((Map<?, ?>) inputMap).keySet()) reads.add(normalize(String.valueOf(key)));
                    }
                    Object output = listener.getClass().getMethod("getOutPutTable").invoke(listener);
                    if (output != null) {
                        Object name = output.getClass().getMethod("getFullQualified").invoke(output);
                        if (name != null) writes.add(normalize(String.valueOf(name)));
                    }
                    return ParserResult.success(reads, writes, Collections.emptyList(), true);
                }
                @Override public void close() throws Exception { loader.close(); }
            };
        }

        static ReferenceParser relation(Path classes, Path classpath) throws Exception {
            URLClassLoader loader = new URLClassLoader(urls(classes, classpath), ClassLoader.getPlatformClassLoader());
            Class<?> parserClass = loader.loadClass("com.ahs.sql.relation.ParseSqlRelation");
            Object parser = parserClass.getConstructor().newInstance();
            Method parse = parserClass.getMethod("parseSql", String.class);
            return new ReferenceParser() {
                @Override public ParserResult parse(String sql) throws Exception {
                    Object finder = parse.invoke(parser, sql);
                    Set<String> reads = new TreeSet<>();
                    Set<String> writes = new TreeSet<>();
                    Object tables = finder.getClass().getMethod("getRelationTables").invoke(finder);
                    if (tables instanceof Collection) {
                        for (Object table : (Collection<?>) tables) reads.add(normalize(String.valueOf(table)));
                    }
                    Object tableName = finder.getClass().getMethod("getTableName").invoke(finder);
                    Object databaseName = finder.getClass().getMethod("getDatabaseName").invoke(finder);
                    if (tableName != null && !String.valueOf(tableName).isEmpty()) {
                        String name = databaseName == null || String.valueOf(databaseName).isEmpty()
                                ? String.valueOf(tableName)
                                : databaseName + "." + tableName;
                        writes.add(normalize(name));
                    }
                    return ParserResult.success(reads, writes, Collections.emptyList(), true);
                }
                @Override public void close() throws Exception { loader.close(); }
            };
        }
    }

    private static final class ParserResult {
        final boolean success;
        final boolean comparable;
        final String status;
        final Set<String> reads;
        final Set<String> writes;
        final List<String> diagnostics;

        private ParserResult(boolean success, boolean comparable, String status, Set<String> reads,
                             Set<String> writes, List<String> diagnostics) {
            this.success = success;
            this.comparable = comparable;
            this.status = status;
            this.reads = Collections.unmodifiableSet(new LinkedHashSet<>(reads));
            this.writes = Collections.unmodifiableSet(new LinkedHashSet<>(writes));
            this.diagnostics = Collections.unmodifiableList(new ArrayList<>(diagnostics));
        }

        static ParserResult success(Set<String> reads, Set<String> writes, List<String> diagnostics,
                                    boolean comparable) {
            return new ParserResult(true, comparable, diagnostics.isEmpty() ? "OK" : "PARTIAL",
                    reads, writes, diagnostics);
        }

        static ParserResult failure(String status, Throwable error) {
            Throwable root = error;
            while ((root instanceof InvocationTargetException || root instanceof ExecutionException)
                    && root.getCause() != null) root = root.getCause();
            String detail = root == null ? status : root.getClass().getSimpleName();
            return new ParserResult(false, true, status, Collections.emptySet(), Collections.emptySet(),
                    Collections.singletonList(detail));
        }

        boolean sameTables(ParserResult other) {
            return other.success && reads.equals(other.reads) && writes.equals(other.writes);
        }

        boolean placeholderCompatible(ParserResult other) {
            return other.success && compatible(reads, other.reads) && compatible(writes, other.writes);
        }

        private boolean compatible(Set<String> current, Set<String> reference) {
            if (current.equals(reference)) return true;
            if (current.size() != reference.size()) return false;
            List<String> unmatched = new ArrayList<>(reference);
            for (String value : current) {
                int match = -1;
                for (int index = 0; index < unmatched.size(); index++) {
                    String candidate = unmatched.get(index);
                    if (value.equals(candidate)
                            || ((value.contains("${") || value.contains("#{")) && value.startsWith(candidate))) {
                        match = index;
                        break;
                    }
                }
                if (match < 0) return false;
                unmatched.remove(match);
            }
            return unmatched.isEmpty();
        }
    }

    private static final class Comparison {
        final boolean agreement;
        final String category;
        final String decision;
        Comparison(boolean agreement, String category, String decision) {
            this.agreement = agreement; this.category = category; this.decision = decision;
        }
    }

    private static final class Mismatch {
        final String taskId;
        final int statementIndex;
        final String decision;
        final ParserResult current;
        final ParserResult antlr;
        final ParserResult relation;
        Mismatch(String taskId, int statementIndex, String decision, ParserResult current,
                 ParserResult antlr, ParserResult relation) {
            this.taskId = taskId; this.statementIndex = statementIndex; this.decision = decision;
            this.current = current; this.antlr = antlr; this.relation = relation;
        }
    }
}
