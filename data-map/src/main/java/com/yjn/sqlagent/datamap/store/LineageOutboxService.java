package com.yjn.sqlagent.datamap.store;

import com.yjn.sqlagent.datamap.config.DataMapProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 任务版本事务可直接调用的 Outbox 写入接口。 */
@Service
public class LineageOutboxService {
    private final JdbcTemplate jdbc;
    private final DataMapProperties properties;
    public LineageOutboxService(JdbcTemplate jdbc, DataMapProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    public void enqueue(long snapshotId, String scope, long taskId, Long versionId, int versionNo) {
        long generation = activeGeneration();
        jdbc.update("UPDATE data_map_graph_outbox SET status='SUPERSEDED' WHERE task_scope=? AND task_id=? "
                + "AND generation_no=? AND status IN('PENDING','FAILED')", scope, taskId, generation);
        jdbc.update("INSERT IGNORE INTO data_map_graph_outbox(snapshot_id,task_scope,task_id,version_id,version_no,generation_no,event_type,status) "
                        + "VALUES(?,?,?,?,?,?,'UPSERT_CURRENT','PENDING')",
                snapshotId, scope, taskId, versionId, versionNo, generation);
        jdbc.update("INSERT INTO data_map_lineage_task_state(task_scope,task_id,version_id,version_no,snapshot_id,sql_checksum,"
                        + "parse_status,projection_status,generation_no,last_parsed_at) SELECT task_scope,task_id,version_id,version_no,id,sql_checksum,"
                        + "CASE WHEN complete_flag=1 THEN 'COMPLETE' ELSE 'PARTIAL' END,'PENDING',?,NOW() FROM task_lineage_snapshot WHERE id=? "
                        + "ON DUPLICATE KEY UPDATE version_id=VALUES(version_id),version_no=VALUES(version_no),snapshot_id=VALUES(snapshot_id),"
                        + "sql_checksum=VALUES(sql_checksum),parse_status=VALUES(parse_status),projection_status='PENDING',generation_no=VALUES(generation_no),last_parsed_at=NOW(),last_error=NULL",
                generation, snapshotId);
    }

    /** 兼容升级前快照；只补投影事件，不重新解析 SQL。 */
    public int enqueueMissing(int limit) {
        long generation = activeGeneration();
        int inserted = jdbc.update("INSERT IGNORE INTO data_map_graph_outbox(snapshot_id,task_scope,task_id,version_id,version_no,generation_no,event_type,status) "
                        + "SELECT s.id,s.task_scope,s.task_id,s.version_id,s.version_no," + generation + ",'UPSERT_CURRENT','PENDING' "
                        + "FROM task_lineage_snapshot s LEFT JOIN sql_task ot ON s.task_scope='OFFLINE' AND ot.id=s.task_id "
                        + "LEFT JOIN rt_task rt ON s.task_scope='REALTIME' AND rt.id=s.task_id "
                        + "LEFT JOIN data_map_graph_outbox go ON go.snapshot_id=s.id AND go.generation_no=" + generation + " "
                        + "WHERE go.id IS NULL AND ((s.task_scope='OFFLINE' AND ot.archived=0 AND ot.effective_version_no=s.version_no) "
                        + "OR (s.task_scope='REALTIME' AND rt.status<>'deleted' AND s.version_no=(SELECT MAX(v.version_no) FROM rt_task_version v WHERE v.task_id=rt.id))) "
                        + "ORDER BY s.id LIMIT " + Math.max(1, Math.min(1000, limit)));
        jdbc.update("INSERT INTO data_map_lineage_task_state(task_scope,task_id,version_id,version_no,generation_no,snapshot_id,sql_checksum,parse_status,projection_status,last_parsed_at) "
                + "SELECT s.task_scope,s.task_id,s.version_id,s.version_no,?,s.id,s.sql_checksum,CASE WHEN s.complete_flag=1 THEN 'COMPLETE' ELSE 'PARTIAL' END,'PENDING',s.create_time "
                + "FROM task_lineage_snapshot s JOIN data_map_graph_outbox o ON o.snapshot_id=s.id AND o.generation_no=? "
                + "ON DUPLICATE KEY UPDATE version_id=VALUES(version_id),version_no=VALUES(version_no),generation_no=VALUES(generation_no),snapshot_id=VALUES(snapshot_id),sql_checksum=VALUES(sql_checksum),parse_status=VALUES(parse_status)",
                generation, generation);
        return inserted;
    }

    public long activeGeneration() {
        try {
            Long value = jdbc.queryForObject("SELECT generation_no FROM data_map_projection_generation "
                    + "WHERE status='ACTIVE' ORDER BY generation_no DESC LIMIT 1", Long.class);
            if (value != null) return value;
        } catch (RuntimeException ignored) { }
        jdbc.update("INSERT IGNORE INTO data_map_projection_generation(generation_no,status,activated_at) VALUES(1,'ACTIVE',NOW())");
        return 1L;
    }

    public long beginFullGeneration() {
        List<Long> building = jdbc.queryForList("SELECT generation_no FROM data_map_projection_generation "
                + "WHERE status='BUILDING' ORDER BY generation_no DESC LIMIT 1", Long.class);
        if (!building.isEmpty()) return building.get(0);
        long generation = 0L;
        int expected = currentSnapshotCount();
        for (int attempt = 0; attempt < 3; attempt++) {
            Long next = jdbc.queryForObject("SELECT COALESCE(MAX(generation_no),0)+1 FROM data_map_projection_generation", Long.class);
            generation = next == null ? 1L : next;
            int inserted = jdbc.update("INSERT IGNORE INTO data_map_projection_generation(generation_no,status,expected_count) VALUES(?,'BUILDING',?)",
                    generation, expected);
            if (inserted == 1) break;
            building = buildingGenerations();
            if (!building.isEmpty()) return building.get(building.size() - 1);
            generation = 0L;
        }
        if (generation == 0L) throw new IllegalStateException("无法创建数据地图全量投影代次");
        jdbc.update("INSERT IGNORE INTO data_map_graph_outbox(snapshot_id,task_scope,task_id,version_id,version_no,generation_no,event_type,status) "
                        + "SELECT s.id,s.task_scope,s.task_id,s.version_id,s.version_no,?,'FULL_REBUILD','PENDING' "
                        + "FROM task_lineage_snapshot s LEFT JOIN sql_task ot ON s.task_scope='OFFLINE' AND ot.id=s.task_id "
                        + "LEFT JOIN rt_task rt ON s.task_scope='REALTIME' AND rt.id=s.task_id "
                        + "WHERE (s.task_scope='OFFLINE' AND ot.archived=0 AND ot.effective_version_no=s.version_no) "
                        + "OR (s.task_scope='REALTIME' AND rt.status<>'deleted' AND s.version_no=(SELECT MAX(v.version_no) FROM rt_task_version v WHERE v.task_id=rt.id))",
                generation);
        return generation;
    }

    public List<Long> buildingGenerations() {
        return jdbc.queryForList("SELECT generation_no FROM data_map_projection_generation "
                + "WHERE status='BUILDING' ORDER BY generation_no", Long.class);
    }

    public boolean activateIfComplete(long generation) {
        Integer pending = jdbc.queryForObject("SELECT COUNT(*) FROM data_map_graph_outbox WHERE generation_no=? AND status<>'SUCCEEDED'", Integer.class, generation);
        if (pending != null && pending > 0) return false;
        jdbc.update("UPDATE data_map_projection_generation SET status='RETIRED' WHERE status='ACTIVE'");
        jdbc.update("UPDATE data_map_projection_generation SET status='ACTIVE',activated_at=NOW() WHERE generation_no=?", generation);
        return true;
    }

    private int currentSnapshotCount() {
        Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM task_lineage_snapshot s LEFT JOIN sql_task ot ON s.task_scope='OFFLINE' AND ot.id=s.task_id "
                + "LEFT JOIN rt_task rt ON s.task_scope='REALTIME' AND rt.id=s.task_id WHERE "
                + "(s.task_scope='OFFLINE' AND ot.archived=0 AND ot.effective_version_no=s.version_no) OR "
                + "(s.task_scope='REALTIME' AND rt.status<>'deleted' AND s.version_no=(SELECT MAX(v.version_no) FROM rt_task_version v WHERE v.task_id=rt.id))", Integer.class);
        return value == null ? 0 : value;
    }

