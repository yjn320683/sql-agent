package com.yjn.sqlagent.parsesql;

import com.yjn.sqlagent.parsesql.antlr.SqlBaseLexer;
import com.yjn.sqlagent.parsesql.antlr.SqlBaseParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.Optional;
import java.util.LinkedHashSet;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.Lexer;

/** SQL 血缘统一入口。实例无状态，可安全复用。 */
public final class SqlLineageParser {
    private static final Pattern SAFE_REPLACEMENT = Pattern.compile(
            "(?:`(?:``|[^`])+`|[A-Za-z0-9_$]+)(?:\\.(?:`(?:``|[^`])+`|[A-Za-z0-9_$]+))*");
    private static final Pattern STEP_LINE = Pattern.compile("(?im)^\\s*====\\s*step\\s*:.*?====\\s*$");
    private static final Pattern ENGINE_LINE = Pattern.compile(
            "(?im)^\\s*set\\s+(?:global\\s+)?engine\\s*=.*?(?:;\\s*)?$");

    public StatementLineage parseStatement(ParseRequest request) {
        if (request == null || request.getSql().trim().isEmpty()) {
            throw new SqlParseException("SQL不能为空", 1, 0);
        }
        ParseBundle bundle = parseTree(request.getSql());
        return new SqlLineageAnalyzer(request, bundle.tokens).analyze(bundle.root.statement());
    }

    /**
     * 将单条 SELECT 或 INSERT 的查询部分包装成服务端限量查询。
     * 该方法只接受解析器明确识别的查询语句，避免通过字符串裁剪误执行 DDL/DML。
     */
    public String toReadOnlyPreviewQuery(ParseRequest request, int limit) {
        if (request == null || request.getSql().trim().isEmpty()) {
            throw new SqlParseException("SQL不能为空", 1, 0);
        }
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("预览行数必须在1到200之间");
        String source = request.getSql();
        ParseBundle bundle = parseTree(source);
        SqlBaseParser.StatementContext statement = bundle.root.statement();
        String querySql;
        if (statement instanceof SqlBaseParser.StatementDefaultContext) {
            querySql = sourceText(source, ((SqlBaseParser.StatementDefaultContext) statement).query());
        } else if (statement instanceof SqlBaseParser.InsertIntoContext) {
            SqlBaseParser.InsertIntoContext insert = (SqlBaseParser.InsertIntoContext) statement;
            StringBuilder value = new StringBuilder();
            if (insert.with() != null) value.append(sourceText(source, insert.with())).append('\n');
            value.append(sourceText(source, insert.query()));
            querySql = value.toString();
        } else {
            throw new IllegalArgumentException("只读预览仅支持SELECT或INSERT查询部分");
        }
        String normalized = querySql.trim();
        while (normalized.endsWith(";")) normalized = normalized.substring(0, normalized.length() - 1).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("没有可预览的查询部分");
        return "SELECT * FROM (\n" + normalized + "\n) sql_agent_preview LIMIT " + limit;
    }

    private String sourceText(String source, org.antlr.v4.runtime.ParserRuleContext context) {
        int start = context.getStart().getStartIndex();
        int end = context.getStop().getStopIndex() + 1;
        if (start < 0 || end <= start || end > source.length()) {
            throw new IllegalArgumentException("无法确定查询源码范围");
        }
        return source.substring(start, end);
    }

