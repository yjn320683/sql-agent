package com.yjn.sqlagent.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjn.sqlagent.common.ErrorCode;
import com.yjn.sqlagent.exception.BusinessException;
import com.yjn.sqlagent.model.dto.AgentChatRequestDTO;
import com.yjn.sqlagent.model.dto.PermissionDecisionDTO;
import com.yjn.sqlagent.model.dto.UserQuestionAnswerDTO;
import com.yjn.sqlagent.service.AgentProxyService;
import com.yjn.sqlagent.service.InteractiveRequestRegistry;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

@Service
public class AgentProxyServiceImpl implements AgentProxyService {

    private static final Logger log = LoggerFactory.getLogger(AgentProxyServiceImpl.class);
    private static final Duration CONTROL_REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration WORKSPACE_REQUEST_TIMEOUT = Duration.ofSeconds(120);

    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<ServerSentEvent<String>>() {
            };
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<Map<String, Object>>() {
            };

    private final WebClient agentWebClient;
    private final InteractiveRequestRegistry interactiveRequestRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentProxyServiceImpl(WebClient agentWebClient,
                                 InteractiveRequestRegistry interactiveRequestRegistry) {
        this.agentWebClient = agentWebClient;
        this.interactiveRequestRegistry = interactiveRequestRegistry;
    }

