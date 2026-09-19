package com.yjn.sqlagent.service;

import com.yjn.sqlagent.model.dto.SqlQueryPreviewDTO;
import com.yjn.sqlagent.parsesql.ParseRequest;
import com.yjn.sqlagent.parsesql.SqlDialect;
import com.yjn.sqlagent.parsesql.SqlLineageParser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 先完成参数渲染，再通过语法树生成不可写的服务端限量查询。 */
@Service
public class SqlQueryPreviewService {
    private final AgentProxyService agent;
    private final SqlLineageParser parser = new SqlLineageParser();

    public SqlQueryPreviewService(AgentProxyService agent) {
        this.agent = agent;
    }

    public Map<String, Object> preview(SqlQueryPreviewDTO request) {
        request.setValidateParameterValues(Boolean.TRUE);
        Map<String, Object> structure = agent.previewSqlStructure(request);
        Map<String, Object> selected = selectStep(structure, request.getStepNo());
        String renderedSql = text(selected.get("renderedSql"));
        String query = parser.toReadOnlyPreviewQuery(ParseRequest.builder(renderedSql)
                .dialect(SqlDialect.HIVE).defaultDatabase(request.getDefaultDb()).build(), request.getLimit());
        Map<String, Object> result = new LinkedHashMap<>(agent.previewHiveQuery(
                query, request.getDefaultDb(), request.getLimit()));
        result.put("stepNo", selected.get("stepNo"));
        result.put("stepName", selected.get("stepName"));
        result.put("renderedSql", renderedSql);
        result.put("previewSql", query);
        result.put("parameters", structure.getOrDefault("parameters", Map.of()));
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> selectStep(Map<String, Object> structure, int stepNo) {
        Object value = structure.get("steps");
        if (!(value instanceof List<?>)) throw new IllegalArgumentException("SQL中没有可预览的Step");
        Map<String, Object> onlyStep = null;
        for (Object item : (List<?>) value) {
            if (!(item instanceof Map<?, ?>)) continue;
            Map<String, Object> step = (Map<String, Object>) item;
            onlyStep = onlyStep == null ? step : Map.of();
            if (Integer.parseInt(String.valueOf(step.get("stepNo"))) == stepNo) return step;
        }
        // 兼容旧前端默认请求 Step 1 的单语句脚本；多 Step 脚本仍严格按编号选择。
        if (stepNo == 1 && onlyStep != null && !onlyStep.isEmpty()
                && Integer.parseInt(String.valueOf(onlyStep.get("stepNo"))) == 0) {
            return onlyStep;
        }
        throw new IllegalArgumentException("未找到Step " + stepNo);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
