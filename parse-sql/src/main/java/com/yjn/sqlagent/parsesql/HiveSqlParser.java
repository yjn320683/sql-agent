package com.yjn.sqlagent.parsesql;

import com.yjn.sqlagent.parsesql.antlr.SqlBaseBaseListener;
import com.yjn.sqlagent.parsesql.antlr.SqlBaseLexer;
import com.yjn.sqlagent.parsesql.antlr.SqlBaseParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;

/** 基于项目内置完整 Hive grammar 的表级 SQL 解析器。 */
public final class HiveSqlParser {
    private static final Pattern SAFE_REPLACEMENT = Pattern.compile(
            "(?:`(?:``|[^`])+`|[A-Za-z0-9_$]+)(?:\\.(?:`(?:``|[^`])+`|[A-Za-z0-9_$]+))*");

    /** 解析一条完整语句，并提取物理输入表和输出表。 */
    public SqlParseResult parseStatement(String sql) {
        String source = sql == null ? "" : sql;
        if (source.trim().isEmpty()) throw new SqlParseException("SQL不能为空", 1, 0);

        SyntaxErrors errors = new SyntaxErrors();
        SqlBaseLexer lexer = lexer(source, errors);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        SqlBaseParser parser = new SqlBaseParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(errors);
        SqlBaseParser.SingleStatementContext root = parser.singleStatement();
        errors.throwIfPresent();

        RelationCollector collector = new RelationCollector(source);
        ParseTreeWalker.DEFAULT.walk(collector, root);
        return new SqlParseResult(source, statementType(root.statement()), collector.references);
    }

    /**
     * 按顶层分号切分 SQL。字符串和注释内的分号由 lexer 隐藏在单个 token 中，
     * 因而不会产生误切分。
     */
    public List<String> splitStatements(String sql) {
        String source = sql == null ? "" : sql;
        if (source.trim().isEmpty()) return Collections.emptyList();
        SyntaxErrors errors = new SyntaxErrors();
        SqlBaseLexer lexer = lexer(source, errors);
        List<? extends Token> tokens = lexer.getAllTokens();
        errors.throwIfPresent();

        List<String> result = new ArrayList<>();
        int start = 0;
        for (Token token : tokens) {
            if (token.getChannel() == Token.DEFAULT_CHANNEL && ";".equals(token.getText())) {
                addStatement(result, source.substring(start, token.getStartIndex()));
                start = token.getStopIndex() + 1;
            }
        }
        addStatement(result, source.substring(Math.min(start, source.length())));
        return Collections.unmodifiableList(result);
    }

    /** 仅替换真实表引用，不修改注释、字符串、字段名或别名。 */
    public String rewriteTables(String sql, Map<String, String> replacements) {
        SqlParseResult parsed = parseStatement(sql);
        if (replacements == null || replacements.isEmpty()) return parsed.getSql();

        Map<String, String> normalized = new HashMap<>();
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            String replacement = entry.getValue() == null ? "" : entry.getValue().trim();
            if (!SAFE_REPLACEMENT.matcher(replacement).matches()) {
                throw new IllegalArgumentException("非法的目标表名：" + replacement);
            }
            normalized.put(TableReference.normalize(entry.getKey()), replacement);
        }

