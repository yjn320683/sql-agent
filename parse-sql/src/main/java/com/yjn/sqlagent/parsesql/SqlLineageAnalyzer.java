package com.yjn.sqlagent.parsesql;

import com.yjn.sqlagent.parsesql.antlr.SqlBaseParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;

/** 将 ANTLR 语法树转换为作用域模型，再回溯到物理字段。 */
final class SqlLineageAnalyzer {
    private final ParseRequest request;
    @SuppressWarnings("unused") private final CommonTokenStream tokens;
    private final List<TableAccess> accesses = new ArrayList<>();
    private final List<ColumnLineage> lineages = new ArrayList<>();
    private final List<ColumnUsage> usages = new ArrayList<>();
    private final List<JoinRelation> joins = new ArrayList<>();
    private final List<LineageDiagnostic> diagnostics = new ArrayList<>();
    private final Map<TableIdentifier, Optional<TableSchema>> metadata = new HashMap<>();

    SqlLineageAnalyzer(ParseRequest request, CommonTokenStream tokens) {
        this.request = request; this.tokens = tokens;
    }

    StatementLineage analyze(SqlBaseParser.StatementContext statement) {
        SqlStatementType type = statementType(statement);
        if (statement instanceof SqlBaseParser.StatementDefaultContext) {
            Shape shape = analyzeQuery(((SqlBaseParser.StatementDefaultContext) statement).query(), new Scope(null));
            emitOutput(null, shape.outputs);
        } else if (statement instanceof SqlBaseParser.InsertIntoContext) {
            analyzeInsert((SqlBaseParser.InsertIntoContext) statement);
        } else if (statement instanceof SqlBaseParser.CreateTableAsSelectContext) {
            SqlBaseParser.CreateTableAsSelectContext ctx = (SqlBaseParser.CreateTableAsSelectContext) statement;
            TableIdentifier target = addAccess(ctx.qualifiedName(), TableAccessRole.WRITE);
            Shape shape = analyzeQuery(ctx.query(), new Scope(null));
            emitOutput(target, targetColumns(target, null, shape.outputs, Collections.emptySet()));
        } else if (statement instanceof SqlBaseParser.CreateViewContext) {
            SqlBaseParser.CreateViewContext ctx = (SqlBaseParser.CreateViewContext) statement;
            TableIdentifier target = addAccess(ctx.qualifiedName(), TableAccessRole.WRITE);
            List<Output> outputs = analyzeQuery(ctx.query(), new Scope(null)).outputs;
            if (ctx.columnAliases() != null) renameOutputs(outputs, identifiers(ctx.columnAliases().identifier()));
            emitOutput(target, outputs);
        } else if (statement instanceof SqlBaseParser.CreateTemporaryViewContext) {
            SqlBaseParser.CreateTemporaryViewContext ctx = (SqlBaseParser.CreateTemporaryViewContext) statement;
            TableIdentifier target = identifier(ctx.qualifiedName());
            List<Output> outputs = analyzeQuery(ctx.query(), new Scope(null)).outputs;
            if (ctx.columnAliases() != null) renameOutputs(outputs, identifiers(ctx.columnAliases().identifier()));
            emitOutput(target, outputs);
        } else if (statement instanceof SqlBaseParser.UpdateContext) {
            analyzeUpdate((SqlBaseParser.UpdateContext) statement);
        } else if (statement instanceof SqlBaseParser.DeleteContext) {
            analyzeDelete((SqlBaseParser.DeleteContext) statement);
        } else if (type == SqlStatementType.OTHER) {
            diagnostic("UNSUPPORTED_STATEMENT", DiagnosticSeverity.WARNING,
                    "该语句没有数据血缘语义", statement);
        }
        return new StatementLineage(request.getSql(), type, compactAccesses(), lineages, usages, joins, diagnostics);
    }

    private void analyzeInsert(SqlBaseParser.InsertIntoContext ctx) {
        TableIdentifier target = addAccess(ctx.qualifiedName(), TableAccessRole.WRITE);
        Scope root = new Scope(null);
        if (ctx.with() != null) analyzeWith(ctx.with(), root);
        Shape shape = analyzeQuery(ctx.query(), root);
        List<String> explicit = ctx.columnAliases() == null ? null : identifiers(ctx.columnAliases().identifier());
        Set<String> staticPartitions = new LinkedHashSet<>();
        for (SqlBaseParser.PartitionAssignmentContext partition : ctx.partitionAssignment()) {
            if (partition.value != null) {
                staticPartitions.add(clean(partition.name.getText()).toLowerCase(Locale.ROOT));
                addUsage(ColumnUsageType.PARTITION_WRITE, partition.value,
                        resolveExpression(partition.value, shape.scope));
            }
        }
        emitOutput(target, targetColumns(target, explicit, shape.outputs, staticPartitions));
    }

