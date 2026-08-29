package com.yjn.sqlagent.service;

import com.yjn.sqlagent.model.dto.UserQuestionAnswerDTO;
import com.yjn.sqlagent.model.dto.SqlCompletionRequestDTO;
import com.yjn.sqlagent.model.dto.SqlStructurePreviewDTO;
import java.util.Map;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

public interface AgentProxyService {

    /** 转发一次聊天请求到 agent，透传其 SSE 事件流。 */
    Flux<ServerSentEvent<String>> streamChat(
            String sessionId,
            String obId,
            Long taskId,
            Long executionId,
            Integer versionNo,
            String command,
            String message);

    /** 转发工具权限决策到 agent。 */
    void decideToolPermission(String obId, String requestId, String decision);

    /** 转发业务澄清问题答案到 agent。 */
    void answerUserQuestion(String obId, String requestId, UserQuestionAnswerDTO answer);

    /** 转发取消请求到 agent。 */
    void cancelChat(String sessionId);

    /** 通知 agent 异步启动一个已持久化的任务实例。 */
    void startTaskExecution(long executionId);

    /** 请求 agent 取消任务实例。 */
    void cancelTaskExecution(long executionId);

    Map<String, Object> listHiveDatabases();

    Map<String, Object> completeSql(SqlCompletionRequestDTO request);

    Map<String, Object> previewSqlStructure(SqlStructurePreviewDTO request);

    Map<String, Object> searchHiveFunctions(String keyword, int limit, int offset, String defaultDb);

    Map<String, Object> getHiveFunction(String name, String defaultDb);

    Map<String, Object> getPlatformHealth();

    Map<String, Object> getDataMapPrimaryKeys(String db, String table);

    Map<String, Object> getTaskExecutionDiagnostics(long executionId);

    Map<String, Object> getTaskLineage(long taskId, Integer versionNo, String defaultDb);

    Map<String, Object> getTaskDependencies(long taskId, Integer versionNo, String defaultDb);

    Map<String, Object> checkTaskQuality(long taskId, Integer versionNo, String defaultDb);

    Map<String, Object> searchHiveTables(String pattern, String db, int limit, int offset);

    Map<String, Object> getHiveColumns(String db, String table);

    Map<String, Object> getHiveTable(String db, String table);

    Map<String, Object> getHivePartitions(String db, String table, int limit, int offset);

    Map<String, Object> getHiveTableDdl(String db, String table);

    Map<String, Object> getHiveTableStatistics(String db, String table, java.util.List<String> columns);

    Map<String, Object> getHiveStorageLayout(String db, String table, int maxFiles);

    Map<String, Object> getHiveTableFreshness(
            String db, String table, int partitionScanLimit, int pathSampleLimit);

    Map<String, Object> validateTaskSql(long taskId, Integer versionNo, String defaultDb);

    Map<String, Object> explainTaskSql(long taskId, Integer versionNo, String defaultDb, boolean extended);
}
