package com.yjn.sqlagent.realtime.controller;

import com.yjn.sqlagent.realtime.common.RealtimeResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.yjn.sqlagent.realtime")
public class RealtimeExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<RealtimeResponse<Void>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(RealtimeResponse.failure(400, ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<RealtimeResponse<Void>> conflict(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(RealtimeResponse.failure(409, ex.getMessage()));
    }
}
