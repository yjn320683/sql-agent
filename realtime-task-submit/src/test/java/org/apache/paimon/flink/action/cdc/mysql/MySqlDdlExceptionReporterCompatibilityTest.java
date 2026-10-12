package org.apache.paimon.flink.action.cdc.mysql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 使用运行时所依赖的定制 CDC 产物验证实际 HTTP 协议，而非仅检查命令参数。 */
class MySqlDdlExceptionReporterCompatibilityTest {
    @Test
    void postsExpectedPayloadAndTokenAndLeavesUnauthorizedResponseForRetry() throws Exception {
        AtomicReference<String> payload = new AtomicReference<>();
        AtomicReference<String> receivedToken = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/internal/sync/ddl-exceptions", exchange -> {
            receivedToken.set(exchange.getRequestHeaders().getFirst("X-RTDW-Internal-Token"));
            payload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int status = "expected-token".equals(receivedToken.get()) ? 200 : 401;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort()
                    + "/api/internal/sync/ddl-exceptions";
            MySqlDdlReportEvent event = new MySqlDdlReportEvent(
                    "00000000-0000-0000-0000-000000000001", 1101L,
                    "source_db", "orders", "ALTER TABLE orders DROP COLUMN value");
            Method send = MySqlDdlExceptionReporter.class.getDeclaredMethod("send", MySqlDdlReportEvent.class);
            send.setAccessible(true);
            assertTrue((boolean) send.invoke(new MySqlDdlExceptionReporter(
                    url, "expected-token", new Object(), Map.of()), event));
            assertEquals("expected-token", receivedToken.get());
            assertTrue(payload.get().contains("\"taskId\":1101"));
            assertTrue(payload.get().contains("\"eventId\":\"00000000-0000-0000-0000-000000000001\""));
            assertTrue(payload.get().contains("\"databaseName\":\"source_db\""));
            assertTrue(payload.get().contains("\"tableName\":\"orders\""));
            assertTrue(payload.get().contains("\"ddlSql\":\"ALTER TABLE orders DROP COLUMN value\""));
            assertFalse((boolean) send.invoke(new MySqlDdlExceptionReporter(
                    url, "wrong-token", new Object(), Map.of()), event));
        } finally {
            server.stop(0);
        }
    }
}
