package com.yjn.sqlagent.parsesql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yjn.sqlagent.parsesql.antlr.SqlBaseLexer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Token;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * 全量只读语料回归。仅在显式提供 SQL_CORPUS_JDBC_URL 时执行，不打印 SQL 正文。
 */
class SqlCorpusRegressionTest {
    private static final int PARALLELISM = 8;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");
    private static final String DEFAULT_QUERY = "select t1.git_file from "
            + "(select action_id,git_file from sql_change_detail_info where git_file is not null and git_file!='') t1 "
            + "join (select action_id,exportType from action_desc where exportType in (4,67,110)) t2 "
            + "on t1.action_id=t2.action_id";

    @Test
    void parsesAllConfiguredActionSqlWithoutUnhandledException() throws Exception {
        String corpusUrl = setting("sql.corpus.jdbc.url", "SQL_CORPUS_JDBC_URL");
        Assumptions.assumeTrue(!corpusUrl.isEmpty(),
                "设置 SQL_CORPUS_JDBC_URL 后执行全量只读语料回归");

        String corpusUser = setting("sql.corpus.jdbc.user", "SQL_CORPUS_JDBC_USER");
        String corpusPassword = setting("sql.corpus.jdbc.password", "SQL_CORPUS_JDBC_PASSWORD");
        String query = setting("sql.corpus.query", "SQL_CORPUS_QUERY");
        if (query.isEmpty()) query = DEFAULT_QUERY;

        Map<String, Integer> categories = counters(
                "success", "partial", "invalid", "unsupported", "timeout", "unhandled");
        Map<String, Integer> diagnosticCodes = new LinkedHashMap<>();
        Map<String, Integer> syntaxMessages = new LinkedHashMap<>();
        Map<String, Integer> statementStarts = new LinkedHashMap<>();
        Map<String, Integer> syntaxShapes = new LinkedHashMap<>();
        Map<String, Integer> targetMismatchShapes = new LinkedHashMap<>();
        List<String> timeoutHashes = new ArrayList<>();
        List<String> unhandledHashes = new ArrayList<>();
        SqlLineageParser parser = new SqlLineageParser();
        ExecutorService parserWorkers = Executors.newCachedThreadPool(task -> {
            Thread worker = new Thread(task, "sql-corpus-parser");
            worker.setDaemon(true);
            return worker;
        });
        int processed = 0;
        List<ParseJob> pending = new ArrayList<>();

        try (CorpusMetadata metadata = CorpusMetadata.fromSettings();
             Connection connection = DriverManager.getConnection(corpusUrl, corpusUser, corpusPassword);
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(60);
            // MySQL Connector/J 的流式读取模式，避免一次把全部 SQL 装入堆内存。
            statement.setFetchSize(Integer.MIN_VALUE);
            try (ResultSet rows = statement.executeQuery(query)) {
                while (rows.next()) {
                    String sql = rows.getString(1);
                    if (sql == null || sql.trim().isEmpty()) continue;
                    recordStatementStarts(parser, sql, statementStarts);
                    Future<SqlScriptLineage> future = parserWorkers.submit(() -> parser.parseScript(
                            ParseRequest.builder(sql).dialect(SqlDialect.HIVE).metadataProvider(metadata)
                                    .mode(ParseMode.TOLERANT).build()));
                    pending.add(new ParseJob(sql, future));
                    if (pending.size() >= PARALLELISM) {
                        collect(pending.remove(0), categories, diagnosticCodes, syntaxMessages,
                                syntaxShapes, targetMismatchShapes, timeoutHashes, unhandledHashes);
                        processed = reportProgress(++processed, categories, diagnosticCodes, syntaxMessages,
                                statementStarts, syntaxShapes, targetMismatchShapes,
                                timeoutHashes, unhandledHashes);
                    }
                }
            }
            while (!pending.isEmpty()) {
                collect(pending.remove(0), categories, diagnosticCodes, syntaxMessages,
                        syntaxShapes, targetMismatchShapes, timeoutHashes, unhandledHashes);
                processed = reportProgress(++processed, categories, diagnosticCodes, syntaxMessages,
                        statementStarts, syntaxShapes, targetMismatchShapes,
                        timeoutHashes, unhandledHashes);
            }
        } finally {
            parserWorkers.shutdownNow();
        }

        writeReport(categories, diagnosticCodes, syntaxMessages, statementStarts,
                syntaxShapes, targetMismatchShapes, timeoutHashes, unhandledHashes);
        assertEquals(0, categories.get("timeout"), "存在解析超时，哈希见 target/sql-corpus-report.txt");
        assertEquals(0, categories.get("unhandled"), "存在未捕获异常，哈希见 target/sql-corpus-report.txt");
    }

