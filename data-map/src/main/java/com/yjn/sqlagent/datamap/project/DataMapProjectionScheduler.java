package com.yjn.sqlagent.datamap.project;

import com.yjn.sqlagent.datamap.config.DataMapProperties;
import com.yjn.sqlagent.datamap.graph.GraphStoreClient;
import com.yjn.sqlagent.datamap.store.LineageOutboxService;
import java.lang.management.ManagementFactory;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 每分钟补齐并投影当前版本；每天构建新代次并在完整后原子切换。 */
@Service
public class DataMapProjectionScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(DataMapProjectionScheduler.class);
    private final LineageOutboxService outbox;
    private final DataMapGraphProjector projector;
    private final GraphStoreClient graph;
    private final DataMapProperties properties;
    private final JdbcTemplate jdbc;
    private final AtomicBoolean running = new AtomicBoolean();
    private final String worker = ManagementFactory.getRuntimeMXBean().getName();

    public DataMapProjectionScheduler(LineageOutboxService outbox, DataMapGraphProjector projector,
                                      GraphStoreClient graph, DataMapProperties properties, JdbcTemplate jdbc) {
        this.outbox=outbox;this.projector=projector;this.graph=graph;this.properties=properties;this.jdbc=jdbc;
    }

    @PostConstruct
    void initialize() {
        if (!properties.isEnabled() || !graph.isConfigured()) return;
        try { projector.initializeSchema(); }
        catch (RuntimeException error) { LOG.warn("数据地图 Neo4j Schema 初始化暂不可用：{}", safe(error)); }
    }

    @Scheduled(initialDelayString="${app.data-map.initial-delay-ms:15000}",
            fixedDelayString="${app.data-map.incremental-delay-ms:60000}")
    public void incremental() {
        if (!properties.isEnabled() || !graph.isConfigured() || !running.compareAndSet(false, true)) return;
        long runId = begin("INCREMENTAL", outbox.activeGeneration());
        try {
            outbox.enqueueMissing(1000);
            DrainStats stats = drain(null);
            activateCompletedGenerations();
            finish(runId, "SUCCEEDED", stats, null);
        } catch (RuntimeException error) {
            finish(runId, "FAILED", new DrainStats(), safe(error));
            LOG.warn("数据地图增量投影失败：{}", safe(error));
        } finally { running.set(false); }
    }

    @Scheduled(cron="${app.data-map.full-cron:0 30 2 * * *}", zone="Asia/Shanghai")
    public void full() {
        if (!properties.isEnabled() || !graph.isConfigured() || !running.compareAndSet(false, true)) return;
        long generation = outbox.beginFullGeneration();
        long runId = begin("FULL", generation);
        try {
            DrainStats stats = drain(generation);
            boolean active = outbox.activateIfComplete(generation);
            if (active) {
                try { projector.retireOlderGenerations(generation); }
                catch (RuntimeException cleanupError) { LOG.warn("数据地图旧代次清理失败，不影响新代次查询：{}", safe(cleanupError)); }
            }
            finish(runId, active ? "SUCCEEDED" : "PARTIAL", stats,
                    active ? null : "存在未成功投影的当前任务，未切换生效代次");
        } catch (RuntimeException error) {
            finish(runId, "FAILED", new DrainStats(), safe(error));
            LOG.warn("数据地图全量投影失败：{}", safe(error));
        } finally { running.set(false); }
    }

    public int projectPendingNow() {
        DrainStats stats = drain(null);
        activateCompletedGenerations();
        return stats.projected;
    }

    private void activateCompletedGenerations() {
        for (Long generation : outbox.buildingGenerations()) {
            if (!outbox.activateIfComplete(generation)) continue;
            try { projector.retireOlderGenerations(generation); }
            catch (RuntimeException cleanupError) {
                LOG.warn("数据地图旧代次清理失败，不影响新代次查询：{}", safe(cleanupError));
            }
        }
    }

    private DrainStats drain(Long wantedGeneration) {
        DrainStats stats = new DrainStats();
        while (true) {
            List<Map<String, Object>> rows = outbox.pending(wantedGeneration, properties.getProjectionBatchSize());
            if (rows.isEmpty()) return stats;
            int claimed = 0;
            for (Map<String, Object> row : rows) {
                long id = number(row.get("id"));
                if (!outbox.claim(id, worker)) continue;
                claimed++;
                stats.scanned++;
                String scope = text(row.get("task_scope")); long taskId = number(row.get("task_id"));
                int attempts = integer(row.get("attempts")) + 1;
                try {
                    long revision = projector.project(row, number(row.get("generation_no")));
                    outbox.succeeded(id, scope, taskId, revision); stats.projected++;
                    if (truth(row.get("complete_flag"))) stats.success++;
                    else stats.partial++;
                } catch (RuntimeException error) {
                    stats.failed++;
                    outbox.failed(id, scope, taskId, safe(error), attempts, properties.getMaxAttempts());
                }
            }
            if (claimed == 0 || rows.size() < properties.getProjectionBatchSize()) return stats;
        }
    }

    private long begin(String type, long generation) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO data_map_lineage_run(run_type,status,generation_no) VALUES(?,'RUNNING',?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, type);
            statement.setLong(2, generation);
            return statement;
        }, keys);
        Number id = keys.getKey();
        if (id == null) throw new IllegalStateException("数据地图运行记录未返回主键");
        return id.longValue();
    }
    private void finish(long id, String status, DrainStats stats, String error) {
        jdbc.update("UPDATE data_map_lineage_run SET status=?,scanned_count=?,success_count=?,partial_count=?,failed_count=?,projected_count=?,error_message=?,finished_at=NOW() WHERE id=?",
                status, stats.scanned, stats.success, stats.partial, stats.failed, stats.projected, error, id);
    }
    private long number(Object value) { return value instanceof Number ? ((Number)value).longValue() : Long.parseLong(text(value)); }
    private int integer(Object value) { return value instanceof Number ? ((Number)value).intValue() : Integer.parseInt(text(value)); }
    private boolean truth(Object value) {
        return Boolean.TRUE.equals(value) || (value instanceof Number && ((Number) value).intValue() == 1)
                || "1".equals(text(value));
    }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private String safe(Throwable error) { String value=error.getMessage();return value==null?error.getClass().getSimpleName():value; }
    private static final class DrainStats { private int scanned; private int success; private int partial; private int failed; private int projected; }
}
