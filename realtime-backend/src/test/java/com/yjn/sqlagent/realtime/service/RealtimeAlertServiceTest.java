package com.yjn.sqlagent.realtime.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjn.sqlagent.realtime.config.RealtimeProperties;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class RealtimeAlertServiceTest {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private JdbcTemplate jdbc;
    private RealtimeAlertService service;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:alertService;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"));
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE rt_task(id BIGINT PRIMARY KEY,task_name VARCHAR(180),task_type VARCHAR(16))");
        jdbc.execute("CREATE TABLE rt_alert_rule(id BIGINT AUTO_INCREMENT PRIMARY KEY,rule_code VARCHAR(64),rule_name VARCHAR(128),"
                + "event_type VARCHAR(64),severity VARCHAR(32),enabled_flag TINYINT,threshold_value BIGINT,consecutive_samples INT,"
                + "window_seconds INT,description VARCHAR(512),update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE rt_alert(id BIGINT AUTO_INCREMENT PRIMARY KEY,task_id BIGINT,task_instance_id BIGINT,rule_id BIGINT,"
                + "event_type VARCHAR(64),severity VARCHAR(32),status VARCHAR(32),title VARCHAR(255),detail TEXT,fingerprint CHAR(64),"
                + "active_fingerprint CHAR(64),occurrence_count INT,first_occurred_at TIMESTAMP,last_occurred_at TIMESTAMP,"
                + "acknowledged_by VARCHAR(64),acknowledged_at TIMESTAMP,muted_until TIMESTAMP,recovered_at TIMESTAMP,evidence_json CLOB,"
                + "create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE rt_task_instance(id BIGINT PRIMARY KEY,task_id BIGINT,managed_flag TINYINT,execution_mode VARCHAR(32),"
                + "status VARCHAR(32),failure_message VARCHAR(512))");
        jdbc.execute("CREATE TABLE rt_alert_rule_state(rule_id BIGINT,task_id BIGINT,task_instance_id BIGINT,consecutive_count INT,"
                + "last_condition_met TINYINT,last_value VARCHAR(128),evidence_json CLOB,last_evaluated_at TIMESTAMP,"
                + "create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                + "PRIMARY KEY(rule_id,task_id,task_instance_id))");
        jdbc.execute("CREATE TABLE rt_sync_dirty_record(task_id BIGINT,resolved_flag TINYINT)");
        jdbc.execute("CREATE TABLE rt_schema_change_event(task_id BIGINT,status VARCHAR(32))");
        jdbc.update("INSERT INTO rt_task VALUES(1,'同步测试','sync')");
        jdbc.update("INSERT INTO rt_alert_rule(rule_code,rule_name,event_type,severity,enabled_flag,threshold_value,consecutive_samples,window_seconds,description) VALUES('SOURCE_LAG','源端延迟','SOURCE_LAG','warning',1,300000,3,300,'延迟')");
        jdbc.update("INSERT INTO rt_alert(task_id,rule_id,event_type,severity,status,title,detail,fingerprint,active_fingerprint,occurrence_count,first_occurred_at,last_occurred_at,evidence_json) VALUES(1,1,'SOURCE_LAG','warning','OPEN','源端延迟','超过阈值','fp','fp',2,NOW(),NOW(),'{}')");
        service = new RealtimeAlertService(jdbc, new ObjectMapper(), mock(RealtimeRuntimeService.class),
                new RealtimeProperties(), executor);
    }

    @AfterEach
    void tearDown() { executor.shutdownNow(); }

    @Test
    void pagesAndTransitionsAlertLifecycle() {
        Map<String, Object> page = service.page(Map.of("view", "ACTIVE", "page", "1", "pageSize", "20"));
        assertEquals(1L, page.get("total"));
        service.acknowledge(1L, "tester");
        assertEquals("ACKNOWLEDGED", jdbc.queryForObject("SELECT status FROM rt_alert WHERE id=1", String.class));
        service.mute(1L, LocalDateTime.now().plusHours(1), "tester");
        assertEquals("MUTED", jdbc.queryForObject("SELECT status FROM rt_alert WHERE id=1", String.class));
        service.unmute(1L);
        assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM rt_alert WHERE id=1", String.class));
    }

    @Test
    void validatesRuleAndMuteUpdates() {
        assertThrows(IllegalArgumentException.class, () -> service.mute(1L, LocalDateTime.now().minusMinutes(1), "tester"));
        Map<String, Object> rule = service.updateRule(1L, Map.of(
                "enabled", false, "severity", "critical", "thresholdValue", 600000,
                "consecutiveSamples", 4, "windowSeconds", 600));
        assertEquals("critical", rule.get("severity"));
        assertEquals(0, ((Number) rule.get("enabled")).intValue());
        assertEquals("RECOVERED", jdbc.queryForObject("SELECT status FROM rt_alert WHERE id=1", String.class));
    }

    @Test
    void recoversInstanceAlertAfterInstanceLeavesActiveSet() {
        jdbc.update("INSERT INTO rt_task_instance VALUES(99,1,1,'PRODUCTION','stopped',NULL)");
        jdbc.update("INSERT INTO rt_alert(task_id,task_instance_id,rule_id,event_type,severity,status,title,detail,fingerprint,"
                + "active_fingerprint,occurrence_count,first_occurred_at,last_occurred_at,evidence_json) "
                + "VALUES(1,99,1,'SOURCE_LAG','warning','OPEN','源端延迟','超过阈值','instance-fp','instance-fp',1,NOW(),NOW(),'{}')");
        jdbc.update("INSERT INTO rt_alert_rule_state(rule_id,task_id,task_instance_id,consecutive_count,last_condition_met,last_evaluated_at) "
                + "VALUES(1,1,99,3,1,NOW())");

        service.evaluateRules();

        assertEquals("RECOVERED", jdbc.queryForObject("SELECT status FROM rt_alert WHERE active_fingerprint IS NULL AND task_instance_id=99", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT consecutive_count FROM rt_alert_rule_state WHERE task_instance_id=99", Integer.class));
    }
}