        List<TableReference> matches = new ArrayList<>();
        for (TableReference reference : parsed.getTableReferences()) {
            if (!reference.isDynamic() && normalized.containsKey(reference.getNormalizedName())) {
                matches.add(reference);
            }
        }
        matches.sort(Comparator.comparingInt(TableReference::getStartOffset).reversed());
        StringBuilder rewritten = new StringBuilder(parsed.getSql());
        int previousStart = Integer.MAX_VALUE;
        for (TableReference match : matches) {
            // 同一源码区间即使被赋予多个语义角色，也只能改写一次。
            if (match.getStartOffset() == previousStart) continue;
            rewritten.replace(match.getStartOffset(), match.getEndOffset(),
                    normalized.get(match.getNormalizedName()));
            previousStart = match.getStartOffset();
        }
        return rewritten.toString();
    }

    private SqlBaseLexer lexer(String source, SyntaxErrors errors) {
        SqlBaseLexer lexer = new SqlBaseLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        lexer.addErrorListener(errors);
        return lexer;
    }

    private void addStatement(List<String> result, String source) {
        String statement = source.trim();
        if (statement.isEmpty()) return;
        SqlBaseLexer lexer = new SqlBaseLexer(CharStreams.fromString(statement));
        for (Token token : lexer.getAllTokens()) {
            if (token.getChannel() == Token.DEFAULT_CHANNEL && !";".equals(token.getText())) {
                result.add(statement);
                return;
            }
        }
    }

    private SqlStatementType statementType(SqlBaseParser.StatementContext context) {
        if (context instanceof SqlBaseParser.InsertIntoContext) {
            SqlBaseParser.InsertIntoContext insert = (SqlBaseParser.InsertIntoContext) context;
            return insert.with() == null ? SqlStatementType.INSERT : SqlStatementType.WITH;
        }
        if (context instanceof SqlBaseParser.CreateTableAsSelectContext) {
            return SqlStatementType.CREATE_TABLE_AS_SELECT;
        }
        if (context instanceof SqlBaseParser.SetSessionContext) return SqlStatementType.SET;
        if (context instanceof SqlBaseParser.StatementDefaultContext) {
            SqlBaseParser.QueryContext query = ((SqlBaseParser.StatementDefaultContext) context).query();
            return query.with() == null ? SqlStatementType.SELECT : SqlStatementType.WITH;
        }
        return SqlStatementType.OTHER;
    }

    private static final class RelationCollector extends SqlBaseBaseListener {
        private final String source;
        private final List<TableReference> references = new ArrayList<>();

        private RelationCollector(String source) {
            this.source = source;
        }

        @Override
        public void enterInsertInto(SqlBaseParser.InsertIntoContext context) {
            add(context.qualifiedName(), TableRole.OUTPUT);
        }

        @Override
        public void enterCreateTableAsSelect(SqlBaseParser.CreateTableAsSelectContext context) {
            add(context.qualifiedName(), TableRole.OUTPUT);
        }

        @Override
        public void enterTableName(SqlBaseParser.TableNameContext context) {
            addInput(context.qualifiedName());
        }

        @Override
        public void enterTable(SqlBaseParser.TableContext context) {
            addInput(context.qualifiedName());
        }

        private void addInput(SqlBaseParser.QualifiedNameContext context) {
            if (!isCteReference(context)) add(context, TableRole.INPUT);
        }

        private boolean isCteReference(SqlBaseParser.QualifiedNameContext context) {
            String normalized = TableReference.normalize(context.getText());
            if (normalized.contains(".")) return false;
            ParseTree current = context.getParent();
            while (current != null) {
                if (current instanceof SqlBaseParser.QueryContext
                        && containsCte(((SqlBaseParser.QueryContext) current).with(), normalized)) {
                    return true;
                }
                if (current instanceof SqlBaseParser.InsertIntoContext
                        && containsCte(((SqlBaseParser.InsertIntoContext) current).with(), normalized)) {
                    return true;
                }
                current = current.getParent();
            }
            return false;
        }

        private boolean containsCte(SqlBaseParser.WithContext with, String normalized) {
            if (with == null) return false;
            for (SqlBaseParser.NamedQueryContext query : with.namedQuery()) {
                if (TableReference.normalize(query.name.getText()).equals(normalized)) return true;
            }
            return false;
        }

        private void add(SqlBaseParser.QualifiedNameContext context, TableRole role) {
            int start = context.getStart().getStartIndex();
            int end = context.getStop().getStopIndex() + 1;
            String original = source.substring(start, end);
            references.add(new TableReference(original, role, start, end));
        }
    }

    private static final class SyntaxErrors extends BaseErrorListener {
        private final Map<Integer, String> messages = new LinkedHashMap<>();
        private int firstLine = 1;
        private int firstColumn = 0;

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                                int charPositionInLine, String message, RecognitionException exception) {
            if (messages.isEmpty()) {
                firstLine = line;
                firstColumn = charPositionInLine;
            }
            messages.put(messages.size(), message);
        }

        private void throwIfPresent() {
            if (messages.isEmpty()) return;
            throw new SqlParseException("Hive SQL解析失败（第" + firstLine + "行，第" + firstColumn
                    + "列）：" + messages.get(0), firstLine, firstColumn);
        }
    }
}
