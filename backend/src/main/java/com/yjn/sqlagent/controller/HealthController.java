package com.yjn.sqlagent.controller;

import com.yjn.sqlagent.service.SchemaMigrationStatusService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final JdbcTemplate jdbcTemplate;
    private final SchemaMigrationStatusService schemaMigrationStatusService;

    public HealthController(JdbcTemplate jdbcTemplate,
                            SchemaMigrationStatusService schemaMigrationStatusService) {
        this.jdbcTemplate = jdbcTemplate;
        this.schemaMigrationStatusService = schemaMigrationStatusService;
    }

    @GetMapping(value = "/api/health", produces = MediaType.TEXT_PLAIN_VALUE)
    public String health() {
        return "ok";
    }

    @GetMapping(value = "/api/ready", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> ready() {
        jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        if (!schemaMigrationStatusService.compatible()) {
            return ResponseEntity.status(503).body("database schema migration required");
        }
        return ResponseEntity.ok("ok");
    }
}
