package com.yjn.sqlagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjn.sqlagent.common.ErrorCode;
import com.yjn.sqlagent.exception.BusinessException;
import com.yjn.sqlagent.model.dto.SqlTaskParameterDTO;
import com.yjn.sqlagent.model.entity.SqlTaskVersionStep;
import com.yjn.sqlagent.parsesql.ParseRequest;
import com.yjn.sqlagent.parsesql.SqlDialect;
import com.yjn.sqlagent.parsesql.SqlLineageParser;
import com.yjn.sqlagent.parsesql.SqlParseException;
import com.yjn.sqlagent.parsesql.SqlStatementType;
import com.yjn.sqlagent.parsesql.StatementLineage;
import com.yjn.sqlagent.parsesql.TableMetadataProvider;
import com.yjn.sqlagent.parsesql.LineageDiagnostic;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class TaskSqlStructureService {
    private static final Pattern STEP_MARKER = Pattern.compile(
            "(?m)^\\s*====\\s*step\\s*:\\s*(\\d+)(?:\\s*:\\s*([^=\\r\\n]+))?\\s*====\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> PARAMETER_TYPES;
    private static final TypeReference<List<SqlTaskParameterDTO>> PARAMETER_LIST =
            new TypeReference<List<SqlTaskParameterDTO>>() { };

    static {
        Set<String> types = new HashSet<>();
        Collections.addAll(types, "STRING", "INTEGER", "DECIMAL", "DATE", "DATETIME", "BOOLEAN");
        PARAMETER_TYPES = Collections.unmodifiableSet(types);
    }

    private final ObjectMapper objectMapper;
    private final SqlLineageParser sqlParser;
    private final TableMetadataProvider metadataProvider;

    public TaskSqlStructureService(ObjectMapper objectMapper) {
        this(objectMapper, TableMetadataProvider.NONE);
    }

    @Autowired
    public TaskSqlStructureService(ObjectMapper objectMapper, BackendHiveTableMetadataProvider metadataProvider) {
        this(objectMapper, (TableMetadataProvider) metadataProvider);
    }

    TaskSqlStructureService(ObjectMapper objectMapper, TableMetadataProvider metadataProvider) {
        this.objectMapper = objectMapper;
        this.sqlParser = new SqlLineageParser();
        this.metadataProvider = metadataProvider;
    }

    public String serializeParameters(List<SqlTaskParameterDTO> parameters) {
        List<SqlTaskParameterDTO> normalized = parameters == null ? Collections.emptyList() : parameters;
        Set<String> names = new HashSet<>();
        for (SqlTaskParameterDTO parameter : normalized) {
            String name = parameter.getName() == null ? "" : parameter.getName().trim();
            String type = parameter.getType() == null ? "" : parameter.getType().trim().toUpperCase(Locale.ROOT);
            if (!PARAMETER_TYPES.contains(type)) {
                throw badRequest("参数 " + name + " 的类型非法");
            }
            if (!names.add(name)) {
                throw badRequest("参数名称重复：" + name);
            }
            parameter.setName(name);
            parameter.setType(type);
            if (parameter.getDescription() != null) parameter.setDescription(parameter.getDescription().trim());
            if (parameter.getRequired() == null) parameter.setRequired(Boolean.TRUE);
        }
        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (Exception exc) {
            throw badRequest("任务参数无法序列化");
        }
    }

    public List<SqlTaskParameterDTO> deserializeParameters(String value) {
        if (value == null || value.trim().isEmpty()) return Collections.emptyList();
        try {
            return objectMapper.readValue(value, PARAMETER_LIST);
        } catch (Exception exc) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR.getCode(), "任务参数配置损坏");
        }
    }

    public List<SqlTaskVersionStep> parseVersionSteps(long taskId, int versionNo, String sql) {
        return analyzeVersion(taskId, versionNo, sql).getSteps();
    }

    /** 每个 Step 只解析一次，同时产出保存结构和不可变血缘事实。 */
    public VersionAnalysis analyzeVersion(long taskId, int versionNo, String sql) {
        String source = sql == null ? "" : sql.trim();
        if (source.isEmpty()) throw badRequest("SQL不能为空");
        List<StepPart> parts = split(source);
        Set<Integer> numbers = new HashSet<>();
        int previous = -1;
        List<SqlTaskVersionStep> result = new ArrayList<>();
        List<StatementLineage> statements = new ArrayList<>();
        for (int order = 0; order < parts.size(); order++) {
            StepPart part = parts.get(order);
            if (!numbers.add(part.number)) throw badRequest("Step编号重复：" + part.number);
            if (part.number <= previous) throw badRequest("Step编号必须按脚本顺序递增");
            previous = part.number;
            StatementLineage parsed = parseStatement(part.sql);
            statements.add(parsed);
            SqlTaskVersionStep row = new SqlTaskVersionStep();
            row.setTaskId(taskId);
            row.setVersionNo(versionNo);
            row.setStepNo(part.number);
            row.setStepOrder(order);
            row.setStepName(part.name == null ? "Step " + part.number : part.name);
            row.setStepSql(part.sql);
            row.setStatementType(parsed.getStatementType().name());
            row.setInputTables(serializeTables(parsed.getInputTables()));
            row.setOutputTables(serializeTables(parsed.getOutputTables()));
            row.setSqlChecksum(checksum(part.sql, "[]"));
            row.setCreateTime(LocalDateTime.now());
            result.add(row);
        }
        return new VersionAnalysis(result, TaskLineageFacts.from(statements, Collections.emptyList()));
    }

    /** 查询历史或当前代码时使用的容错脚本解析，不执行 SQL。 */
    public TaskLineageFacts analyzeLineage(String sql, String defaultDatabase) {
        String source = sql == null ? "" : sql.trim();
        if (source.isEmpty()) throw badRequest("SQL不能为空");
        try {
            com.yjn.sqlagent.parsesql.SqlScriptLineage parsed = sqlParser.parseScript(ParseRequest.builder(source)
                    .dialect(SqlDialect.HIVE)
                    .defaultDatabase(defaultDatabase)
                    .metadataProvider(metadataProvider)
                    .mode(com.yjn.sqlagent.parsesql.ParseMode.TOLERANT)
                    .build());
            return TaskLineageFacts.from(parsed.getStatements(), parsed.getDiagnostics());
        } catch (SqlParseException exception) {
            throw badRequest(exception.getMessage());
        }
    }

    public String checksum(String sql, String parameterSchema) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(((sql == null ? "" : sql.trim()) + "\n"
                    + (parameterSchema == null ? "[]" : parameterSchema)).getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) value.append(String.format("%02x", item));
            return value.toString();
        } catch (Exception exc) {
            throw new IllegalStateException("无法计算SQL校验和", exc);
        }
    }

    private List<StepPart> split(String sql) {
        Matcher matcher = STEP_MARKER.matcher(sql);
        List<Marker> markers = new ArrayList<>();
        while (matcher.find()) {
            markers.add(new Marker(matcher.start(), matcher.end(), Integer.parseInt(matcher.group(1)),
                    normalizeName(matcher.group(2))));
        }
        if (markers.isEmpty()) return Collections.singletonList(new StepPart(0, "Step 0", sql));
        if (!sql.substring(0, markers.get(0).start).trim().isEmpty()) {
            throw badRequest("首个Step标记前不能包含SQL");
        }
        List<StepPart> result = new ArrayList<>();
        for (int index = 0; index < markers.size(); index++) {
            Marker marker = markers.get(index);
            int end = index + 1 < markers.size() ? markers.get(index + 1).start : sql.length();
            String stepSql = sql.substring(marker.end, end).trim();
            if (stepSql.isEmpty()) throw badRequest("Step " + marker.number + " 不能为空");
            result.add(new StepPart(marker.number, marker.name, stepSql));
        }
        return result;
    }

    private StatementLineage parseStatement(String sql) {
        List<String> statements = sqlParser.splitStatements(sql, SqlDialect.HIVE);
        if (statements.size() != 1) throw badRequest("每个Step只能包含一条SQL语句");
        try {
            StatementLineage parsed = sqlParser.parseStatement(ParseRequest.builder(statements.get(0))
                    .dialect(SqlDialect.HIVE).defaultDatabase("default")
                    .metadataProvider(metadataProvider).build());
            SqlStatementType type = parsed.getStatementType();
            if (type != SqlStatementType.SELECT && type != SqlStatementType.WITH
                    && type != SqlStatementType.INSERT) {
                throw badRequest("Step只允许SELECT、WITH或INSERT，当前为" + type.name());
            }
            return parsed;
        } catch (SqlParseException exception) {
            throw badRequest(exception.getMessage());
        }
    }

    private String serializeTables(List<String> tables) {
        try {
            return objectMapper.writeValueAsString(tables);
        } catch (Exception exception) {
            throw new IllegalStateException("无法序列化SQL表关系", exception);
        }
    }

    private String normalizeName(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        String normalized = value.trim();
        return normalized.length() > 128 ? normalized.substring(0, 128) : normalized;
    }

    private BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST.getCode(), message);
    }

    private static final class Marker {
        private final int start;
        private final int end;
        private final int number;
        private final String name;
        private Marker(int start, int end, int number, String name) {
            this.start = start; this.end = end; this.number = number; this.name = name;
        }
    }

    private static final class StepPart {
        private final int number;
        private final String name;
        private final String sql;
        private StepPart(int number, String name, String sql) {
            this.number = number; this.name = name; this.sql = sql;
        }
    }

    public static final class VersionAnalysis {
        private final List<SqlTaskVersionStep> steps;
        private final TaskLineageFacts lineage;

        private VersionAnalysis(List<SqlTaskVersionStep> steps, TaskLineageFacts lineage) {
            this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
            this.lineage = lineage;
        }

        public List<SqlTaskVersionStep> getSteps() { return steps; }
        public TaskLineageFacts getLineage() { return lineage; }
    }
}
