package com.yjn.sqlagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjn.sqlagent.mapper.TaskLineageSnapshotMapper;
import com.yjn.sqlagent.model.entity.TaskLineageSnapshot;
import com.yjn.sqlagent.realtime.repository.LineageRelationRepository;
import com.yjn.sqlagent.datamap.store.LineageOutboxService;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

/** 写入和读取按 SQL 校验和区分的不可变血缘快照。 */
@Service
public class TaskLineageSnapshotService {
    public static final String PARSER_VERSION = "parse-sql-v2";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<Map<String, Object>>() { };
    private final TaskLineageSnapshotMapper mapper;
    private final ObjectMapper objectMapper;
    private LineageRelationRepository relationRepository;
    private LineageOutboxService outboxService;

    public TaskLineageSnapshotService(TaskLineageSnapshotMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Autowired(required = false)
    void setRelationRepository(LineageRelationRepository relationRepository) {
        this.relationRepository = relationRepository;
    }

    @Autowired(required = false)
    void setOutboxService(LineageOutboxService outboxService) { this.outboxService = outboxService; }

    public TaskLineageFacts findOffline(long taskId, int versionNo, String checksum, String defaultDatabase) {
        TaskLineageSnapshot snapshot = mapper.selectExact("OFFLINE", taskId, versionNo, checksum,
                normalizeDatabase(defaultDatabase));
        if (snapshot == null) return null;
        try {
            return TaskLineageFacts.fromJson(objectMapper.readValue(snapshot.getLineageJson(), MAP));
        } catch (Exception exception) {
            throw new IllegalStateException("血缘快照 JSON 已损坏，snapshotId=" + snapshot.getId(), exception);
        }
    }

    public Map<String, Object> metadata(long taskId, int versionNo, String checksum, String defaultDatabase) {
        TaskLineageSnapshot snapshot = mapper.selectExact("OFFLINE", taskId, versionNo, checksum,
                normalizeDatabase(defaultDatabase));
        if (snapshot == null) return Collections.emptyMap();
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("snapshotId", snapshot.getId());
        result.put("parserVersion", snapshot.getParserVersion());
        result.put("snapshotSource", snapshot.getSnapshotSource());
        result.put("complete", snapshot.getCompleteFlag());
        result.put("createdAt", snapshot.getCreateTime());
        result.put("sqlChecksum", snapshot.getSqlChecksum());
        return result;
    }

    public void saveOffline(long taskId, Long versionId, int versionNo, String checksum,
                            String defaultDatabase, String source, TaskLineageFacts facts) {
        String database = normalizeDatabase(defaultDatabase);
        if (mapper.selectExact("OFFLINE", taskId, versionNo, checksum, database) != null) return;
        TaskLineageSnapshot snapshot = new TaskLineageSnapshot();
        snapshot.setTaskScope("OFFLINE");
        snapshot.setTaskId(taskId);
        snapshot.setVersionId(versionId);
        snapshot.setVersionNo(versionNo);
        snapshot.setSqlChecksum(checksum);
        snapshot.setDialect("HIVE");
        snapshot.setDefaultDatabase(database);
        snapshot.setParserVersion(PARSER_VERSION);
        snapshot.setSnapshotSource(source);
        snapshot.setCompleteFlag(facts.isComplete());
        try {
            snapshot.setLineageJson(objectMapper.writeValueAsString(facts.toJson()));
            snapshot.setDiagnosticsJson(objectMapper.writeValueAsString(facts.getDiagnostics()));
        } catch (Exception exception) {
            throw new IllegalStateException("无法序列化血缘快照", exception);
        }
        snapshot.setCreateTime(LocalDateTime.now());
        try {
            mapper.insert(snapshot);
            if (relationRepository != null && snapshot.getId() != null) {
                relationRepository.index(snapshot.getId(), "OFFLINE", taskId, versionId, versionNo, facts.toJson());
            }
            if (outboxService != null && snapshot.getId() != null) {
                outboxService.enqueue(snapshot.getId(), "OFFLINE", taskId, versionId, versionNo);
            }
        } catch (DuplicateKeyException ignored) {
            // 并发首次查看或保存只保留一份相同事实。
        }
    }

    private String normalizeDatabase(String value) {
        return value == null || value.trim().isEmpty() ? "default" : value.trim();
    }
}