    public SqlScriptLineage parseScript(ParseRequest request) {
        if (request == null) throw new IllegalArgumentException("解析请求不能为空");
        List<StatementLineage> statements = new ArrayList<>();
        List<LineageDiagnostic> diagnostics = new ArrayList<>();
        List<String> parts;
        try {
            parts = splitStatements(request.getSql(), request.getDialect());
        } catch (SqlParseException error) {
            if (request.getMode() == ParseMode.STRICT) throw error;
            diagnostics.add(new LineageDiagnostic("SCRIPT_LEXER_ERROR", DiagnosticSeverity.ERROR,
                    error.getMessage(), error.getLine(), error.getColumn(),
                    error.getStartOffset(), error.getEndOffset()));
            return new SqlScriptLineage(statements, diagnostics);
        }
        String defaultCatalog = request.getDefaultCatalog();
        String defaultDatabase = request.getDefaultDatabase();
        VirtualMetadata metadata = new VirtualMetadata(request.getMetadataProvider());
        for (String part : parts) {
            try {
                ParseRequest statementRequest = request.toBuilder(part).defaultCatalog(defaultCatalog)
                        .defaultDatabase(defaultDatabase).metadataProvider(metadata).build();
                ParseBundle bundle = parseTree(part);
                StatementLineage statement = new SqlLineageAnalyzer(statementRequest, bundle.tokens)
                        .analyze(bundle.root.statement());
                statement = metadata.resolve(statement);
                statements.add(statement);
                if (statement.getStatementType() == SqlStatementType.CREATE_TEMPORARY_VIEW) {
                    metadata.register(statement);
                }
                if (bundle.root.statement() instanceof SqlBaseParser.UseContext) {
                    SqlBaseParser.UseContext use = (SqlBaseParser.UseContext) bundle.root.statement();
                    defaultDatabase = TableIdentifier.unquote(use.schema.getText());
                    if (use.catalog != null) defaultCatalog = TableIdentifier.unquote(use.catalog.getText());
                }
            } catch (SqlParseException error) {
                if (request.getMode() == ParseMode.STRICT) throw error;
                if (isUnsupportedScriptStatement(part)) {
                    statements.add(unsupportedStatement(part, error));
                } else {
                    diagnostics.add(new LineageDiagnostic("SYNTAX_ERROR", DiagnosticSeverity.ERROR,
                            error.getMessage(), error.getLine(), error.getColumn(),
                            error.getStartOffset(), error.getEndOffset()));
                }
            } catch (RuntimeException error) {
                if (request.getMode() == ParseMode.STRICT) throw error;
                diagnostics.add(new LineageDiagnostic("INTERNAL_ERROR", DiagnosticSeverity.ERROR,
                        rootMessage(error), 1, 0, -1, -1));
            }
        }
        return new SqlScriptLineage(statements, diagnostics);
    }

    private StatementLineage unsupportedStatement(String sql, SqlParseException error) {
        LineageDiagnostic diagnostic = new LineageDiagnostic("UNSUPPORTED_STATEMENT",
                DiagnosticSeverity.WARNING, "脚本命令不属于受支持的数据血缘语句",
                error.getLine(), error.getColumn(), error.getStartOffset(), error.getEndOffset());
        return new StatementLineage(sql, SqlStatementType.OTHER, Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList(diagnostic));
    }

    /**
     * 容错脚本可携带 Shell/调度命令和管理 DDL。它们没有查询血缘语义，但应作为不支持语句
     * 单独报告，而不能让同一脚本中后续的 INSERT/SELECT 丢失。
     */
    private boolean isUnsupportedScriptStatement(String sql) {
        SqlBaseLexer lexer = new SqlBaseLexer(CharStreams.fromString(sql));
        List<? extends Token> tokens = lexer.getAllTokens();
        Token first = firstDefaultToken(tokens);
        if (first == null) return false;
        switch (first.getType()) {
            case SqlBaseLexer.SELECT:
            case SqlBaseLexer.WITH:
            case SqlBaseLexer.INSERT:
            case SqlBaseLexer.REPLACE:
            case SqlBaseLexer.UPDATE:
            case SqlBaseLexer.DELETE:
                return false;
            case SqlBaseLexer.CREATE:
                return !looksLikeLineageCreate(tokens);
            default:
                return true;
        }
    }