    public List<Map<String, Object>> pending(int limit) { return pending(null, limit); }

    public List<Map<String, Object>> pending(Long generation, int limit) {
        String generationClause = generation == null ? "" : " AND o.generation_no=" + generation;
        return jdbc.queryForList("SELECT o.*,s.lineage_json,s.complete_flag,s.parser_version,s.sql_checksum,s.dialect,"
                        + "CASE WHEN o.task_scope='OFFLINE' THEN ot.name ELSE rt.task_name END task_name,"
                        + "CASE WHEN o.task_scope='OFFLINE' THEN 'offline' ELSE rt.task_type END task_type "
                        + "FROM data_map_graph_outbox o JOIN task_lineage_snapshot s ON s.id=o.snapshot_id "
                        + "LEFT JOIN sql_task ot ON o.task_scope='OFFLINE' AND ot.id=o.task_id "
                        + "LEFT JOIN rt_task rt ON o.task_scope='REALTIME' AND rt.id=o.task_id "
                        + "WHERE o.status='PENDING' AND o.available_at<=NOW()" + generationClause + " ORDER BY o.id LIMIT "
                        + Math.max(1, Math.min(500, limit)));
    }

    public boolean claim(long id, String worker) {
        return jdbc.update("UPDATE data_map_graph_outbox SET status='PROCESSING',locked_by=?,locked_at=NOW(),attempts=attempts+1 "
                + "WHERE id=? AND status='PENDING' AND available_at<=NOW()", worker, id) == 1;
    }

