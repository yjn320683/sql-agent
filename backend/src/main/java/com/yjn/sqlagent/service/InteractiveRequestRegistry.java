package com.yjn.sqlagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjn.sqlagent.common.ErrorCode;
import com.yjn.sqlagent.exception.BusinessException;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;

@Component
public class InteractiveRequestRegistry {

    private static final long TTL_MILLIS = Duration.ofMinutes(10).toMillis();

    private final ObjectMapper objectMapper;
    private final Map<String, Entry> entries = new ConcurrentHashMap<String, Entry>();

    public InteractiveRequestRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void register(String obId, ServerSentEvent<String> event) {
        if (!"permission_request".equals(event.event()) && !"user_question_request".equals(event.event())) {
            return;
        }
        if (event.data() == null || event.data().trim().isEmpty()) {
            return;
        }
        try {
            JsonNode payload = objectMapper.readTree(event.data());
            String requestId = payload.path("requestId").asText("");
            if (!requestId.isEmpty()) {
                evictExpired();
                entries.put(requestId, new Entry(obId, System.currentTimeMillis() + TTL_MILLIS));
            }
        } catch (IOException ignored) {
            // 非法交互事件不会进入审批链路。
        }
    }

    public void requireOwner(String obId, String requestId) {
        Entry entry = entries.get(requestId);
        if (entry == null || entry.expiresAt < System.currentTimeMillis()) {
            entries.remove(requestId);
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "交互请求已过期，请重新发起操作");
        }
        if (!entry.obId.equals(obId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN.getCode(), "无权处理该交互请求");
        }
    }

    public void complete(String requestId) {
        entries.remove(requestId);
    }

    private void evictExpired() {
        long now = System.currentTimeMillis();
        entries.entrySet().removeIf(entry -> entry.getValue().expiresAt < now);
    }

    private static class Entry {
        private final String obId;
        private final long expiresAt;

        private Entry(String obId, long expiresAt) {
            this.obId = obId;
            this.expiresAt = expiresAt;
        }
    }
}