    @Test
    void expandsAtLeastOneCorpusWildcardFromConfiguredMetastore() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(
                setting("sql.corpus.metastore.probe", "SQL_CORPUS_METASTORE_PROBE")));
        String corpusUrl = setting("sql.corpus.jdbc.url", "SQL_CORPUS_JDBC_URL");
        Assumptions.assumeTrue(!corpusUrl.isEmpty());
        boolean expanded = false;
        try (CorpusMetadata metadata = CorpusMetadata.fromSettings();
             Connection connection = DriverManager.getConnection(corpusUrl,
                     setting("sql.corpus.jdbc.user", "SQL_CORPUS_JDBC_USER"),
                     setting("sql.corpus.jdbc.password", "SQL_CORPUS_JDBC_PASSWORD"));
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(60);
            statement.setFetchSize(Integer.MIN_VALUE);
            try (ResultSet rows = statement.executeQuery(DEFAULT_QUERY)) {
                SqlLineageParser parser = new SqlLineageParser();
                while (rows.next() && !expanded) {
                    String sql = rows.getString(1);
                    if (sql == null || sql.indexOf('*') < 0) continue;
                    for (String part : parser.splitStatements(sql, SqlDialect.HIVE)) {
                        if (!containsToken(part, SqlBaseLexer.ASTERISK)) continue;
                        SqlScriptLineage result = parser.parseScript(ParseRequest.builder(part)
                                .dialect(SqlDialect.HIVE).metadataProvider(metadata)
                                .mode(ParseMode.TOLERANT).build());
                        boolean wildcardMissing = hasDiagnostic(result, "WILDCARD_NOT_EXPANDED");
                        int columns = result.getStatements().stream()
                                .mapToInt(item -> item.getColumnLineages().size()).sum();
                        if (!wildcardMissing && columns > 1) {
                            System.out.println("[parse-sql metastore probe] expanded-columns=" + columns);
                            expanded = true;
                            break;
                        }
                    }
                }
            }
        }
        assertTrue(expanded, "未找到可通过已配置 Hive Metastore 展开的 SELECT * 样本");
    }

    private boolean containsToken(String sql, int tokenType) {
        SqlBaseLexer lexer = new SqlBaseLexer(CharStreams.fromString(sql));
        for (Token token : lexer.getAllTokens()) {
            if (token.getChannel() == Token.DEFAULT_CHANNEL && token.getType() == tokenType) return true;
        }
        return false;
    }

    private void collect(ParseJob job, Map<String, Integer> categories,
                         Map<String, Integer> diagnosticCodes, Map<String, Integer> syntaxMessages,
                         Map<String, Integer> syntaxShapes, Map<String, Integer> targetMismatchShapes,
                         List<String> timeoutHashes, List<String> unhandledHashes) throws Exception {
        try {
            SqlScriptLineage lineage = job.future.get(job.timeoutSeconds, TimeUnit.SECONDS);
            classify(lineage, categories, diagnosticCodes, syntaxMessages);
            if (hasDiagnostic(lineage, "SYNTAX_ERROR")) {
                recordTokenShapes(job.sql, syntaxShapes, "SYNTAX_ERROR");
            }
            if (hasDiagnostic(lineage, "TARGET_COLUMN_COUNT_MISMATCH")) {
                recordTokenShapes(job.sql, targetMismatchShapes, "TARGET_COLUMN_COUNT_MISMATCH");
            }
        } catch (TimeoutException error) {
            job.future.cancel(true);
            increment(categories, "timeout");
            timeoutHashes.add(hash(job.sql));
        } catch (ExecutionException error) {
            increment(categories, "unhandled");
            unhandledHashes.add(hash(job.sql));
        } catch (InterruptedException error) {
            job.future.cancel(true);
            Thread.currentThread().interrupt();
            throw error;
        } catch (Throwable error) {
            increment(categories, "unhandled");
            unhandledHashes.add(hash(job.sql));
        }
    }

    private int reportProgress(int processed, Map<String, Integer> categories,
                               Map<String, Integer> diagnosticCodes, Map<String, Integer> syntaxMessages,
                               Map<String, Integer> statementStarts, Map<String, Integer> syntaxShapes,
                               Map<String, Integer> targetMismatchShapes, List<String> timeoutHashes,
                               List<String> unhandledHashes) throws Exception {
        if (processed % 100 == 0) {
            writeReport(categories, diagnosticCodes, syntaxMessages, statementStarts,
                    syntaxShapes, targetMismatchShapes, timeoutHashes, unhandledHashes);
            System.out.println("[parse-sql corpus] processed=" + processed);
        }
        return processed;
    }

    private void classify(SqlScriptLineage lineage, Map<String, Integer> categories,
                          Map<String, Integer> diagnosticCodes, Map<String, Integer> syntaxMessages) {
        boolean error = lineage.getDiagnostics().stream()
                .anyMatch(item -> item.getSeverity() == DiagnosticSeverity.ERROR);
        boolean unsupported = false;
        boolean partial = !lineage.getDiagnostics().isEmpty();
        for (LineageDiagnostic diagnostic : lineage.getDiagnostics()) {
            increment(diagnosticCodes, diagnostic.getCode());
            collectSyntaxMessage(syntaxMessages, diagnostic);
        }
        for (StatementLineage statement : lineage.getStatements()) {
            partial |= statement.isPartial();
            for (LineageDiagnostic diagnostic : statement.getDiagnostics()) {
                increment(diagnosticCodes, diagnostic.getCode());
                collectSyntaxMessage(syntaxMessages, diagnostic);
                unsupported |= "UNSUPPORTED_STATEMENT".equals(diagnostic.getCode());
                error |= diagnostic.getSeverity() == DiagnosticSeverity.ERROR;
            }
        }
        if (error) increment(categories, "invalid");
        else if (unsupported) increment(categories, "unsupported");
        else if (partial) increment(categories, "partial");
        else increment(categories, "success");
    }

    private void collectSyntaxMessage(Map<String, Integer> syntaxMessages, LineageDiagnostic diagnostic) {
        if (!"SYNTAX_ERROR".equals(diagnostic.getCode())) return;
        String message = diagnostic.getMessage();
        String category;
        if (message.contains("expecting {<EOF>, ';'}")) category = "TRAILING_TOKENS";
        else if (message.contains("no viable alternative")) category = "NO_VIABLE_ALTERNATIVE";
        else if (message.contains("extraneous input")) category = "EXTRANEOUS_TOKEN";
        else if (message.contains("missing ")) category = "MISSING_TOKEN";
        else if (message.contains("mismatched input")) category = "MISMATCHED_TOKEN";
        else category = "OTHER_SYNTAX_ERROR";
        increment(syntaxMessages, category);
    }

    private void writeReport(Map<String, Integer> categories, Map<String, Integer> diagnostics,
                             Map<String, Integer> syntaxMessages, Map<String, Integer> statementStarts,
                             Map<String, Integer> syntaxShapes, Map<String, Integer> targetMismatchShapes,
                             List<String> timeoutHashes, List<String> unhandledHashes) throws Exception {
        StringBuilder report = new StringBuilder("# parse-sql corpus regression\n");
        categories.forEach((key, value) -> report.append(key).append('=').append(value).append('\n'));
        diagnostics.forEach((key, value) -> report.append("diagnostic.").append(key).append('=').append(value).append('\n'));
        syntaxMessages.forEach((key, value) -> report.append("syntax.").append(key).append('=').append(value).append('\n'));
        statementStarts.forEach((key, value) -> report.append("statement-start.").append(key)
                .append('=').append(value).append('\n'));
        appendShapes(report, "syntax-shape", syntaxShapes);
        appendShapes(report, "target-mismatch-shape", targetMismatchShapes);
        for (String hash : timeoutHashes) report.append("timeout.hash=").append(hash).append('\n');
        for (String hash : unhandledHashes) report.append("unhandled.hash=").append(hash).append('\n');
        Path output = Path.of("target", "sql-corpus-report.txt");
        Files.createDirectories(output.getParent());
        Files.writeString(output, report.toString(), StandardCharsets.UTF_8);
    }

    private boolean hasDiagnostic(SqlScriptLineage lineage, String code) {
        if (lineage.getDiagnostics().stream().anyMatch(item -> code.equals(item.getCode()))) return true;
        return lineage.getStatements().stream().flatMap(item -> item.getDiagnostics().stream())
                .anyMatch(item -> code.equals(item.getCode()));
    }

    /** 仅记录词法类型，不记录标识符、字面量或 SQL 正文。 */
    private void recordTokenShapes(String sql, Map<String, Integer> shapes, String diagnosticCode) {
        SqlLineageParser parser = new SqlLineageParser();
        for (String statement : parser.splitStatements(sql, SqlDialect.HIVE)) {
            SqlScriptLineage statementResult = parser.parseScript(ParseRequest.builder(statement)
                    .dialect(SqlDialect.HIVE).mode(ParseMode.TOLERANT).build());
            if (!hasDiagnostic(statementResult, diagnosticCode)) continue;
            SqlBaseLexer lexer = new SqlBaseLexer(CharStreams.fromString(statement));
            List<String> types = new ArrayList<>();
            Token token;
            while ((token = lexer.nextToken()).getType() != Token.EOF && types.size() < 48) {
                if (token.getChannel() != Token.DEFAULT_CHANNEL) continue;
                String name = SqlBaseLexer.VOCABULARY.getSymbolicName(token.getType());
                types.add(name == null ? "TOKEN_" + token.getType() : name);
            }
            if (types.isEmpty()) continue;
            String first = types.get(0);
            boolean lineageStatement = "SELECT".equals(first) || "WITH".equals(first)
                    || "INSERT".equals(first) || "REPLACE".equals(first)
                    || "UPDATE".equals(first) || "DELETE".equals(first) || "CREATE".equals(first);
            if (!lineageStatement) continue;
            String shape = String.join(",", types);
            if ("SYNTAX_ERROR".equals(diagnosticCode)) {
                LineageDiagnostic diagnostic = firstDiagnostic(statementResult, diagnosticCode);
                shape += "@" + tokenWindow(statement, diagnostic == null ? -1 : diagnostic.getStartOffset());
            }
            if (shapes.size() < 200 || shapes.containsKey(shape)) increment(shapes, shape);
            else increment(shapes, "OTHER_SHAPE");
        }
    }

    private LineageDiagnostic firstDiagnostic(SqlScriptLineage lineage, String code) {
        for (LineageDiagnostic item : lineage.getDiagnostics()) if (code.equals(item.getCode())) return item;
        for (StatementLineage statement : lineage.getStatements()) {
            for (LineageDiagnostic item : statement.getDiagnostics()) if (code.equals(item.getCode())) return item;
        }
        return null;
    }

    private String tokenWindow(String sql, int offset) {
        return tokenWindow(sql, offset, 8);
    }

    private String tokenWindow(String sql, int offset, int radius) {
        SqlBaseLexer lexer = new SqlBaseLexer(CharStreams.fromString(sql));
        List<String> names = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        Token token;
        while ((token = lexer.nextToken()).getType() != Token.EOF) {
            if (token.getChannel() != Token.DEFAULT_CHANNEL) continue;
            String name = SqlBaseLexer.VOCABULARY.getSymbolicName(token.getType());
            names.add(name == null ? "TOKEN_" + token.getType() : name);
            starts.add(token.getStartIndex());
        }
        if (names.isEmpty()) return "EOF";
        int hit = names.size() - 1;
        for (int index = 0; index < starts.size(); index++) {
            if (starts.get(index) >= offset) { hit = index; break; }
        }
        List<String> window = new ArrayList<>();
        for (int index = Math.max(0, hit - radius);
             index <= Math.min(names.size() - 1, hit + radius); index++) {
            window.add(index == hit ? "!" + names.get(index) : names.get(index));
        }
        return String.join(",", window);
    }

    private void appendShapes(StringBuilder report, String prefix, Map<String, Integer> shapes) {
        shapes.entrySet().stream()
                .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                .limit(40)
                .forEach(item -> report.append(prefix).append('.').append(item.getValue())
                        .append('=').append(item.getKey()).append('\n'));
    }

    private String hash(String sql) throws Exception {
        byte[] value = MessageDigest.getInstance("SHA-256").digest(sql.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte item : value) result.append(String.format("%02x", item));
        return result.toString();
    }

    private static Map<String, Integer> counters(String... names) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String name : names) result.put(name, 0);
        return result;
    }

    private static void increment(Map<String, Integer> values, String key) {
        values.put(key, values.getOrDefault(key, 0) + 1);
    }

    private void recordStatementStarts(SqlLineageParser parser, String sql, Map<String, Integer> starts) {
        try {
            for (String statement : parser.splitStatements(sql, SqlDialect.HIVE)) {
                SqlBaseLexer lexer = new SqlBaseLexer(CharStreams.fromString(statement));
                Token token;
                do token = lexer.nextToken(); while (token.getChannel() != Token.DEFAULT_CHANNEL
                        && token.getType() != Token.EOF);
                String name = token.getType() == Token.EOF ? "EOF"
                        : SqlBaseLexer.VOCABULARY.getSymbolicName(token.getType());
                increment(starts, name == null ? "TOKEN_" + token.getType() : name);
            }
        } catch (RuntimeException error) {
            increment(starts, "LEXER_ERROR");
        }
    }

    private static String setting(String property, String environment) {
        String value = System.getProperty(property);
        if (value == null || value.trim().isEmpty()) value = System.getenv(environment);
        return value == null ? "" : value.trim();
    }

    private static final class ParseJob {
        private final String sql;
        private final Future<SqlScriptLineage> future;
        private final int timeoutSeconds;

        private ParseJob(String sql, Future<SqlScriptLineage> future) {
            this.sql = sql;
            this.future = future;
            this.timeoutSeconds = Math.max(10, Math.min(60, sql.length() / 5_000));
        }
    }

    private static final class CorpusMetadata implements TableMetadataProvider, AutoCloseable {
        private final Connection connection;
        private final Object metastoreClient;
        private final Map<String, Optional<TableSchema>> cache = new LinkedHashMap<>();

        private CorpusMetadata(Connection connection, Object metastoreClient) {
            this.connection = connection;
            this.metastoreClient = metastoreClient;
        }

        private static CorpusMetadata fromSettings() throws Exception {
            String url = setting("sql.corpus.hive.jdbc.url", "SQL_CORPUS_HIVE_JDBC_URL");
            if (!url.isEmpty()) {
                return new CorpusMetadata(DriverManager.getConnection(url,
                        setting("sql.corpus.hive.jdbc.user", "SQL_CORPUS_HIVE_JDBC_USER"),
                        setting("sql.corpus.hive.jdbc.password", "SQL_CORPUS_HIVE_JDBC_PASSWORD")), null);
            }
            String metastoreUris = setting("sql.corpus.hive.metastore.uris", "SQL_CORPUS_HIVE_METASTORE_URIS");
            if (metastoreUris.isEmpty()) return new CorpusMetadata(null, null);
            Class<?> hiveConfType = Class.forName("org.apache.hadoop.hive.conf.HiveConf");
            Object hiveConf = hiveConfType.getConstructor().newInstance();
            hiveConfType.getMethod("set", String.class, String.class)
                    .invoke(hiveConf, "hive.metastore.uris", metastoreUris);
            Class<?> clientType = Class.forName("org.apache.hadoop.hive.metastore.HiveMetaStoreClient");
            Class<?> configurationType = Class.forName("org.apache.hadoop.conf.Configuration");
            Object client = clientType.getConstructor(configurationType).newInstance(hiveConf);
            return new CorpusMetadata(null, client);
        }

        @Override
        public synchronized Optional<TableSchema> getTable(TableIdentifier table) {
            String key = table.normalizedName();
            Optional<TableSchema> cached = cache.get(key);
            if (cached != null) return cached;
            Optional<TableSchema> loaded = loadTable(table);
            cache.put(key, loaded);
            return loaded;
        }

        private Optional<TableSchema> loadTable(TableIdentifier table) {
            if (!safe(table.getDatabase()) || !safe(table.getTable())) return Optional.empty();
            if (metastoreClient != null) return loadMetastoreTable(table);
            if (connection == null) return Optional.empty();
            List<TableColumnMetadata> columns = new ArrayList<>();
            boolean partition = false;
            String sql = "DESCRIBE `" + table.getDatabase() + "`.`" + table.getTable() + "`";
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(10);
                try (ResultSet rows = statement.executeQuery(sql)) {
                    while (rows.next()) {
                        String name = text(rows.getString(1));
                        if (name.startsWith("# Partition Information")) { partition = true; continue; }
                        if (!safe(name)) continue;
                        columns.add(new TableColumnMetadata(name, text(rows.getString(2)), partition));
                    }
                }
                return columns.isEmpty() ? Optional.empty() : Optional.of(new TableSchema(table, columns));
            } catch (Exception error) {
                return Optional.empty();
            }
        }

        private Optional<TableSchema> loadMetastoreTable(TableIdentifier table) {
            try {
                Object hiveTable = metastoreClient.getClass().getMethod("getTable", String.class, String.class)
                        .invoke(metastoreClient, table.getDatabase(), table.getTable());
                List<TableColumnMetadata> columns = new ArrayList<>();
                Object storage = hiveTable.getClass().getMethod("getSd").invoke(hiveTable);
                appendMetastoreColumns(columns, storage.getClass().getMethod("getCols").invoke(storage), false);
                appendMetastoreColumns(columns,
                        hiveTable.getClass().getMethod("getPartitionKeys").invoke(hiveTable), true);
                return columns.isEmpty() ? Optional.empty() : Optional.of(new TableSchema(table, columns));
            } catch (Exception error) {
                return Optional.empty();
            }
        }

        private void appendMetastoreColumns(List<TableColumnMetadata> target, Object value, boolean partition)
                throws Exception {
            if (!(value instanceof Iterable)) return;
            for (Object field : (Iterable<?>) value) {
                Method name = field.getClass().getMethod("getName");
                Method type = field.getClass().getMethod("getType");
                target.add(new TableColumnMetadata(String.valueOf(name.invoke(field)),
                        String.valueOf(type.invoke(field)), partition));
            }
        }

        @Override
        public void close() throws Exception {
            if (connection != null) connection.close();
            if (metastoreClient != null) metastoreClient.getClass().getMethod("close").invoke(metastoreClient);
        }
        private static boolean safe(String value) { return value != null && IDENTIFIER.matcher(value).matches(); }
        private static String text(String value) { return value == null ? "" : value.trim(); }
    }
}