    private void analyzeUpdate(SqlBaseParser.UpdateContext ctx) {
        Scope scope = new Scope(null);
        for (SqlBaseParser.RelationContext relation : ctx.relation()) analyzeRelation(relation, scope);
        Binding target = scope.bindings.isEmpty() ? null : scope.bindings.get(0);
        if (target != null && target.table != null) markReadWrite(target.table);
        int ordinal = 0;
        for (SqlBaseParser.SetItemContext item : ctx.setItem()) {
            List<SourceColumn> sources = resolveExpression(item.right, scope);
            String targetColumn = lastPart(text(item.left));
            lineages.add(new ColumnLineage(target == null ? null : target.table, targetColumn,
                    ordinal++, text(item.right), sources));
            addUsage(ColumnUsageType.UPDATE_SET, item.right, sources);
        }
        if (ctx.where != null) addUsage(ColumnUsageType.FILTER, ctx.where, resolveExpression(ctx.where, scope));
    }

    private void analyzeDelete(SqlBaseParser.DeleteContext ctx) {
        TableIdentifier target = addAccess(ctx.table, TableAccessRole.READ_WRITE);
        Scope scope = new Scope(null);
        String alias = ctx.alias == null ? target.getTable() : clean(ctx.alias.getText());
        scope.bindings.add(physicalBinding(target, alias, false));
        if (ctx.booleanExpression() != null) {
            addUsage(ColumnUsageType.FILTER, ctx.booleanExpression(), resolveExpression(ctx.booleanExpression(), scope));
        }
    }

    private Shape analyzeQuery(SqlBaseParser.QueryContext ctx, Scope outer) {
        Scope scope = new Scope(outer);
        if (ctx.with() != null) analyzeWith(ctx.with(), scope);
        return analyzeQueryNoWith(ctx.queryNoWith(), scope);
    }

    private void analyzeWith(SqlBaseParser.WithContext with, Scope scope) {
        for (SqlBaseParser.NamedQueryContext named : with.namedQuery()) {
            String name = clean(named.name.getText());
            // 递归 CTE 先注册占位，避免被误认为物理表。
            Binding placeholder = new Binding(name, null, new ArrayList<>(), true);
            scope.ctes.put(name.toLowerCase(Locale.ROOT), placeholder);
            Shape value = analyzeQuery(named.query(), scope);
            List<Output> outputs = new ArrayList<>(value.outputs);
            if (named.columnAliases() != null) renameOutputs(outputs, identifiers(named.columnAliases().identifier()));
            scope.ctes.put(name.toLowerCase(Locale.ROOT), new Binding(name, null, outputs, false));
        }
    }

    private Shape analyzeQueryNoWith(SqlBaseParser.QueryNoWithContext ctx, Scope scope) {
        Shape shape = analyzeQueryTerm(ctx.queryTerm(), scope);
        Scope orderScope = new Scope(shape.scope);
        orderScope.add(new Binding("", null, shape.outputs, false));
        for (SqlBaseParser.SortItemContext item : ctx.sortItem()) {
            addUsage(ColumnUsageType.ORDER_BY, item, resolveExpression(item, orderScope));
        }
        return shape;
    }