    /** 回收因进程退出而遗留的 PROCESSING 事件；租约内事件不会被重复领取。 */
    public Map<String, Integer> recoverStaleProcessing() {
        int leaseSeconds = Math.max(30, properties.getProcessingLeaseSeconds());
        int maxAttempts = Math.max(1, properties.getMaxAttempts());
        int failed = jdbc.update("UPDATE data_map_graph_outbox SET status='FAILED',locked_by=NULL,locked_at=NULL,"
                        + "last_error=CONCAT('投影处理超过租约且已达到最大重试次数（',attempts,'/',?,'）') "
                        + "WHERE status='PROCESSING' AND locked_at<TIMESTAMPADD(SECOND,-?,NOW()) AND attempts>=?",
                maxAttempts, leaseSeconds, maxAttempts);
        int requeued = jdbc.update("UPDATE data_map_graph_outbox SET status='PENDING',available_at=NOW(),locked_by=NULL,locked_at=NULL,"
                        + "last_error=CONCAT('投影处理超过租约，已自动回收（第 ',attempts,' 次）') "
                        + "WHERE status='PROCESSING' AND locked_at<TIMESTAMPADD(SECOND,-?,NOW()) AND attempts<?",
                leaseSeconds, maxAttempts);
        if (failed > 0) {
            jdbc.update("UPDATE data_map_lineage_task_state s JOIN data_map_graph_outbox o "
                    + "ON s.task_scope=o.task_scope AND s.task_id=o.task_id "
                    + "AND s.snapshot_id=o.snapshot_id AND s.generation_no=o.generation_no "
                    + "SET s.projection_status='FAILED',s.last_error=o.last_error WHERE o.status='FAILED' "
                    + "AND o.last_error LIKE '投影处理超过租约%'");
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("requeued", requeued);
        result.put("failed", failed);
        return result;
    }

    public Map<String, Long> statusCounts() {
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("pending", statusCount("PENDING"));
        result.put("processing", statusCount("PROCESSING"));
        result.put("stale", staleProcessingCount());
        result.put("failed", statusCount("FAILED"));
        result.put("succeeded", statusCount("SUCCEEDED"));
        return result;
    }

    private long statusCount(String status) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM data_map_graph_outbox WHERE status=?", Long.class, status);
        return value == null ? 0L : value;
    }

    private long staleProcessingCount() {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM data_map_graph_outbox WHERE status='PROCESSING' "
                        + "AND locked_at<TIMESTAMPADD(SECOND,-?,NOW())", Long.class,
                Math.max(30, properties.getProcessingLeaseSeconds()));
        return value == null ? 0L : value;
    }

    public void succeeded(long id, String scope, long taskId, long revision) {
        int changed = jdbc.update("UPDATE data_map_graph_outbox SET status='SUCCEEDED',projected_at=NOW(),last_error=NULL,locked_by=NULL,locked_at=NULL "
                + "WHERE id=? AND status='PROCESSING'", id);
        if (changed != 1) return;
        jdbc.update("UPDATE data_map_projection_generation g JOIN data_map_graph_outbox o ON o.generation_no=g.generation_no "
                + "SET g.projected_count=g.projected_count+1 WHERE o.id=?", id);
        jdbc.update("UPDATE data_map_lineage_task_state s JOIN data_map_graph_outbox o "
                + "ON o.id=? AND s.task_scope=o.task_scope AND s.task_id=o.task_id "
                + "AND s.snapshot_id=o.snapshot_id AND s.generation_no=o.generation_no "
                + "SET s.projection_status='PROJECTED',s.graph_revision=?,s.last_projected_at=NOW(),s.last_error=NULL "
                + "WHERE s.task_scope=? AND s.task_id=?", id, revision, scope, taskId);
    }

    public void failed(long id, String scope, long taskId, String error, int attempts, int maxAttempts) {
        String status = attempts >= maxAttempts ? "FAILED" : "PENDING";
        int delay = Math.min(300, Math.max(5, attempts * attempts * 5));
        int changed = jdbc.update("UPDATE data_map_graph_outbox SET status=?,available_at=TIMESTAMPADD(SECOND,?,NOW()),last_error=?,locked_by=NULL,locked_at=NULL "
                        + "WHERE id=? AND status='PROCESSING'",
                status, delay, limit(error), id);
        if (changed != 1) return;
        jdbc.update("UPDATE data_map_lineage_task_state s JOIN data_map_graph_outbox o "
                + "ON o.id=? AND s.task_scope=o.task_scope AND s.task_id=o.task_id "
                + "AND s.snapshot_id=o.snapshot_id AND s.generation_no=o.generation_no "
                + "SET s.projection_status='FAILED',s.last_error=? WHERE s.task_scope=? AND s.task_id=?",
                id, limit(error), scope, taskId);
    }

    private String limit(String value) {
        if (value == null) return null;
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }
}
