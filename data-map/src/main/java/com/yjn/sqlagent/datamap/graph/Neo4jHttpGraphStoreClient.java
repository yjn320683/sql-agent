package com.yjn.sqlagent.datamap.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjn.sqlagent.datamap.config.DataMapProperties;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/** 通过 Neo4j 5.26 Query API 执行参数化 Cypher，不要求主工程升级到 Java 17。 */
@Component
public class Neo4jHttpGraphStoreClient implements GraphStoreClient {
    private final RestTemplate http;
    private final ObjectMapper mapper;
    private final DataMapProperties properties;

    public Neo4jHttpGraphStoreClient(@Qualifier("dataMapRestTemplate") RestTemplate http,
                                     ObjectMapper mapper, DataMapProperties properties) {
        this.http = http; this.mapper = mapper; this.properties = properties;
    }

    @Override public boolean isConfigured() { return properties.getNeo4j().isEnabled(); }

    @Override public boolean ping() {
        if (!isConfigured()) return false;
        try { query("RETURN 1 AS ok", Collections.emptyMap()); return true; }
        catch (RuntimeException error) { return false; }
    }

    @Override
    public List<Map<String, Object>> query(String cypher, Map<String, Object> parameters) {
        if (!isConfigured()) throw new GraphStoreUnavailableException("Neo4j 未配置或未启用");
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("statement", cypher);
            body.put("parameters", parameters == null ? Collections.emptyMap() : parameters);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            String credentials = properties.getNeo4j().getUsername() + ":" + properties.getNeo4j().getPassword();
            headers.set(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder()
                    .encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
            String url = trimSlash(properties.getNeo4j().getBaseUrl()) + "/db/"
                    + properties.getNeo4j().getDatabase() + "/query/v2";
            JsonNode root = http.postForObject(url, new HttpEntity<>(body, headers), JsonNode.class);
            return rows(root);
        } catch (GraphStoreUnavailableException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new GraphStoreUnavailableException("Neo4j Query API 调用失败：" + safe(error), error);
        }
    }

    private List<Map<String, Object>> rows(JsonNode root) {
        if (root == null) return Collections.emptyList();
        JsonNode data = root.path("data");
        JsonNode fields = data.path("fields");
        JsonNode values = data.path("values");
        if (!fields.isArray() || !values.isArray()) return Collections.emptyList();
        List<String> names = new ArrayList<>();
        fields.forEach(item -> names.add(item.asText()));
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode row : values) {
            Map<String, Object> value = new LinkedHashMap<>();
            for (int index = 0; index < names.size(); index++) {
                value.put(names.get(index), index < row.size() ? mapper.convertValue(row.get(index), Object.class) : null);
            }
            result.add(value);
        }
        return result;
    }

    private String trimSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private String safe(Throwable error) {
        String value = error.getMessage();
        return value == null || value.trim().isEmpty() ? error.getClass().getSimpleName() : value;
    }
}