    @Override
    public Flux<ServerSentEvent<String>> streamChat(
            String sessionId,
            String obId,
            Long taskId,
            Long executionId,
            Integer versionNo,
            String command,
            String message) {
        AgentChatRequestDTO body = new AgentChatRequestDTO();
        body.setSessionId(sessionId);
        body.setObId(obId);
        body.setTaskId(taskId);
        body.setExecutionId(executionId);
        body.setVersionNo(versionNo);
        body.setCommand(command);
        body.setMessage(message);

        String errorId = UUID.randomUUID().toString();
        return agentWebClient.post()
                .uri("/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(SSE_TYPE)
                .doOnNext(event -> interactiveRequestRegistry.register(obId, event))
                .doOnError(ex -> log.error("Agent SSE 调用失败 errorId={}, sessionId={}", errorId, sessionId, ex))
                .onErrorResume(ex -> Flux.just(errorEvent("Agent 服务暂时不可用，错误编号：" + errorId)));
    }

    @Override
    public void decideToolPermission(String obId, String requestId, String decision) {
        interactiveRequestRegistry.requireOwner(obId, requestId);
        PermissionDecisionDTO body = new PermissionDecisionDTO();
        body.setDecision(decision);

        Map<String, Object> response = agentWebClient.post()
                .uri("/permissions/{requestId}", requestId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .block(CONTROL_REQUEST_TIMEOUT);
        ensureAccepted(response, "权限请求已过期或不属于当前 Agent 进程，请重新发起操作");
        interactiveRequestRegistry.complete(requestId);
    }

    @Override
    public void answerUserQuestion(String obId, String requestId, UserQuestionAnswerDTO answer) {
        interactiveRequestRegistry.requireOwner(obId, requestId);
        Map<String, Object> response = agentWebClient.post()
                .uri("/user-questions/{requestId}/answer", requestId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(answer)
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .block(CONTROL_REQUEST_TIMEOUT);
        ensureAccepted(response, "澄清问题已过期或不属于当前 Agent 进程，请重新发起问题");
        interactiveRequestRegistry.complete(requestId);
    }

    @Override
    public void cancelChat(String sessionId) {
        Map<String, String> body = new HashMap<String, String>();
        body.put("sessionId", sessionId);

        agentWebClient.post()
                .uri("/chat/cancel")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block(CONTROL_REQUEST_TIMEOUT);
    }

    @Override
    public void startTaskExecution(long executionId) {
        Map<String, Object> response = agentWebClient.post()
                .uri("/sql-executions/{executionId}/start", executionId)
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .block(CONTROL_REQUEST_TIMEOUT);
        ensureAccepted(response, "Agent 未接受执行请求");
    }

    @Override
    public void cancelTaskExecution(long executionId) {
        Map<String, Object> response = agentWebClient.post()
                .uri("/sql-executions/{executionId}/cancel", executionId)
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .block(CONTROL_REQUEST_TIMEOUT);
        ensureAccepted(response, "执行实例不存在或已经结束");
    }

    @Override
    public Map<String, Object> listHiveDatabases() {
        return workspaceRequest(agentWebClient.get().uri("/workspace/hive/databases"));
    }

    @Override
    public Map<String, Object> completeSql(com.yjn.sqlagent.model.dto.SqlCompletionRequestDTO request) {
        return workspaceRequest(agentWebClient.post()
                .uri("/workspace/sql/completions")
                .bodyValue(request));
    }

    @Override
    public Map<String, Object> previewSqlStructure(
            com.yjn.sqlagent.model.dto.SqlStructurePreviewDTO request) {
        return workspaceRequest(agentWebClient.post()
                .uri("/workspace/sql/structure")
                .bodyValue(request));
    }

    @Override
    public Map<String, Object> searchHiveFunctions(
            String keyword, int limit, int offset, String defaultDb) {
        return workspaceRequest(agentWebClient.get().uri(builder -> builder
                .path("/workspace/hive/functions")
                .queryParam("keyword", keyword == null ? "" : keyword)
                .queryParam("limit", limit)
                .queryParam("offset", offset)
                .queryParamIfPresent("defaultDb", java.util.Optional.ofNullable(defaultDb))
                .build()));
    }

    @Override
    public Map<String, Object> getHiveFunction(String name, String defaultDb) {
        return workspaceRequest(agentWebClient.get().uri(builder -> builder
                .path("/workspace/hive/functions/{name}")
                .queryParamIfPresent("defaultDb", java.util.Optional.ofNullable(defaultDb))
                .build(name)));
    }

    @Override
    public Map<String, Object> getPlatformHealth() {
        return workspaceRequest(agentWebClient.get().uri("/workspace/platform/health"));
    }

    @Override
    public Map<String, Object> getDataMapPrimaryKeys(String db, String table) {
        return workspaceRequest(agentWebClient.get().uri(builder -> builder
                .path("/workspace/data-map/tables/{db}/{table}/primary-keys")
                .build(db, table)));
    }

    @Override
    public Map<String, Object> getTaskExecutionDiagnostics(long executionId) {
        return workspaceRequest(agentWebClient.get().uri(builder -> builder
                .path("/workspace/task-executions/{executionId}/diagnostics")
                .build(executionId)));
    }

    @Override
    public Map<String, Object> getTaskLineage(long taskId, Integer versionNo, String defaultDb) {
        return workspaceRequest(agentWebClient.get().uri(builder -> builder
                .path("/workspace/tasks/{taskId}/lineage")
                .queryParamIfPresent("versionNo", java.util.Optional.ofNullable(versionNo))
                .queryParamIfPresent("defaultDb", java.util.Optional.ofNullable(defaultDb))
                .build(taskId)));
    }

    @Override
    public Map<String, Object> getTaskDependencies(long taskId, Integer versionNo, String defaultDb) {
        return workspaceRequest(agentWebClient.get().uri(builder -> builder
                .path("/workspace/tasks/{taskId}/dependencies")
                .queryParamIfPresent("versionNo", java.util.Optional.ofNullable(versionNo))
                .queryParamIfPresent("defaultDb", java.util.Optional.ofNullable(defaultDb))
                .queryParam("limit", 500)
                .build(taskId)));
    }

    @Override
    public Map<String, Object> checkTaskQuality(long taskId, Integer versionNo, String defaultDb) {
        return workspaceRequest(agentWebClient.post().uri(builder -> builder
                .path("/workspace/tasks/{taskId}/quality")
                .queryParamIfPresent("versionNo", java.util.Optional.ofNullable(versionNo))
                .queryParamIfPresent("defaultDb", java.util.Optional.ofNullable(defaultDb))
                .build(taskId)));
    }

    @Override
    public Map<String, Object> searchHiveTables(String pattern, String db, int limit, int offset) {
        return workspaceRequest(agentWebClient.get().uri(builder -> builder
                .path("/workspace/hive/tables")
                .queryParam("pattern", pattern == null ? "" : pattern)
                .queryParamIfPresent("db", java.util.Optional.ofNullable(db))
                .queryParam("limit", limit)
                .queryParam("offset", offset)
                .build()));
    }

    @Override
    public Map<String, Object> getHiveColumns(String db, String table) {
        return workspaceRequest(agentWebClient.get().uri(builder -> builder
                .path("/workspace/hive/tables/{db}/{table}/columns")
                .build(db, table)));
    }

    @Override
    public Map<String, Object> getHiveTable(String db, String table) {
        return workspaceRequest(agentWebClient.get().uri(builder -> builder
                .path("/workspace/hive/tables/{db}/{table}")
                .build(db, table)));
    }

    @Override
    public Map<String, Object> getHivePartitions(String db, String table, int limit, int offset) {
        return workspaceRequest(agentWebClient.get().uri(builder -> builder
                .path("/workspace/hive/tables/{db}/{table}/partitions")
                .queryParam("limit", limit)
                .queryParam("offset", offset)
                .build(db, table)));
    }

    @Override
    public Map<String, Object> getHiveTableDdl(String db, String table) {
        return workspaceRequest(agentWebClient.get().uri(builder -> builder
                .path("/workspace/hive/tables/{db}/{table}/ddl")
                .build(db, table)));
    }

    @Override
    public Map<String, Object> getHiveTableStatistics(
            String db, String table, java.util.List<String> columns) {
        return workspaceRequest(agentWebClient.get().uri(builder -> {
            org.springframework.web.util.UriBuilder query = builder
                    .path("/workspace/hive/tables/{db}/{table}/statistics");
            if (columns != null) {
                for (String column : columns) {
                    query.queryParam("columns", column);
                }
            }
            return query.build(db, table);
        }));
    }

    @Override
    public Map<String, Object> getHiveStorageLayout(String db, String table, int maxFiles) {
        return workspaceRequest(agentWebClient.get().uri(builder -> builder
                .path("/workspace/hive/tables/{db}/{table}/storage-layout")
                .queryParam("maxFiles", maxFiles)
                .build(db, table)));
    }

    @Override
    public Map<String, Object> getHiveTableFreshness(
            String db, String table, int partitionScanLimit, int pathSampleLimit) {
        return workspaceRequest(agentWebClient.get().uri(builder -> builder
                .path("/workspace/hive/tables/{db}/{table}/freshness")
                .queryParam("partitionScanLimit", partitionScanLimit)
                .queryParam("pathSampleLimit", pathSampleLimit)
                .build(db, table)));
    }

    @Override
    public Map<String, Object> validateTaskSql(long taskId, Integer versionNo, String defaultDb) {
        return workspaceRequest(agentWebClient.post().uri(builder -> builder
                .path("/workspace/tasks/{taskId}/validate")
                .queryParamIfPresent("versionNo", java.util.Optional.ofNullable(versionNo))
                .queryParamIfPresent("defaultDb", java.util.Optional.ofNullable(defaultDb))
                .build(taskId)));
    }

    @Override
    public Map<String, Object> explainTaskSql(long taskId, Integer versionNo, String defaultDb, boolean extended) {
        return workspaceRequest(agentWebClient.post().uri(builder -> builder
                .path("/workspace/tasks/{taskId}/explain")
                .queryParamIfPresent("versionNo", java.util.Optional.ofNullable(versionNo))
                .queryParamIfPresent("defaultDb", java.util.Optional.ofNullable(defaultDb))
                .queryParam("extended", extended)
                .build(taskId)));
    }

    private Map<String, Object> workspaceRequest(WebClient.RequestHeadersSpec<?> request) {
        try {
            return request.retrieve().bodyToMono(MAP_TYPE).block(WORKSPACE_REQUEST_TIMEOUT);
        } catch (WebClientResponseException ex) {
            String message = extractAgentError(ex.getResponseBodyAsString(StandardCharsets.UTF_8));
            throw new BusinessException(
                    ex.getRawStatusCode() >= 500 ? 503 : ErrorCode.BAD_REQUEST.getCode(),
                    message);
        } catch (RuntimeException ex) {
            log.error("SQL 工作台调用 Agent 失败 errorType={}", ex.getClass().getSimpleName());
            throw new BusinessException(503, "Hive 工作台服务暂时不可用");
        }
    }

    private String extractAgentError(String responseBody) {
        try {
            String detail = objectMapper.readTree(responseBody).path("detail").asText();
            return detail == null || detail.trim().isEmpty() ? "Hive 工作台请求失败" : detail;
        } catch (Exception ignored) {
            return "Hive 工作台请求失败";
        }
    }

    private ServerSentEvent<String> errorEvent(String message) {
        Map<String, String> payload = new HashMap<String, String>();
        payload.put("message", message == null ? "agent 连接失败" : message);
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            json = "{\"message\":\"agent 连接失败\"}";
        }
        return ServerSentEvent.<String>builder()
                .event("error")
                .data(json)
                .build();
    }

    private void ensureAccepted(Map<String, Object> response, String message) {
        if (response == null || !Boolean.TRUE.equals(response.get("accepted"))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), message);
        }
    }
}