    private Shape analyzeQueryTerm(SqlBaseParser.QueryTermContext ctx, Scope scope) {
        if (ctx instanceof SqlBaseParser.QueryTermDefaultContext) {
            return analyzeQueryPrimary(((SqlBaseParser.QueryTermDefaultContext) ctx).queryPrimary(), scope);
        }
        SqlBaseParser.SetOperationContext set = (SqlBaseParser.SetOperationContext) ctx;
        Shape left = analyzeQueryTerm(set.left, new Scope(scope));
        Shape right = analyzeQueryTerm(set.right, new Scope(scope));
        int count = Math.max(left.outputs.size(), right.outputs.size());
        if (left.outputs.size() != right.outputs.size()) {
            diagnostic("SET_COLUMN_COUNT_MISMATCH", DiagnosticSeverity.ERROR,
                    "集合操作两侧列数不一致：" + left.outputs.size() + " / " + right.outputs.size(), set);
        }
        List<Output> outputs = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            Output first = index < left.outputs.size() ? left.outputs.get(index) : null;
            Output second = index < right.outputs.size() ? right.outputs.get(index) : null;
            String name = first != null ? first.name : second.name;
            String expression = first != null ? first.expression : second.expression;
            List<SourceColumn> sources = new ArrayList<>();
            if (first != null) sources.addAll(first.sources);
            if (second != null) sources.addAll(second.sources);
            outputs.add(new Output(name, expression, sources));
        }
        return new Shape(outputs, left.scope);
    }

    private Shape analyzeQueryPrimary(SqlBaseParser.QueryPrimaryContext ctx, Scope scope) {
        if (ctx instanceof SqlBaseParser.QueryPrimaryDefaultContext) {
            return analyzeQuerySpecification(((SqlBaseParser.QueryPrimaryDefaultContext) ctx).querySpecification(), scope);
        }
        if (ctx instanceof SqlBaseParser.SubqueryContext) {
            return analyzeQueryNoWith(((SqlBaseParser.SubqueryContext) ctx).queryNoWith(), new Scope(scope));
        }
        if (ctx instanceof SqlBaseParser.TableContext) {
            SqlBaseParser.QualifiedNameContext table = ((SqlBaseParser.TableContext) ctx).qualifiedName();
            TableIdentifier identifier = addAccess(table, TableAccessRole.READ);
            Binding binding = physicalBinding(identifier, identifier.getTable(), dynamic(table));
            Scope resultScope = new Scope(scope); resultScope.bindings.add(binding);
            return new Shape(new ArrayList<>(binding.outputs), resultScope);
        }
        SqlBaseParser.InlineTableContext inline = (SqlBaseParser.InlineTableContext) ctx;
        List<List<SqlBaseParser.ExpressionContext>> rows = new ArrayList<>();
        for (SqlBaseParser.ExpressionContext expression : inline.expression()) {
            List<SqlBaseParser.RowConstructorContext> constructors =
                    descendants(expression, SqlBaseParser.RowConstructorContext.class);
            if (constructors.size() == 1 && text(constructors.get(0)).equals(text(expression))) {
                rows.add(constructors.get(0).expression());
            } else {
                rows.add(Collections.singletonList(expression));
            }
        }
        int width = rows.isEmpty() ? 0 : rows.get(0).size();
        List<Output> outputs = new ArrayList<>();
        for (int ordinal = 0; ordinal < width; ordinal++) {
            List<SourceColumn> sources = new ArrayList<>();
            String expressionText = "";
            for (List<SqlBaseParser.ExpressionContext> row : rows) {
                if (row.size() != width) {
                    diagnostic("VALUES_COLUMN_COUNT_MISMATCH", DiagnosticSeverity.ERROR,
                            "VALUES 各行列数不一致：" + width + " / " + row.size(), inline);
                    continue;
                }
                SqlBaseParser.ExpressionContext expression = row.get(ordinal);
                if (expressionText.isEmpty()) expressionText = text(expression);
                sources.addAll(resolveExpression(expression, scope));
            }
            outputs.add(new Output("_col_" + ordinal, expressionText, sources));
        }
        return new Shape(outputs, scope);
    }

    private Shape analyzeQuerySpecification(SqlBaseParser.QuerySpecificationContext ctx, Scope outer) {
        Scope scope = new Scope(outer);
        for (SqlBaseParser.RelationContext relation : ctx.relation()) analyzeRelation(relation, scope);
        List<Output> outputs = new ArrayList<>();
        int ordinal = 0;
        for (SqlBaseParser.SelectItemContext item : ctx.selectItem()) {
            if (item instanceof SqlBaseParser.SelectAllContext) {
                SqlBaseParser.SelectAllContext all = (SqlBaseParser.SelectAllContext) item;
                String qualifier = all.qualifiedName() == null ? null : clean(all.qualifiedName().getText());
                List<Binding> selected = qualifier == null ? scope.bindings : scope.matching(qualifier);
                if (selected.isEmpty()) {
                    diagnostic("UNKNOWN_QUALIFIER", DiagnosticSeverity.WARNING,
                            "找不到通配符限定关系：" + qualifier, all);
                }
                for (Binding binding : selected) {
                    if (binding.outputs.isEmpty()) {
                        List<SourceColumn> wildcard = binding.table == null ? Collections.emptyList()
                                : Collections.singletonList(new SourceColumn(binding.table, "*", false));
                        outputs.add(new Output("*", text(all), wildcard));
                        diagnostic("WILDCARD_NOT_EXPANDED", DiagnosticSeverity.WARNING,
                                "缺少元数据，无法展开 " + (qualifier == null ? "*" : qualifier + ".*"), all);
                    } else {
                        for (Output output : binding.outputs) outputs.add(output.copy());
                    }
                }
                continue;
            }
            ParserRuleContext expression;
            List<String> aliases = new ArrayList<>();
            if (item instanceof SqlBaseParser.SelectSingleContext) {
                SqlBaseParser.SelectSingleContext single = (SqlBaseParser.SelectSingleContext) item;
                expression = single.expression();
                if (single.identifier() != null) aliases.add(clean(single.identifier().getText()));
            } else {
                SqlBaseParser.SelectMultiContext multi = (SqlBaseParser.SelectMultiContext) item;
                expression = multi.primaryExpression();
                aliases.addAll(identifiers(multi.identifier()));
            }
            List<SourceColumn> sources = lineageSources(expression, resolveExpression(expression, scope));
            addUsage(ColumnUsageType.SELECT, expression, sources);
            collectInlineWindowUsages(expression, scope);
            if (aliases.isEmpty()) aliases.add(inferName(expression, ordinal));
            for (String alias : aliases) outputs.add(new Output(alias, text(expression), sources));
            ordinal++;
        }
        if (ctx.where != null) addUsage(ColumnUsageType.FILTER, ctx.where, resolveExpression(ctx.where, scope));
        for (SqlBaseParser.GroupingElementContext group : ctx.groupingElement()) {
            addUsage(ColumnUsageType.GROUP_BY, group, resolveExpression(group, scope));
        }
        if (ctx.having != null) addUsage(ColumnUsageType.HAVING, ctx.having, resolveExpression(ctx.having, scope));
        for (SqlBaseParser.WindowDefinitionContext window : ctx.windowDefinition()) {
            for (SqlBaseParser.ExpressionContext expression : window.expression()) {
                addUsage(ColumnUsageType.WINDOW_PARTITION, expression, resolveExpression(expression, scope));
            }
            for (SqlBaseParser.SortItemContext item : window.sortItem()) {
                addUsage(ColumnUsageType.WINDOW_ORDER, item, resolveExpression(item, scope));
            }
        }
        return new Shape(outputs, scope);
    }

    private void analyzeRelation(SqlBaseParser.RelationContext ctx, Scope scope) {
        if (ctx instanceof SqlBaseParser.RelationDefaultContext) {
            analyzeSampled(((SqlBaseParser.RelationDefaultContext) ctx).sampledRelation(), scope); return;
        }
        SqlBaseParser.JoinRelationContext join = (SqlBaseParser.JoinRelationContext) ctx;
        Scope leftScope = new Scope(scope);
        analyzeRelation(join.left, leftScope);
        Scope rightScope = new Scope(leftScope);
        if (join.right != null) analyzeSampled(join.right, rightScope);
        else if (join.rightRelation != null) analyzeRelation(join.rightRelation, rightScope);
        for (Binding binding : leftScope.bindings) scope.add(binding);
        for (Binding binding : rightScope.bindings) scope.add(binding);
        List<SourceColumn> columns = new ArrayList<>();
        String condition = "";
        if (join.joinCriteria() != null) {
            condition = text(join.joinCriteria());
            if (join.joinCriteria().USING() != null) {
                for (SqlBaseParser.IdentifierContext name : join.joinCriteria().identifier()) {
                    columns.addAll(joinColumns(leftScope, rightScope, clean(name.getText())));
                }
            } else {
                columns.addAll(resolveExpression(join.joinCriteria(), scope));
            }
            addUsage(ColumnUsageType.JOIN, join.joinCriteria(), columns);
        } else if (join.NATURAL() != null) {
            columns.addAll(naturalJoinColumns(leftScope, rightScope));
            condition = "NATURAL";
            addUsage(ColumnUsageType.JOIN, join, columns);
        }
        List<SourceColumn> uniqueColumns = unique(columns);
        joins.add(new JoinRelation(joinType(join), condition,
                joinSide(uniqueColumns, leftScope), joinSide(uniqueColumns, rightScope)));
    }

    private void analyzeSampled(SqlBaseParser.SampledRelationContext sampled, Scope scope) {
        SqlBaseParser.AliasedRelationContext ctx = sampled.aliasedRelation();
        SqlBaseParser.RelationPrimaryContext primary = ctx.relationPrimary();
        Binding binding;
        if (primary instanceof SqlBaseParser.TableNameContext) {
            SqlBaseParser.QualifiedNameContext table = ((SqlBaseParser.TableNameContext) primary).qualifiedName();
            String raw = clean(table.getText());
            Binding cte = raw.contains(".") ? null : scope.cte(raw);
            if (cte != null) binding = cte.copy();
            else {
                TableIdentifier identifier = addAccess(table, TableAccessRole.READ);
                binding = physicalBinding(identifier, identifier.getTable(), dynamic(table));
            }
        } else if (primary instanceof SqlBaseParser.SubqueryRelationContext) {
            Shape shape = analyzeQuery(((SqlBaseParser.SubqueryRelationContext) primary).query(), scope);
            binding = new Binding("", null, shape.outputs, false);
        } else if (primary instanceof SqlBaseParser.ParenthesizedRelationContext) {
            Scope nested = new Scope(scope);
            analyzeRelation(((SqlBaseParser.ParenthesizedRelationContext) primary).relation(), nested);
            for (Binding item : nested.bindings) scope.add(item);
            return;
        } else {
            SqlBaseParser.UnnestContext unnest = (SqlBaseParser.UnnestContext) primary;
            List<SourceColumn> sources = new ArrayList<>();
            for (SqlBaseParser.ExpressionContext expression : unnest.expression()) {
                sources.addAll(resolveExpression(expression, scope));
            }
            List<String> aliases = ctx.columnAliases() == null ? Collections.singletonList("_unnest")
                    : identifiers(ctx.columnAliases().identifier());
            List<Output> outputs = aliases.stream().map(name -> new Output(name, text(unnest), sources))
                    .collect(Collectors.toList());
            binding = new Binding("", null, outputs, false);
        }
        if (ctx.identifier() != null) binding.alias = clean(ctx.identifier().getText());
        if (ctx.columnAliases() != null && !(primary instanceof SqlBaseParser.UnnestContext)) {
            renameOutputs(binding.outputs, identifiers(ctx.columnAliases().identifier()));
        }
        scope.add(binding);
        for (SqlBaseParser.LateralViewContext lateral : ctx.lateralView()) {
            List<SourceColumn> sources = new ArrayList<>();
            for (SqlBaseParser.ExpressionContext expression : lateral.expression()) {
                sources.addAll(resolveExpression(expression, scope));
            }
            String alias = lateral.identifier() == null ? "" : clean(lateral.identifier().getText());
            List<Output> outputs = new ArrayList<>();
            for (SqlBaseParser.QualifiedNameContext name : descendants(lateral.lateralViewSet(), SqlBaseParser.QualifiedNameContext.class)) {
                outputs.add(new Output(lastPart(clean(name.getText())), text(lateral), sources));
            }
            scope.add(new Binding(alias, null, outputs, false));
        }
    }

    private Binding physicalBinding(TableIdentifier table, String alias, boolean dynamic) {
        Optional<TableSchema> schema = schema(table, dynamic);
        List<Output> outputs = new ArrayList<>();
        if (schema.isPresent()) {
            for (TableColumnMetadata column : schema.get().getColumns()) {
                outputs.add(new Output(column.getName(), column.getName(),
                        Collections.singletonList(new SourceColumn(table, column.getName(), true))));
            }
        }
        return new Binding(alias, table, outputs, !schema.isPresent());
    }

    private Optional<TableSchema> schema(TableIdentifier table, boolean dynamic) {
        if (dynamic) return Optional.empty();
        if (metadata.containsKey(table)) return metadata.get(table);
        Optional<TableSchema> result;
        try {
            result = request.getMetadataProvider().getTable(table);
            if (result == null) result = Optional.empty();
            if (!result.isPresent()) diagnostic("METADATA_UNAVAILABLE", DiagnosticSeverity.WARNING,
                    "未取得表结构：" + table.qualifiedName(), null);
        } catch (RuntimeException error) {
            result = Optional.empty();
            diagnostic("METADATA_UNAVAILABLE", DiagnosticSeverity.WARNING,
                    "读取表结构失败：" + table.qualifiedName() + "（" + rootMessage(error) + "）", null);
        }
        metadata.put(table, result); return result;
    }

    private List<SourceColumn> resolveExpression(ParseTree tree, Scope scope) {
        LinkedHashSet<SourceColumn> result = new LinkedHashSet<>();
        resolveNode(tree, scope, result, Collections.emptySet());
        return new ArrayList<>(result);
    }

    private void resolveNode(ParseTree node, Scope scope, Set<SourceColumn> result, Set<String> boundVariables) {
        if (node instanceof SqlBaseParser.QueryContext) {
            Shape shape = analyzeQuery((SqlBaseParser.QueryContext) node, scope);
            for (Output output : shape.outputs) result.addAll(output.sources);
            return;
        }
        if (node instanceof SqlBaseParser.LambdaContext) {
            SqlBaseParser.LambdaContext lambda = (SqlBaseParser.LambdaContext) node;
            Set<String> nested = new LinkedHashSet<>(boundVariables);
            for (SqlBaseParser.IdentifierContext name : lambda.identifier()) {
                nested.add(clean(name.getText()).toLowerCase(Locale.ROOT));
            }
            resolveNode(lambda.expression(), scope, result, nested);
            return;
        }
        if (node instanceof SqlBaseParser.DereferenceContext && !(node.getParent() instanceof SqlBaseParser.DereferenceContext)) {
            String value = clean(node.getText());
            int dot = value.lastIndexOf('.');
            if (dot > 0) {
                result.addAll(resolveColumn(value.substring(0, dot), value.substring(dot + 1), scope, (ParserRuleContext) node));
                return;
            }
        }
        if (node instanceof SqlBaseParser.ColumnReferenceContext
                && !(node.getParent() instanceof SqlBaseParser.DereferenceContext)) {
            String column = clean(((SqlBaseParser.ColumnReferenceContext) node).identifier().getText());
            if (request.getDialect() == SqlDialect.HIVE && text(node).startsWith("\"")) return;
            if (boundVariables.contains(column.toLowerCase(Locale.ROOT))) return;
            result.addAll(resolveColumn(null, column, scope, (ParserRuleContext) node));
            return;
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            resolveNode(node.getChild(index), scope, result, boundVariables);
        }
    }

    private List<SourceColumn> resolveColumn(String qualifier, String column, Scope scope, ParserRuleContext location) {
        List<Binding> bindings = qualifier == null ? scope.visibleBindings() : scope.matching(qualifier);
        List<SourceColumn> result = new ArrayList<>();
        List<Binding> candidates = new ArrayList<>();
        for (Binding binding : bindings) {
            Output output = binding.output(column);
            if (output != null) { result.addAll(output.sources); candidates.add(binding); }
            else if (binding.metadataUnknown && binding.table != null) {
                result.add(new SourceColumn(binding.table, column, false)); candidates.add(binding);
            }
        }
        if (candidates.size() > 1) {
            diagnostic("AMBIGUOUS_COLUMN", DiagnosticSeverity.WARNING,
                    "字段 " + (qualifier == null ? column : qualifier + "." + column)
                            + " 同时匹配 " + candidates.size() + " 个关系", location);
        } else if (candidates.isEmpty() && scope.parent != null) {
            return resolveColumn(qualifier, column, scope.parent, location);
        } else if (candidates.isEmpty()) {
            diagnostic("UNRESOLVED_COLUMN", DiagnosticSeverity.WARNING,
                    "无法解析字段：" + (qualifier == null ? column : qualifier + "." + column), location);
        }
        return unique(result);
    }

    private void collectInlineWindowUsages(ParseTree tree, Scope scope) {
        for (SqlBaseParser.OverContext over : descendants(tree, SqlBaseParser.OverContext.class)) {
            for (SqlBaseParser.ExpressionContext expression : over.expression()) {
                addUsage(ColumnUsageType.WINDOW_PARTITION, expression, resolveExpression(expression, scope));
            }
            for (SqlBaseParser.SortItemContext item : over.sortItem()) {
                addUsage(ColumnUsageType.WINDOW_ORDER, item, resolveExpression(item, scope));
            }
        }
    }

    private List<SourceColumn> naturalJoinColumns(Scope left, Scope right) {
        List<SourceColumn> result = new ArrayList<>();
        for (Binding a : left.bindings) for (Output output : a.outputs) {
            for (Binding b : right.bindings) {
                Output matching = b.output(output.name);
                if (matching != null) { result.addAll(output.sources); result.addAll(matching.sources); }
            }
        }
        if (result.isEmpty()) diagnostic("NATURAL_JOIN_METADATA_REQUIRED", DiagnosticSeverity.WARNING,
                "NATURAL JOIN 缺少可用于匹配同名字段的元数据", null);
        return unique(result);
    }

    private List<Output> targetColumns(TableIdentifier target, List<String> explicit, List<Output> source,
                                       Set<String> excludedColumns) {
        List<String> names = explicit == null ? new ArrayList<>() : new ArrayList<>(explicit);
        if (names.isEmpty()) {
            Optional<TableSchema> targetSchema = schema(target, false);
            if (targetSchema.isPresent()) {
                for (TableColumnMetadata column : targetSchema.get().getColumns()) {
                    if (!excludedColumns.contains(column.getName().toLowerCase(Locale.ROOT))) names.add(column.getName());
                }
            }
        }
        List<Output> result = new ArrayList<>();
        for (int index = 0; index < source.size(); index++) {
            Output item = source.get(index);
            String name = index < names.size() ? names.get(index) : item.name;
            result.add(new Output(name, item.expression, item.sources));
        }
        boolean unresolvedWildcard = source.stream().anyMatch(item -> "*".equals(item.name));
        if (!names.isEmpty() && !unresolvedWildcard && names.size() != source.size()) {
            diagnostic("TARGET_COLUMN_COUNT_MISMATCH", DiagnosticSeverity.ERROR,
                    "目标字段数与查询输出列数不一致：" + names.size() + " / " + source.size(), null);
        }
        return result;
    }

    private void emitOutput(TableIdentifier target, List<Output> outputs) {
        for (int index = 0; index < outputs.size(); index++) {
            Output output = outputs.get(index);
            lineages.add(new ColumnLineage(target, output.name, index, output.expression, output.sources));
        }
    }

    private TableIdentifier addAccess(SqlBaseParser.QualifiedNameContext context, TableAccessRole role) {
        TableIdentifier table = identifier(context);
        int start = context.getStart().getStartIndex();
        int end = context.getStop().getStopIndex() + 1;
        String raw = text(context);
        accesses.add(new TableAccess(table, role, start, end, raw.contains("${") || raw.contains("#{")));
        return table;
    }

    private TableIdentifier identifier(SqlBaseParser.QualifiedNameContext context) {
        return TableIdentifier.parse(text(context), request.getDefaultCatalog(), request.getDefaultDatabase());
    }

    private boolean dynamic(SqlBaseParser.QualifiedNameContext context) {
        String value = text(context);
        return value.contains("${") || value.contains("#{");
    }

    private List<SourceColumn> joinColumns(Scope left, Scope right, String column) {
        List<SourceColumn> result = new ArrayList<>();
        for (Binding binding : left.bindings) {
            Output output = binding.output(column);
            if (output != null) result.addAll(output.sources);
            else if (binding.metadataUnknown && binding.table != null) {
                result.add(new SourceColumn(binding.table, column, false));
            }
        }
        for (Binding binding : right.bindings) {
            Output output = binding.output(column);
            if (output != null) result.addAll(output.sources);
            else if (binding.metadataUnknown && binding.table != null) {
                result.add(new SourceColumn(binding.table, column, false));
            }
        }
        return unique(result);
    }

    private List<SourceColumn> joinSide(List<SourceColumn> columns, Scope scope) {
        Set<TableIdentifier> tables = new LinkedHashSet<>();
        for (Binding binding : scope.bindings) {
            if (binding.table != null) tables.add(binding.table);
            for (Output output : binding.outputs) {
                for (SourceColumn source : output.sources) tables.add(source.getTable());
            }
        }
        return columns.stream().filter(column -> tables.contains(column.getTable())).collect(Collectors.toList());
    }

    private List<SourceColumn> lineageSources(ParserRuleContext expression, List<SourceColumn> sources) {
        if (isDirectExpression(expression)) return sources;
        return sources.stream().map(source -> new SourceColumn(source.getTable(), source.getColumn(), false))
                .collect(Collectors.toList());
    }

    private boolean isDirectExpression(ParseTree expression) {
        if (expression instanceof SqlBaseParser.ColumnReferenceContext
                || expression instanceof SqlBaseParser.DereferenceContext) return true;
        if (expression instanceof SqlBaseParser.ExpressionContext
                || expression instanceof SqlBaseParser.BooleanDefaultContext
                || expression instanceof SqlBaseParser.PredicatedContext
                || expression instanceof SqlBaseParser.ValueExpressionDefaultContext) {
            return expression.getChildCount() == 1 && isDirectExpression(expression.getChild(0));
        }
        if (expression instanceof SqlBaseParser.ParenthesizedExpressionContext) {
            return isDirectExpression(((SqlBaseParser.ParenthesizedExpressionContext) expression).expression());
        }
        return false;
    }

    private void markReadWrite(TableIdentifier target) {
        for (int index = 0; index < accesses.size(); index++) {
            TableAccess access = accesses.get(index);
            if (access.getTable().equals(target) && access.getRole() == TableAccessRole.READ) {
                accesses.set(index, new TableAccess(target, TableAccessRole.READ_WRITE,
                        access.getStartOffset(), access.getEndOffset(), access.isDynamic()));
                return;
            }
        }
    }

    private List<TableAccess> compactAccesses() {
        Map<String, TableAccess> result = new LinkedHashMap<>();
        for (TableAccess access : accesses) {
            String key = access.getTable().normalizedName() + ":" + access.getStartOffset();
            TableAccess previous = result.get(key);
            if (previous == null) result.put(key, access);
            else if (previous.getRole() != access.getRole()) {
                result.put(key, new TableAccess(access.getTable(), TableAccessRole.READ_WRITE,
                        access.getStartOffset(), access.getEndOffset(), access.isDynamic()));
            }
        }
        return new ArrayList<>(result.values());
    }

    private SqlStatementType statementType(SqlBaseParser.StatementContext statement) {
        if (statement instanceof SqlBaseParser.StatementDefaultContext) {
            return ((SqlBaseParser.StatementDefaultContext) statement).query().with() == null
                    ? SqlStatementType.SELECT : SqlStatementType.WITH;
        }
        if (statement instanceof SqlBaseParser.InsertIntoContext) {
            SqlBaseParser.InsertIntoContext insert = (SqlBaseParser.InsertIntoContext) statement;
            if (insert.REPLACE() != null) return SqlStatementType.REPLACE;
            return insert.with() == null ? SqlStatementType.INSERT : SqlStatementType.WITH;
        }
        if (statement instanceof SqlBaseParser.CreateTableAsSelectContext) return SqlStatementType.CREATE_TABLE_AS_SELECT;
        if (statement instanceof SqlBaseParser.CreateTemporaryViewContext) return SqlStatementType.CREATE_TEMPORARY_VIEW;
        if (statement instanceof SqlBaseParser.CreateViewContext) return SqlStatementType.CREATE_VIEW;
        if (statement instanceof SqlBaseParser.UpdateContext) return SqlStatementType.UPDATE;
        if (statement instanceof SqlBaseParser.DeleteContext) return SqlStatementType.DELETE;
        if (statement instanceof SqlBaseParser.SetSessionContext) return SqlStatementType.SET;
        if (statement instanceof SqlBaseParser.UseContext) return SqlStatementType.USE;
        return SqlStatementType.OTHER;
    }

    private void addUsage(ColumnUsageType type, ParserRuleContext context, List<SourceColumn> columns) {
        usages.add(new ColumnUsage(type, text(context), columns));
    }

    private String inferName(ParserRuleContext expression, int ordinal) {
        List<SqlBaseParser.DereferenceContext> dereferences = descendants(expression, SqlBaseParser.DereferenceContext.class);
        if (dereferences.size() == 1 && text(expression).equals(text(dereferences.get(0)))) {
            return lastPart(clean(text(expression)));
        }
        List<SqlBaseParser.ColumnReferenceContext> columns = descendants(expression, SqlBaseParser.ColumnReferenceContext.class);
        if (columns.size() == 1 && text(expression).equals(text(columns.get(0)))) {
            return clean(columns.get(0).identifier().getText());
        }
        return "_col_" + ordinal;
    }

    private String joinType(SqlBaseParser.JoinRelationContext join) {
        if (join.CROSS() != null) return "CROSS";
        if (join.NATURAL() != null) return "NATURAL " + (join.joinType() == null ? "INNER" : clean(text(join.joinType())).toUpperCase(Locale.ROOT));
        String value = join.joinType() == null ? "INNER" : clean(text(join.joinType())).toUpperCase(Locale.ROOT);
        return value.isEmpty() ? "INNER" : value;
    }

    private String text(ParseTree context) {
        if (!(context instanceof ParserRuleContext)) return context == null ? "" : context.getText();
        ParserRuleContext rule = (ParserRuleContext) context;
        if (rule.getStart() == null || rule.getStop() == null) return rule.getText();
        int start = Math.max(0, rule.getStart().getStartIndex());
        int end = Math.min(request.getSql().length(), rule.getStop().getStopIndex() + 1);
        return end >= start ? request.getSql().substring(start, end) : rule.getText();
    }

    private void diagnostic(String code, DiagnosticSeverity severity, String message, ParserRuleContext ctx) {
        Token token = ctx == null ? null : ctx.getStart();
        diagnostics.add(new LineageDiagnostic(code, severity, message,
                token == null ? 1 : token.getLine(), token == null ? 0 : token.getCharPositionInLine(),
                token == null ? -1 : token.getStartIndex(), ctx == null || ctx.getStop() == null
                        ? -1 : ctx.getStop().getStopIndex() + 1));
    }

    private static <T extends ParseTree> List<T> descendants(ParseTree root, Class<T> type) {
        List<T> result = new ArrayList<>();
        if (root == null) return result;
        if (type.isInstance(root)) result.add(type.cast(root));
        for (int index = 0; index < root.getChildCount(); index++) result.addAll(descendants(root.getChild(index), type));
        return result;
    }

    private List<String> identifiers(List<SqlBaseParser.IdentifierContext> values) {
        return values.stream().map(item -> clean(item.getText())).collect(Collectors.toList());
    }

    private void renameOutputs(List<Output> outputs, List<String> names) {
        int count = Math.min(outputs.size(), names.size());
        for (int index = 0; index < count; index++) outputs.get(index).name = names.get(index);
        if (outputs.size() != names.size()) diagnostic("ALIAS_COLUMN_COUNT_MISMATCH", DiagnosticSeverity.WARNING,
                "关系别名字段数与输出列数不一致", null);
    }

    private String clean(String value) { return TableIdentifier.unquote(value == null ? "" : value.trim()); }
    private String lastPart(String value) { int dot = value.lastIndexOf('.'); return dot < 0 ? value : value.substring(dot + 1); }
    private List<SourceColumn> unique(List<SourceColumn> values) { return new ArrayList<>(new LinkedHashSet<>(values)); }
    private String rootMessage(Throwable error) { Throwable value = error; while (value.getCause() != null) value = value.getCause(); return value.getMessage() == null ? value.getClass().getSimpleName() : value.getMessage(); }

    private static final class Shape {
        private final List<Output> outputs;
        private final Scope scope;
        private Shape(List<Output> outputs, Scope scope) { this.outputs = outputs; this.scope = scope; }
    }

    private static final class Output {
        private String name;
        private final String expression;
        private final List<SourceColumn> sources;
        private Output(String name, String expression, List<SourceColumn> sources) {
            this.name = name; this.expression = expression; this.sources = new ArrayList<>(new LinkedHashSet<>(sources));
        }
        private Output copy() { return new Output(name, expression, sources); }
    }

    private static final class Binding {
        private String alias;
        private final TableIdentifier table;
        private final List<Output> outputs;
        private final boolean metadataUnknown;
        private Binding(String alias, TableIdentifier table, List<Output> outputs, boolean metadataUnknown) {
            this.alias = alias == null ? "" : alias; this.table = table;
            this.outputs = new ArrayList<>(); for (Output output : outputs) this.outputs.add(output.copy());
            this.metadataUnknown = metadataUnknown;
        }
        private Output output(String name) {
            for (Output output : outputs) if (output.name.equalsIgnoreCase(name)) return output;
            return null;
        }
        private boolean matches(String value) {
            if (!alias.isEmpty() && alias.equalsIgnoreCase(value)) return true;
            if (table == null) return false;
            return table.getTable().equalsIgnoreCase(value)
                    || table.qualifiedName().equalsIgnoreCase(value)
                    || (!table.getDatabase().isEmpty() && (table.getDatabase() + "." + table.getTable()).equalsIgnoreCase(value));
        }
        private Binding copy() { return new Binding(alias, table, outputs, metadataUnknown); }
    }

    private static final class Scope {
        private final Scope parent;
        private final List<Binding> bindings = new ArrayList<>();
        private final Map<String, Binding> ctes = new LinkedHashMap<>();
        private Scope(Scope parent) { this.parent = parent; }
        private void add(Binding value) {
            if (!bindings.contains(value)) bindings.add(value);
        }
        private Binding cte(String name) {
            Binding value = ctes.get(name.toLowerCase(Locale.ROOT));
            return value != null ? value : parent == null ? null : parent.cte(name);
        }
        private List<Binding> matching(String qualifier) {
            List<Binding> result = new ArrayList<>();
            for (Binding binding : visibleBindings()) if (binding.matches(qualifier)) result.add(binding);
            return result;
        }
        private List<Binding> visibleBindings() {
            if (!bindings.isEmpty()) return new ArrayList<>(bindings);
            return parent == null ? new ArrayList<>() : parent.visibleBindings();
        }
    }
}
