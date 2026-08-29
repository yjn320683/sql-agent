package com.yjn.sqlagent.datacompare.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** 使用版本参数定义中的默认值渲染验数 SQL。 */
@Service
public class SqlParameterRenderer {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
    private static final TypeReference<List<Map<String, Object>>> DEFINITIONS =
            new TypeReference<List<Map<String, Object>>>() { };
    private final ObjectMapper json;

    public SqlParameterRenderer(ObjectMapper json) {
        this.json = json;
    }

    public String renderDefaults(String sql, String parameterSchema) {
        Map<String, Map<String, Object>> definitions = definitions(parameterSchema);
        Matcher matcher = PLACEHOLDER.matcher(sql == null ? "" : sql);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            Map<String, Object> definition = definitions.get(name);
            if (definition == null) throw new IllegalArgumentException("SQL引用了未声明的运行参数：" + name);
            Object value = definition.get("defaultValue");
            if (value == null || "".equals(value)) {
                throw new IllegalArgumentException("版本验数需要为运行参数配置默认值：" + name);
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(literal(name, definition, value)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private Map<String, Map<String, Object>> definitions(String schema) {
        if (schema == null || schema.trim().isEmpty()) return new LinkedHashMap<>();
        try {
            Map<String, Map<String, Object>> result = new LinkedHashMap<>();
            for (Map<String, Object> item : json.readValue(schema, DEFINITIONS)) {
                String name = string(item.get("name")).trim();
                if (!name.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) {
                    throw new IllegalArgumentException("参数名称非法：" + name);
                }
                if (result.put(name, item) != null) throw new IllegalArgumentException("参数名称重复：" + name);
            }
            return result;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("任务参数定义不是有效JSON", error);
        }
    }

    private String literal(String name, Map<String, Object> definition, Object value) {
        String type = string(definition.get("type")).trim().toUpperCase(Locale.ROOT);
        try {
            switch (type) {
                case "STRING":
                    return "'" + string(value).replace("'", "''") + "'";
                case "INTEGER":
                    return new BigDecimal(string(value)).toBigIntegerExact().toString();
                case "DECIMAL":
                    return new BigDecimal(string(value)).stripTrailingZeros().toPlainString();
                case "DATE":
                    return "'" + LocalDate.parse(string(value)) + "'";
                case "DATETIME":
                    return "'" + datetime(value) + "'";
                case "BOOLEAN":
                    if (value instanceof Boolean) return (Boolean) value ? "TRUE" : "FALSE";
                    if ("true".equalsIgnoreCase(string(value)) || "1".equals(string(value))) return "TRUE";
                    if ("false".equalsIgnoreCase(string(value)) || "0".equals(string(value))) return "FALSE";
                    throw new IllegalArgumentException("BOOLEAN参数值非法");
                default:
                    throw new IllegalArgumentException("参数类型非法");
            }
        } catch (ArithmeticException | DateTimeParseException error) {
            throw new IllegalArgumentException("版本验数参数默认值非法：" + name, error);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("版本验数参数默认值非法：" + name, error);
        } catch (IllegalArgumentException error) {
            if (error.getMessage() != null && error.getMessage().startsWith("版本验数")) throw error;
            throw new IllegalArgumentException("版本验数参数默认值非法：" + name, error);
        }
    }

    private String datetime(Object value) {
        String text = string(value).trim();
        try {
            return OffsetDateTime.parse(text).toLocalDateTime().toString().replace('T', ' ');
        } catch (DateTimeParseException ignored) {
            return LocalDateTime.parse(text.replace(' ', 'T')).toString().replace('T', ' ');
        }
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString();
    }
}