    private boolean looksLikeLineageCreate(List<? extends Token> tokens) {
        List<Integer> visible = new ArrayList<>();
        for (Token token : tokens) {
            if (token.getChannel() == Token.DEFAULT_CHANNEL) visible.add(token.getType());
        }
        int index = 1;
        if (index + 1 < visible.size() && visible.get(index) == SqlBaseLexer.OR
                && visible.get(index + 1) == SqlBaseLexer.REPLACE) index += 2;
        if (index < visible.size() && visible.get(index) == SqlBaseLexer.TEMPORARY) index++;
        if (index < visible.size() && visible.get(index) == SqlBaseLexer.VIEW) return true;
        if (index >= visible.size() || visible.get(index) != SqlBaseLexer.TABLE) return false;
        for (int cursor = index + 1; cursor + 1 < visible.size(); cursor++) {
            if (visible.get(cursor) == SqlBaseLexer.AS
                    && (visible.get(cursor + 1) == SqlBaseLexer.SELECT
                    || visible.get(cursor + 1) == SqlBaseLexer.WITH)) return true;
        }
        return false;
    }

    private Token firstDefaultToken(List<? extends Token> tokens) {
        for (Token token : tokens) if (token.getChannel() == Token.DEFAULT_CHANNEL) return token;
        return null;
    }

    /** 使用 lexer 切分脚本，字符串、注释和 Flink Statement Set 内部边界均不会误切。 */
    public List<String> splitStatements(String sql, SqlDialect dialect) {
        String source = prepareScript(sql == null ? "" : sql, dialect);
        if (source.trim().isEmpty()) return Collections.emptyList();
        SyntaxErrors errors = new SyntaxErrors();
        SqlBaseLexer lexer = lexer(source, errors);
        List<? extends Token> tokens = lexer.getAllTokens();
        errors.throwIfPresent();
        List<String> result = new ArrayList<>();
        int start = 0;
        int parenthesisDepth = 0;
        int bracketDepth = 0;
        for (Token token : tokens) {
            if (token.getChannel() != Token.DEFAULT_CHANNEL) continue;
            String value = token.getText();
            if (parenthesisDepth == 0 && bracketDepth == 0 && start < token.getStartIndex()
                    && isImplicitCommandBoundary(token)
                    && containsLineBreak(source, start, token.getStartIndex())) {
                addStatement(result, source.substring(start, token.getStartIndex()));
                start = token.getStartIndex();
            }
            if ("(".equals(value)) parenthesisDepth++;
            else if (")".equals(value) && parenthesisDepth > 0) parenthesisDepth--;
            else if ("[".equals(value)) bracketDepth++;
            else if ("]".equals(value) && bracketDepth > 0) bracketDepth--;
            else if (parenthesisDepth == 0 && bracketDepth == 0 && ";".equals(value)) {
                addStatement(result, source.substring(start, token.getStartIndex()));
                start = token.getStopIndex() + 1;
            }
        }
        addStatement(result, source.substring(Math.min(start, source.length())));
        return Collections.unmodifiableList(result);
    }

    /**
     * 历史调度语料中存在未加分号、但从新行开始的管理命令。仅对不可能出现在查询表达式中的
     * 顶层 DDL/会话命令做恢复，避免把 CTE、UNION 或 Hive 多路 INSERT 错切。
     */
    private boolean isImplicitCommandBoundary(Token token) {
        switch (token.getType()) {
            case SqlBaseLexer.CREATE:
            case SqlBaseLexer.DROP:
            case SqlBaseLexer.ALTER:
            case SqlBaseLexer.USE:
            case SqlBaseLexer.SHOW:
                return true;
            default:
                return false;
        }
    }

    private boolean containsLineBreak(String source, int start, int end) {
        for (int index = Math.max(0, start); index < Math.min(end, source.length()); index++) {
            char value = source.charAt(index);
            if (value == '\n' || value == '\r') return true;
        }
        return false;
    }

    /** 仅清理 lexer 识别出的注释 token，字符串和引号标识符中的注释字符保持不变。 */
    public String stripComments(String sql) {
        String source = sql == null ? "" : sql;
        StringBuilder result = new StringBuilder(source);
        SqlBaseLexer lexer = new SqlBaseLexer(CharStreams.fromString(source));
        for (Token token : lexer.getAllTokens()) {
            if (token.getType() != SqlBaseLexer.SIMPLE_COMMENT
                    && token.getType() != SqlBaseLexer.BRACKETED_COMMENT) continue;
            for (int index = token.getStartIndex(); index <= token.getStopIndex() && index < result.length(); index++) {
                char value = result.charAt(index);
                if (value != '\r' && value != '\n') result.setCharAt(index, ' ');
            }
        }
        return result.toString().trim();
    }

