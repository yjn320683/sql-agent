package com.yjn.sqlagent.realtime.controller;

import com.yjn.sqlagent.realtime.common.RealtimeActorProvider;
import com.yjn.sqlagent.realtime.common.RealtimeResponse;
import com.yjn.sqlagent.realtime.repository.RealtimeSyncRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RealtimeSyncDdlExceptionController {
    private final RealtimeSyncRepository repository;
    private final RealtimeActorProvider actors;
    private final String token;

    public RealtimeSyncDdlExceptionController(RealtimeSyncRepository repository, RealtimeActorProvider actors,
            @Value("${app.sync.ddl-exception.report-token:}") String token) {
        this.repository = repository; this.actors = actors; this.token = token;
    }

    @GetMapping({"/v1/api/tasks/{taskId}/sync/ddl-exceptions", "/api/tasks/{taskId}/sync/ddl-exceptions"})
    public RealtimeResponse<List<Map<String, Object>>> list(@PathVariable long taskId) {
        actors.requireActor();
        return RealtimeResponse.success(repository.ddlExceptions(taskId));
    }

    @PostMapping("/api/internal/sync/ddl-exceptions")
    public ResponseEntity<RealtimeResponse<Void>> report(
            @RequestHeader(value = "X-RTDW-Internal-Token", required = false) String provided,
            @RequestBody Map<String, Object> request) {
        if (token.isBlank() || provided == null || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(RealtimeResponse.failure(401, "内部上报 Token 无效"));
        }
        try {
            if (request == null || request.get("taskId") == null || request.get("eventId") == null
                    || request.get("databaseName") == null || request.get("tableName") == null
                    || request.get("ddlSql") == null) {
                throw new IllegalArgumentException("DDL 异常上报参数不完整");
            }
            long taskId = Long.parseLong(String.valueOf(request.get("taskId")));
            repository.reportDdlException(String.valueOf(request.get("eventId")), taskId,
                    String.valueOf(request.get("databaseName")), String.valueOf(request.get("tableName")),
                    String.valueOf(request.get("ddlSql")));
            return ResponseEntity.ok(RealtimeResponse.success(null));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(RealtimeResponse.failure(400, exception.getMessage()));
        }
    }
}
