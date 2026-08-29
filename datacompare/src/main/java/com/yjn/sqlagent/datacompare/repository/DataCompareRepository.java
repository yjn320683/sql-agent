package com.yjn.sqlagent.datacompare.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjn.sqlagent.datacompare.model.CompareTableRequest;
import com.yjn.sqlagent.datacompare.model.CreateCompareRequest;
import com.yjn.sqlagent.datacompare.model.TaskVersionContent;
import com.yjn.sqlagent.datacompare.model.VersionComparePlan;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class DataCompareRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public DataCompareRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public TaskVersionContent version(long taskId, int versionNo) {
        List<TaskVersionContent> rows = jdbc.query(
                "SELECT task_id,version_no,status,name,description,version_note,sql_content,ddl_content,parameter_schema,"
                        + "sql_checksum,revision,base_effective_version_no,base_effective_checksum "
                        + "FROM sql_task_version "
                        + "WHERE task_id=? AND version_no=?",
                (rs, row) -> new TaskVersionContent(
                        rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                        rs.getString(10), rs.getLong(11),
                        rs.getObject(12) == null ? null : rs.getInt(12), rs.getString(13)),
                taskId, versionNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 当前任务生效代码；尚未通过版本生效时 versionNo 使用 0。 */
    public TaskVersionContent effective(long taskId) {
        List<TaskVersionContent> rows = jdbc.query(
                "SELECT id,COALESCE(effective_version_no,0),'EFFECTIVE',name,description,NULL,sql_content,ddl_content,"
                        + "parameter_schema,sql_checksum,revision,effective_version_no,sql_checksum "
                        + "FROM sql_task WHERE id=? AND archived=0",
                (rs, row) -> new TaskVersionContent(
                        rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                        rs.getLong(11), rs.getObject(12) == null ? null : rs.getInt(12), rs.getString(13)), taskId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> unionContext(long taskId, int versionNo) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT u.id union_id,u.union_ddl,u.union_ddl_checksum,u.status,m.id union_member_id "
                        + "FROM sql_task_version_union_member m JOIN sql_task_version_union u ON u.id=m.union_id "
                        + "WHERE m.task_id=? AND m.version_no=? AND u.status IN ('DRAFT','PUBLISH_FAILED')",
                taskId, versionNo);
        return rows.isEmpty() ? Collections.emptyMap() : rows.get(0);
    }

    public String versionStepScript(long taskId, int versionNo) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT step_no,step_sql FROM sql_task_version_step WHERE task_id=? AND version_no=? ORDER BY step_order",
                taskId, versionNo);
        if (rows.isEmpty()) return null;
        StringBuilder script = new StringBuilder();
        for (Map<String, Object> row : rows) {
            script.append("====step:").append(row.get("step_no")).append("====\n")
                    .append(row.get("step_sql")).append('\n');
        }
        return script.toString();
    }

    public void createPlan(VersionComparePlan plan, String operator) {
        jdbc.update("INSERT INTO data_compare_plan(plan_token,task_id,baseline_version_no,candidate_version_no,"
                        + "baseline_checksum,candidate_checksum,union_id,union_ddl_checksum,original_baseline_sql,"
                        + "original_candidate_sql,generated_baseline_sql,generated_candidate_sql,baseline_steps,"
                        + "candidate_steps,table_mappings,temporary_tables,operator_ob_id,expires_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,DATE_ADD(NOW(),INTERVAL 2 HOUR))",
                plan.getToken(), plan.getTaskId(), plan.getBaselineVersionNo(), plan.getCandidateVersionNo(),
                plan.getBaselineChecksum(), plan.getCandidateChecksum(), plan.getUnionId(), plan.getUnionDdlChecksum(),
                plan.getOriginalBaselineSql(), plan.getOriginalCandidateSql(), plan.getGeneratedBaselineSql(),
                plan.getGeneratedCandidateSql(), toJson(plan.getBaselineSteps()), toJson(plan.getCandidateSteps()),
                toJson(plan.getTableMappings()), toJson(plan.getTemporaryTables()), operator);
    }

    public Map<String, Object> plan(String token) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM data_compare_plan WHERE plan_token=? AND expires_at>NOW()", token);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long createJob(CreateCompareRequest request, String operator, VersionComparePlan plan,
                          String baselineSql, String candidateSql) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO data_compare_job_detail(compare_type,plan_token,task_id,baseline_version_no,candidate_version_no,"
                            + "baseline_checksum,candidate_checksum,union_id,union_ddl_checksum,baseline_table,candidate_table,"
                            + "original_baseline_sql,original_candidate_sql,generated_baseline_sql,generated_candidate_sql,"
                            + "temporary_tables,baseline_steps,candidate_steps,only_compare_same_column,status,operator_ob_id) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, request.getCompareType());
            ps.setString(2, plan == null ? null : plan.getToken());
            setLong(ps, 3, request.getTaskId());
            Integer baselineVersionNo = plan == null
                    ? request.getBaselineVersionNo() : Integer.valueOf(plan.getBaselineVersionNo());
            Integer candidateVersionNo = plan == null
                    ? request.getCandidateVersionNo() : Integer.valueOf(plan.getCandidateVersionNo());
            setInteger(ps, 4, baselineVersionNo);
            setInteger(ps, 5, candidateVersionNo);
            ps.setString(6, plan == null ? null : plan.getBaselineChecksum());
            ps.setString(7, plan == null ? null : plan.getCandidateChecksum());
            setLong(ps, 8, plan == null ? null : plan.getUnionId());
            ps.setString(9, plan == null ? null : plan.getUnionDdlChecksum());
            ps.setString(10, request.getBaselineTable());
            ps.setString(11, request.getCandidateTable());
            ps.setString(12, baselineSql);
            ps.setString(13, candidateSql);
            ps.setString(14, plan == null ? null : plan.getGeneratedBaselineSql());
            ps.setString(15, plan == null ? null : plan.getGeneratedCandidateSql());
            ps.setString(16, plan == null ? null : toJson(plan.getTemporaryTables()));
            ps.setString(17, toJson(plan == null ? request.getBaselineSteps() : plan.getBaselineSteps()));
            ps.setString(18, toJson(plan == null ? request.getCandidateSteps() : plan.getCandidateSteps()));
            ps.setBoolean(19, Boolean.TRUE.equals(request.getOnlyCompareSameColumn()));
            ps.setString(20, "PENDING");
            ps.setString(21, operator);
            return ps;
        }, key);
        long id = key.getKey().longValue();
        if (plan != null) {
            if (jdbc.update("UPDATE data_compare_plan SET consumed_job_id=? WHERE plan_token=? AND consumed_job_id IS NULL",
                    id, plan.getToken()) != 1) throw new IllegalArgumentException("验数计划已被消费，请重新生成");
        }
        return id;
    }

    public long createTable(long jobId, CompareTableRequest table, String operator) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO data_compare_tbl_verify(job_id,original_tbl_name,baseline_source_tbl_name,baseline_tbl_name,"
                            + "candidate_source_tbl_name,candidate_tbl_name,compare_rule,status,operator_ob_id) "
                            + "VALUES(?,?,?,?,?,?,?,'PENDING',?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, jobId);
            ps.setString(2, table.getOriginalTable());
            ps.setString(3, table.getBaselineSourceTable() == null ? table.getBaselineTable() : table.getBaselineSourceTable());
            ps.setString(4, table.getBaselineTable());
            ps.setString(5, table.getCandidateSourceTable() == null ? table.getCandidateTable() : table.getCandidateSourceTable());
            ps.setString(6, table.getCandidateTable());
            ps.setString(7, toJson(table.getRule()));
            ps.setString(8, operator);
            return ps;
        }, key);
        return key.getKey().longValue();
    }

    public Map<String, Object> job(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM data_compare_job_detail WHERE id=?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> tables(long jobId) {
        return jdbc.queryForList("SELECT * FROM data_compare_tbl_verify WHERE job_id=? ORDER BY id", jobId);
    }

    public List<Map<String, Object>> reportTables(long jobId, String keyword, int offset, int limit) {
        StringBuilder sql = new StringBuilder("SELECT v.*,CASE WHEN v.result_stale=1 THEN 'NEEDS_RERUN' "
                + "WHEN v.force_pass=1 THEN 'FORCE_PASSED' ELSE v.status END display_status "
                + "FROM data_compare_tbl_verify v WHERE v.job_id=?");
        java.util.ArrayList<Object> args = new java.util.ArrayList<>(); args.add(jobId);
        appendTableKeyword(sql, args, keyword);
        sql.append(" ORDER BY v.id LIMIT ?,?"); args.add(offset); args.add(limit);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public long countReportTables(long jobId, String keyword) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM data_compare_tbl_verify v WHERE v.job_id=?");
        java.util.ArrayList<Object> args = new java.util.ArrayList<>(); args.add(jobId);
        appendTableKeyword(sql, args, keyword);
        Long value = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return value == null ? 0 : value;
    }

    private void appendTableKeyword(StringBuilder sql, java.util.List<Object> args, String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) return;
        String pattern = "%" + keyword.trim() + "%";
        sql.append(" AND (CAST(v.id AS CHAR) LIKE ? OR COALESCE(v.original_tbl_name,'') LIKE ?")
                .append(" OR COALESCE(v.baseline_source_tbl_name,'') LIKE ? OR COALESCE(v.candidate_source_tbl_name,'') LIKE ?)");
        for (int index = 0; index < 4; index++) args.add(pattern);
    }

    public Map<String, Object> table(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT v.*,CASE WHEN v.result_stale=1 THEN 'NEEDS_RERUN' "
                        + "WHEN v.force_pass=1 THEN 'FORCE_PASSED' ELSE v.status END display_status "
                        + "FROM data_compare_tbl_verify v WHERE v.id=?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> list(String status, String type, Long taskId, Integer versionNo,
                                          Long jobId, String operator, String fromTime, String toTime,
                                          String keyword, int offset, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM data_compare_job_detail WHERE 1=1");
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        appendListFilters(sql, args, status, type, taskId, versionNo, jobId, operator,
                fromTime, toTime, keyword);
        sql.append(" ORDER BY id DESC LIMIT ?,?"); args.add(offset); args.add(limit);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public long count(String status, String type, Long taskId, Integer versionNo, Long jobId,
                      String operator, String fromTime, String toTime, String keyword) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM data_compare_job_detail WHERE 1=1");
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        appendListFilters(sql, args, status, type, taskId, versionNo, jobId, operator,
                fromTime, toTime, keyword);
        Long value = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return value == null ? 0 : value;
    }

    private void appendListFilters(StringBuilder sql, java.util.List<Object> args,
                                   String status, String type, Long taskId, Integer versionNo,
                                   Long jobId, String operator, String fromTime, String toTime,
                                   String keyword) {
        if (status != null && !status.isEmpty() && !"all".equalsIgnoreCase(status)) {
            sql.append(" AND status=?"); args.add(status);
        }
        if (type != null && !type.isEmpty() && !"all".equalsIgnoreCase(type)) {
            sql.append(" AND compare_type=?"); args.add(type);
        }
        if (taskId != null) { sql.append(" AND task_id=?"); args.add(taskId); }
        if (versionNo != null) { sql.append(" AND candidate_version_no=?"); args.add(versionNo); }
        if (jobId != null) { sql.append(" AND id=?"); args.add(jobId); }
        if (operator != null && !operator.isEmpty()) { sql.append(" AND operator_ob_id=?"); args.add(operator); }
        if (fromTime != null && !fromTime.isEmpty()) { sql.append(" AND create_time>=?"); args.add(fromTime); }
        if (toTime != null && !toTime.isEmpty()) { sql.append(" AND create_time<=?"); args.add(toTime); }
        if (keyword != null && !keyword.isEmpty()) {
            String pattern = "%" + keyword + "%";
            sql.append(" AND (CAST(id AS CHAR) LIKE ? OR CAST(task_id AS CHAR) LIKE ?")
                    .append(" OR baseline_table LIKE ? OR candidate_table LIKE ? OR operator_ob_id LIKE ?)");
            for (int index = 0; index < 5; index++) args.add(pattern);
        }
    }

    public boolean claim(long id, String instanceId) {
        return jdbc.update("UPDATE data_compare_job_detail SET status='RUNNING',executor_instance_id=?,"
                + "started_at=NOW(),heartbeat_at=NOW(),error_message=NULL WHERE id=? AND status='PENDING'",
                instanceId, id) == 1;
    }

    public void heartbeat(long id, String instanceId) {
        jdbc.update("UPDATE data_compare_job_detail SET heartbeat_at=NOW() WHERE id=? AND executor_instance_id=? "
                + "AND status IN ('RUNNING','CANCELLING')", id, instanceId);
    }

    public void finishJob(long id, String status, String error) {
        jdbc.update("UPDATE data_compare_job_detail SET status=?,error_message=?,finished_at=NOW(),heartbeat_at=NOW() WHERE id=?",
                status, error, id);
    }

    public void generatedSql(long id, String baseline, String candidate) {
        jdbc.update("UPDATE data_compare_job_detail SET generated_baseline_sql=?,generated_candidate_sql=? WHERE id=?",
                baseline, candidate, id);
    }

    public void temporaryTables(long id, List<String> tables) {
        jdbc.update("UPDATE data_compare_job_detail SET temporary_tables=? WHERE id=?", toJson(tables), id);
    }

    public void startTable(long id, String logFile) {
        jdbc.update("UPDATE data_compare_tbl_verify SET status='RUNNING',log_file=?,started_at=NOW(),error_message=NULL WHERE id=?",
                logFile, id);
    }

    public void updateTableNames(long id, String baseline, String candidate) {
        jdbc.update("UPDATE data_compare_tbl_verify SET baseline_tbl_name=?,candidate_tbl_name=? WHERE id=?",
                baseline, candidate, id);
    }

    public void finishTable(long id, Map<String, Object> result, String status, String error) {
        jdbc.update("UPDATE data_compare_tbl_verify SET is_part_tab=?,part_nums=?,partition_scope=?,meta_data_is_same=?,"
                        + "meta_data_diff=?,row_num_is_same=?,row_nums=?,crc32_value_is_same=?,crc32_values=?,"
                        + "col_probe_detail=?,diff_detail=?,diff_tbl_name=?,status=?,error_message=?,"
                        + "verified_rule_revision=rule_revision,result_stale=0,force_pass=0,force_reason=NULL,"
                        + "force_operator_ob_id=NULL,force_time=NULL,finished_at=NOW() WHERE id=?",
                result.get("isPartTable"), result.get("partitionCounts"), toJson(result.get("partitionScope")),
                result.get("metadataSame"), toJson(result.get("metadataDiff")), result.get("rowCountSame"),
                toJson(result.get("rowCounts")), result.get("crcSame"), toJson(result.get("crcValues")),
                toJson(result.get("columnProbe")), toJson(result.get("difference")), result.get("diffTable"),
                status, error, id);
    }

    public void updateRule(long id, String ruleJson, String operator) {
        jdbc.update("UPDATE data_compare_tbl_verify SET compare_rule=?,rule_revision=rule_revision+1,result_stale=1,"
                        + "force_pass=0,force_reason=NULL,force_operator_ob_id=NULL,force_time=NULL,operator_ob_id=? WHERE id=?",
                ruleJson, operator, id);
    }

    public void prepareRerun(long id, String operator) {
        jdbc.update("UPDATE data_compare_tbl_verify SET status='PENDING',result_stale=0,rerun_count=rerun_count+1,"
                        + "force_pass=0,force_reason=NULL,force_operator_ob_id=NULL,force_time=NULL,error_message=NULL,"
                        + "started_at=NULL,finished_at=NULL,operator_ob_id=? WHERE id=?",
                operator, id);
    }

    public void forcePass(long id, String reason, String operator) {
        if (jdbc.update("UPDATE data_compare_tbl_verify SET force_pass=1,force_reason=?,force_operator_ob_id=?,"
                        + "force_time=NOW() WHERE id=? AND status='NOT_PASSED' AND result_stale=0",
                reason, operator, id) != 1) {
            throw new IllegalArgumentException("只有未通过且结果未失效的表级验数可以强制通过");
        }
    }

    public void recomputeJobStatus(long jobId) {
        List<Map<String, Object>> rows = tables(jobId);
        boolean active = false, failed = false, stale = false, mismatch = false, forced = false;
        for (Map<String, Object> row : rows) {
            String status = String.valueOf(row.get("status"));
            boolean rowStale = bool(row.get("result_stale"));
            boolean rowForced = bool(row.get("force_pass"));
            active |= "PENDING".equals(status) || "RUNNING".equals(status);
            failed |= "FAILED".equals(status) || "CANCELLED".equals(status);
            stale |= rowStale;
            mismatch |= "NOT_PASSED".equals(status) && !rowForced;
            forced |= "NOT_PASSED".equals(status) && rowForced;
        }
        String status = active ? "RUNNING" : failed ? "FAILED" : stale ? "NEEDS_RERUN"
                : mismatch ? "NOT_PASSED" : forced ? "FORCE_PASSED" : "PASSED";
        jdbc.update("UPDATE data_compare_job_detail SET status=?,finished_at=CASE WHEN ?='RUNNING' THEN NULL ELSE NOW() END WHERE id=?",
                status, status, jobId);
    }

    public void linkUnionMemberLatestJob(Long unionId, long taskId, int versionNo, long jobId) {
        if (unionId == null) return;
        jdbc.update("UPDATE sql_task_version_union_member SET latest_compare_job_id=?,update_time=NOW() "
                + "WHERE union_id=? AND task_id=? AND version_no=?", jobId, unionId, taskId, versionNo);
    }

    public void finishUnfinishedTables(long jobId, String status, String error) {
        jdbc.update("UPDATE data_compare_tbl_verify SET status=?,error_message=?,finished_at=NOW() "
                        + "WHERE job_id=? AND status IN ('PENDING','RUNNING')", status, error, jobId);
    }

    public boolean requestCancel(long id) {
        return jdbc.update("UPDATE data_compare_job_detail SET status='CANCELLING' WHERE id=? AND status='RUNNING'", id) == 1;
    }

    public void failOrphaned(String instanceId) {
        jdbc.update("UPDATE data_compare_job_detail SET status='FAILED',finished_at=NOW(),"
                + "error_message='验数服务已重启，原运行无法恢复' WHERE status IN ('RUNNING','CANCELLING') "
                + "AND (executor_instance_id IS NULL OR executor_instance_id<>?)", instanceId);
    }

    public List<Long> pendingJobs() {
        return jdbc.queryForList("SELECT id FROM data_compare_job_detail WHERE status='PENDING' ORDER BY id", Long.class);
    }

    private String toJson(Object value) {
        if (value == null) return null;
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("无法序列化验数参数", e); }
    }

    public String json(Object value) { return toJson(value); }

    private static boolean bool(Object value) {
        return value instanceof Boolean ? (Boolean) value
                : value != null && ("1".equals(value.toString()) || "true".equalsIgnoreCase(value.toString()));
    }

    private static void setLong(PreparedStatement ps, int index, Long value) throws java.sql.SQLException {
        if (value == null) ps.setNull(index, java.sql.Types.BIGINT); else ps.setLong(index, value);
    }

    private static void setInteger(PreparedStatement ps, int index, Integer value) throws java.sql.SQLException {
        if (value == null) ps.setNull(index, java.sql.Types.INTEGER); else ps.setInt(index, value);
    }
}