    public String rewriteTables(ParseRequest request, Map<String, String> replacements) {
        StatementLineage parsed = parseStatement(request);
        if (replacements == null || replacements.isEmpty()) return parsed.getSql();
        Map<String, String> normalized = new HashMap<>();
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            String replacement = entry.getValue() == null ? "" : entry.getValue().trim();
            if (!SAFE_REPLACEMENT.matcher(replacement).matches()) {
                throw new IllegalArgumentException("非法的目标表名：" + replacement);
            }
            TableIdentifier identifier = TableIdentifier.parse(entry.getKey(),
                    request.getDefaultCatalog(), request.getDefaultDatabase());
            normalized.put(identifier.normalizedName(), replacement);
        }
        List<TableAccess> matches = new ArrayList<>();
        for (TableAccess access : parsed.getTableAccesses()) {
            if (!access.isDynamic() && normalized.containsKey(access.getTable().normalizedName())) matches.add(access);
        }
        matches.sort(Comparator.comparingInt(TableAccess::getStartOffset).reversed());
        StringBuilder rewritten = new StringBuilder(parsed.getSql());
        int previousStart = Integer.MIN_VALUE;
        for (TableAccess match : matches) {
            if (match.getStartOffset() == previousStart) continue;
            rewritten.replace(match.getStartOffset(), match.getEndOffset(),
                    normalized.get(match.getTable().normalizedName()));
            previousStart = match.getStartOffset();
        }
        return rewritten.toString();
    }

    private ParseBundle parseTree(String source) {
        SyntaxErrors errors = new SyntaxErrors();
        SqlBaseLexer lexer = lexer(source, errors);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        SqlBaseParser parser = new SqlBaseParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(errors);
        SqlBaseParser.SingleStatementContext root = parser.singleStatement();
        errors.throwIfPresent();
        return new ParseBundle(root, tokens);
    }

    private SqlBaseLexer lexer(String source, SyntaxErrors errors) {
        SqlBaseLexer lexer = new SqlBaseLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners(); lexer.addErrorListener(errors); return lexer;
    }

    private String prepareScript(String sql, SqlDialect dialect) {
        String source = STEP_LINE.matcher(sql).replaceAll("");
        source = ENGINE_LINE.matcher(source).replaceAll("");
        return dialect == SqlDialect.FLINK ? unwrapStatementSets(source) : source;
    }

    /**
     * 按 lexer token 去掉 Statement Set 包装。包装 END 必须位于 INSERT 终止分号之后，
     * 因此不会把 CASE ... END、字符串或注释误认为包装结束。
     */
    private String unwrapStatementSets(String source) {
        SqlBaseLexer lexer = new SqlBaseLexer(CharStreams.fromString(source));
        List<Token> visible = new ArrayList<>();
        for (Token token : lexer.getAllTokens()) {
            if (token.getChannel() == Token.DEFAULT_CHANNEL) visible.add(token);
        }
        List<int[]> removals = new ArrayList<>();
        for (int index = 0; index + 3 < visible.size(); index++) {
            if (!tokensEqual(visible, index, "EXECUTE", "STATEMENT", "SET", "BEGIN")) continue;
            Token execute = visible.get(index);
            Token begin = visible.get(index + 3);
            Token end = null;
            for (int cursor = index + 4; cursor < visible.size(); cursor++) {
                Token candidate = visible.get(cursor);
                if ("END".equalsIgnoreCase(candidate.getText())
                        && cursor > 0 && ";".equals(visible.get(cursor - 1).getText())) {
                    end = candidate;
                    index = cursor;
                    break;
                }
            }
            if (end == null) {
                throw new SqlParseException("Flink Statement Set 缺少 END", execute.getLine(),
                        execute.getCharPositionInLine(), execute.getStartIndex(), execute.getStopIndex() + 1);
            }
            removals.add(new int[]{execute.getStartIndex(), begin.getStopIndex() + 1});
            removals.add(new int[]{end.getStartIndex(), end.getStopIndex() + 1});
        }
        StringBuilder result = new StringBuilder(source);
        removals.sort((left, right) -> Integer.compare(right[0], left[0]));
        for (int[] removal : removals) result.replace(removal[0], removal[1], " ");
        return result.toString();
    }

    private boolean tokensEqual(List<Token> tokens, int start, String... expected) {
        if (start + expected.length > tokens.size()) return false;
        for (int index = 0; index < expected.length; index++) {
            if (!expected[index].equalsIgnoreCase(tokens.get(start + index).getText())) return false;
        }
        return true;
    }

    private void addStatement(List<String> result, String source) {
        String statement = source.trim();
        if (statement.isEmpty()) return;
        SqlBaseLexer lexer = new SqlBaseLexer(CharStreams.fromString(statement));
        for (Token token : lexer.getAllTokens()) {
            if (token.getChannel() == Token.DEFAULT_CHANNEL && !";".equals(token.getText())) {
                result.add(statement); return;
            }
        }
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static final class ParseBundle {
        private final SqlBaseParser.SingleStatementContext root;
        private final CommonTokenStream tokens;
        private ParseBundle(SqlBaseParser.SingleStatementContext root, CommonTokenStream tokens) {
            this.root = root; this.tokens = tokens;
        }
    }

    /** 脚本内临时视图的 Schema 与物理来源，仅在一次解析调用内可见。 */
    private static final class VirtualMetadata implements TableMetadataProvider {
        private final TableMetadataProvider delegate;
        private final Map<String, VirtualDefinition> definitions = new LinkedHashMap<>();
        private final Map<String, Optional<TableSchema>> physicalCache = new LinkedHashMap<>();

        private VirtualMetadata(TableMetadataProvider delegate) {
            this.delegate = delegate == null ? TableMetadataProvider.NONE : delegate;
        }

        @Override
        public Optional<TableSchema> getTable(TableIdentifier table) {
            VirtualDefinition definition = definitions.get(table.normalizedName());
            if (definition != null) return Optional.of(definition.schema);
            Optional<TableSchema> cached = physicalCache.get(table.normalizedName());
            if (cached != null) return cached;
            Optional<TableSchema> loaded;
            try {
                loaded = delegate.getTable(table);
                if (loaded == null) loaded = Optional.empty();
            } catch (RuntimeException error) {
                loaded = Optional.empty();
            }
            physicalCache.put(table.normalizedName(), loaded);
            return loaded;
        }

        private void register(StatementLineage statement) {
            TableIdentifier target = null;
            List<TableColumnMetadata> columns = new ArrayList<>();
            Map<String, List<SourceColumn>> sources = new LinkedHashMap<>();
            for (ColumnLineage lineage : statement.getColumnLineages()) {
                if (lineage.getTargetTable() == null) continue;
                target = lineage.getTargetTable();
                columns.add(new TableColumnMetadata(lineage.getTargetColumn(), "", false));
                sources.put(lineage.getTargetColumn().toLowerCase(Locale.ROOT), lineage.getSources());
            }
            if (target != null) {
                List<TableAccess> physical = new ArrayList<>();
                for (TableAccess access : statement.getTableAccesses()) {
                    if (access.getRole() == TableAccessRole.READ || access.getRole() == TableAccessRole.READ_WRITE) {
                        physical.add(access);
                    }
                }
                definitions.put(target.normalizedName(),
                        new VirtualDefinition(new TableSchema(target, columns), sources, physical));
            }
        }

        private StatementLineage resolve(StatementLineage statement) {
            if (definitions.isEmpty()) return statement;
            List<TableAccess> accesses = new ArrayList<>();
            for (TableAccess access : statement.getTableAccesses()) {
                VirtualDefinition definition = definitions.get(access.getTable().normalizedName());
                if (definition != null && (access.getRole() == TableAccessRole.READ
                        || access.getRole() == TableAccessRole.READ_WRITE)) {
                    for (TableAccess physical : definition.physicalAccesses) {
                        accesses.add(new TableAccess(physical.getTable(), TableAccessRole.READ,
                                -1, -1, physical.isDynamic()));
                    }
                } else accesses.add(access);
            }
            accesses = uniqueAccesses(accesses);

            List<ColumnLineage> lineages = new ArrayList<>();
            for (ColumnLineage lineage : statement.getColumnLineages()) {
                lineages.add(new ColumnLineage(lineage.getTargetTable(), lineage.getTargetColumn(),
                        lineage.getOrdinal(), lineage.getExpression(), resolveSources(lineage.getSources())));
            }
            List<ColumnUsage> usages = new ArrayList<>();
            for (ColumnUsage usage : statement.getColumnUsages()) {
                usages.add(new ColumnUsage(usage.getType(), usage.getExpression(), resolveSources(usage.getColumns())));
            }
            List<JoinRelation> joins = new ArrayList<>();
            for (JoinRelation join : statement.getJoins()) {
                joins.add(new JoinRelation(join.getJoinType(), join.getCondition(),
                        resolveSources(join.getLeftColumns()), resolveSources(join.getRightColumns())));
            }
            return new StatementLineage(statement.getSql(), statement.getStatementType(), accesses,
                    lineages, usages, joins, statement.getDiagnostics());
        }

        private List<SourceColumn> resolveSources(List<SourceColumn> values) {
            LinkedHashSet<SourceColumn> result = new LinkedHashSet<>();
            for (SourceColumn source : values) {
                VirtualDefinition definition = definitions.get(source.getTable().normalizedName());
                if (definition == null) {
                    result.add(source); continue;
                }
                List<SourceColumn> mapped = definition.sources.get(source.getColumn().toLowerCase(Locale.ROOT));
                if (mapped == null) mapped = definition.sources.get("*");
                if (mapped == null || mapped.isEmpty()) continue;
                for (SourceColumn physical : mapped) {
                    result.add(new SourceColumn(physical.getTable(), physical.getColumn(),
                            source.isDirect() && physical.isDirect()));
                }
            }
            return new ArrayList<>(result);
        }

        private List<TableAccess> uniqueAccesses(List<TableAccess> values) {
            Map<String, TableAccess> result = new LinkedHashMap<>();
            for (TableAccess value : values) {
                String key = value.getTable().normalizedName() + ":" + value.getRole();
                result.putIfAbsent(key, value);
            }
            return new ArrayList<>(result.values());
        }
    }

    private static final class VirtualDefinition {
        private final TableSchema schema;
        private final Map<String, List<SourceColumn>> sources;
        private final List<TableAccess> physicalAccesses;

        private VirtualDefinition(TableSchema schema, Map<String, List<SourceColumn>> sources,
                                  List<TableAccess> physicalAccesses) {
            this.schema = schema; this.sources = sources; this.physicalAccesses = physicalAccesses;
        }
    }

    private static final class SyntaxErrors extends BaseErrorListener {
        private final Map<Integer, String> messages = new LinkedHashMap<>();
        private int line = 1;
        private int column;
        private int startOffset = -1;
        private int endOffset = -1;
        @Override public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                                          int charPositionInLine, String message, RecognitionException exception) {
            if (messages.isEmpty()) {
                this.line = line; this.column = charPositionInLine;
                if (offendingSymbol instanceof Token) {
                    Token token = (Token) offendingSymbol;
                    startOffset = token.getStartIndex(); endOffset = token.getStopIndex() + 1;
                } else if (recognizer instanceof Lexer) {
                    startOffset = ((Lexer) recognizer).getInputStream().index();
                    endOffset = startOffset < 0 ? -1 : startOffset + 1;
                }
            }
            messages.put(messages.size(), message);
        }
        private void throwIfPresent() {
            if (!messages.isEmpty()) throw new SqlParseException("SQL解析失败（第" + line + "行，第" + column
                    + "列）：" + messages.get(0), line, column, startOffset, endOffset);
        }
    }
}
