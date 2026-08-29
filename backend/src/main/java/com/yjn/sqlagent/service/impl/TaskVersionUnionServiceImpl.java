package com.yjn.sqlagent.service.impl;

import com.yjn.sqlagent.common.ErrorCode;
import com.yjn.sqlagent.datacompare.service.HiveDdlService;
import com.yjn.sqlagent.datacompare.service.HiveJdbcClient;
import com.yjn.sqlagent.exception.BusinessException;
import com.yjn.sqlagent.mapper.SqlTaskMapper;
import com.yjn.sqlagent.mapper.SqlTaskVersionMapper;
import com.yjn.sqlagent.model.dto.TaskVersionUnionMemberDTO;
import com.yjn.sqlagent.model.dto.TaskVersionUnionSaveDTO;
import com.yjn.sqlagent.model.entity.SqlTask;
import com.yjn.sqlagent.model.entity.SqlTaskVersion;
import com.yjn.sqlagent.service.TaskSqlStructureService;
import com.yjn.sqlagent.service.TaskVersionUnionService;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 联合版本的成员维护、验数门禁与一次性发布。 */
@Service
public class TaskVersionUnionServiceImpl implements TaskVersionUnionService {
    private final JdbcTemplate jdbc;
    private final SqlTaskMapper taskMapper;
    private final SqlTaskVersionMapper versionMapper;
    private final TaskSqlStructureService structureService;
    private final HiveDdlService ddlService;
    private final HiveJdbcClient hive;
    private final TransactionTemplate transactions;

    public TaskVersionUnionServiceImpl(JdbcTemplate jdbc, SqlTaskMapper taskMapper,
                                       SqlTaskVersionMapper versionMapper,
                                       TaskSqlStructureService structureService,
                                       HiveDdlService ddlService, HiveJdbcClient hive,
                                       TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.taskMapper = taskMapper;
        this.versionMapper = versionMapper;
        this.structureService = structureService;
        this.ddlService = ddlService;
        this.hive = hive;
        this.transactions = transactions;
    }

    @Override
    public Map<String, Object> list(int page, int pageSize, String keyword) {
        validatePage(page, pageSize);
        String value = keyword == null ? "" : keyword.trim();
        String filter = value.isEmpty() ? "" : " WHERE CAST(id AS CHAR) LIKE ? OR created_by LIKE ? OR updated_by LIKE ?";
        Object[] filterArgs = value.isEmpty() ? new Object[0]
                : new Object[]{"%" + value + "%", "%" + value + "%", "%" + value + "%"};
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM sql_task_version_union" + filter,
                Long.class, filterArgs);
        List<Object> args = new ArrayList<>(); Collections.addAll(args, filterArgs);
        args.add((page - 1) * pageSize); args.add(pageSize);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM sql_task_version_union" + filter + " ORDER BY id DESC LIMIT ?,?", args.toArray());
        List<Map<String, Object>> items = rows.stream().map(row -> detail(number(row.get("id")), row))
                .collect(Collectors.toList());
        return page(items, page, pageSize, total == null ? 0 : total);
    }

    @Override
    public Map<String, Object> candidates(int page, int pageSize, String keyword) {
        validatePage(page, pageSize);
        String value = keyword == null ? "" : keyword.trim();
        String where = " FROM sql_task_version v JOIN sql_task t ON t.id=v.task_id "
                + "LEFT JOIN sql_task_version_union_member m ON m.task_id=v.task_id AND m.version_no=v.version_no "
                + "WHERE v.status='DRAFT' AND t.archived=0 AND m.id IS NULL "
                + "AND v.base_effective_checksum=t.sql_checksum "
                + "AND (v.base_effective_version_no=t.effective_version_no "
                + "OR (v.base_effective_version_no IS NULL AND t.effective_version_no IS NULL))";
        List<Object> args = new ArrayList<>();
        if (!value.isEmpty()) {
            where += " AND (CAST(v.task_id AS CHAR) LIKE ? OR CAST(v.version_no AS CHAR) LIKE ? OR t.name LIKE ? OR v.version_note LIKE ?)";
            String pattern = "%" + value + "%";
            for (int i = 0; i < 4; i++) args.add(pattern);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + where, Long.class, args.toArray());
        List<Object> listArgs = new ArrayList<>(args); listArgs.add((page - 1) * pageSize); listArgs.add(pageSize);
        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT v.task_id,v.version_no,v.revision version_revision,v.ddl_content ddl,v.sql_checksum,"
                        + "v.version_note,t.name task_name,t.sql_checksum baseline_checksum,t.revision task_revision"
                        + where + " ORDER BY v.update_time DESC LIMIT ?,?", listArgs.toArray());
        return page(items, page, pageSize, total == null ? 0 : total);
    }

    @Override
    public Map<String, Object> get(long unionId) {
        return detail(unionId, null);
    }

    @Override
    @Transactional
    public Map<String, Object> create(String operator, TaskVersionUnionSaveDTO request) {
        return save(operator, null, request);
    }

    @Override
    @Transactional
    public Map<String, Object> update(String operator, long unionId, TaskVersionUnionSaveDTO request) {
        return save(operator, unionId, request);
    }

    private Map<String, Object> save(String operator, Long unionId, TaskVersionUnionSaveDTO request) {
        requireDistinctMembers(request.getMembers());
        String unionDdl = normalize(request.getUnionDdl());
        List<String> memberDdls = request.getMembers().stream().map(item -> normalize(item.getDdl()))
                .collect(Collectors.toList());
        ddlService.requireNoOverlap(unionDdl, memberDdls);
        String unionChecksum = ddlChecksum(unionDdl);

        Map<String, Object> current = null;
        Map<String, Map<String, Object>> oldMembers = new HashMap<>();
        if (unionId == null) {
            unionId = insertUnion(operator, unionDdl, unionChecksum);
        } else {
            current = lockUnion(unionId);
            String status = string(current.get("status"));
            if (!"DRAFT".equals(status) && !"PUBLISH_FAILED".equals(status)) {
                throw badRequest("只有草稿或发布失败的联合版本可以修改");
            }
            if (!Objects.equals(number(current.get("revision")), request.getRevision())) {
                throw conflict("联合版本已被其他用户更新，当前 revision=" + current.get("revision"));
            }
            for (Map<String, Object> row : memberRows(unionId)) oldMembers.put(memberKey(row), row);
            jdbc.update("UPDATE sql_task_version_union SET union_ddl=?,union_ddl_checksum=?,status='DRAFT',"
                            + "revision=revision+1,error_message=NULL,updated_by=?,update_time=NOW() WHERE id=?",
                    unionDdl, unionChecksum, operator, unionId);
        }

        boolean unionChanged = current != null
                && !Objects.equals(string(current.get("union_ddl_checksum")), unionChecksum);
        List<MemberSnapshot> snapshots = new ArrayList<>();
        for (TaskVersionUnionMemberDTO member : request.getMembers()) {
            SqlTask task = taskMapper.selectByIdForUpdate(member.getTaskId());
            SqlTaskVersion version = versionMapper.selectVersionForUpdate(member.getTaskId(), member.getVersionNo());
            requireEligible(task, version);
            if (!Objects.equals(version.getRevision(), member.getVersionRevision())) {
                throw conflict("任务 " + member.getTaskId() + " 的版本已更新，当前 revision=" + version.getRevision());
            }
            Long occupied = jdbc.queryForObject(
                    "SELECT MAX(union_id) FROM sql_task_version_union_member WHERE task_id=? AND version_no=? AND union_id<>?",
                    Long.class, member.getTaskId(), member.getVersionNo(), unionId);
            if (occupied != null) throw badRequest("任务 " + member.getTaskId() + " 的该版本已加入联合版本 " + occupied);

            String nextDdl = normalize(member.getDdl());
            boolean ddlChanged = !Objects.equals(normalize(version.getDdlContent()), nextDdl);
            if (ddlChanged) {
                version.setDdlContent(nextDdl);
                version.setSqlChecksum(contentChecksum(version));
                version.setUpdatedBy(operator);
                if (versionMapper.updateDraftOptimistically(version, member.getVersionRevision()) != 1) {
                    throw conflict("任务 " + member.getTaskId() + " 的版本保存冲突");
                }
                version.setRevision(version.getRevision() + 1);
            }
            Map<String, Object> old = oldMembers.get(member.getTaskId() + ":" + member.getVersionNo());
            Long latestJob = old == null || unionChanged || ddlChanged
                    || !Objects.equals(string(old.get("version_checksum")), version.getSqlChecksum())
                    || !Objects.equals(string(old.get("baseline_checksum")), task.getSqlChecksum())
                    ? null : longObject(old.get("latest_compare_job_id"));
            snapshots.add(new MemberSnapshot(task, version, latestJob));
        }

        jdbc.update("DELETE FROM sql_task_version_union_member WHERE union_id=?", unionId);
        for (MemberSnapshot snapshot : snapshots) {
            jdbc.update("INSERT INTO sql_task_version_union_member(union_id,task_id,version_no,version_revision,"
                            + "version_checksum,baseline_checksum,latest_compare_job_id) VALUES(?,?,?,?,?,?,?)",
                    unionId, snapshot.task.getId(), snapshot.version.getVersionNo(), snapshot.version.getRevision(),
                    snapshot.version.getSqlChecksum(), snapshot.task.getSqlChecksum(), snapshot.latestCompareJobId);
        }
        return get(unionId);
    }

    @Override
    public Map<String, Object> publish(String operator, long unionId, long revision) {
        PublishSnapshot snapshot = transactions.execute(status -> preparePublish(unionId, revision));
        if (snapshot == null) throw new IllegalStateException("无法创建联合发布快照");
        try {
            executeProductionDdl(operator, snapshot);
            transactions.executeWithoutResult(status -> activateAll(operator, snapshot));
            return get(unionId);
        } catch (Exception error) {
            jdbc.update("UPDATE sql_task_version_union SET status='PUBLISH_FAILED',error_message=?,"
                            + "updated_by=?,revision=revision+1,update_time=NOW() WHERE id=?",
                    safe(error), operator, unionId);
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "联合发布失败：" + safe(error));
        }
    }

    private PublishSnapshot preparePublish(long unionId, long revision) {
        Map<String, Object> union = lockUnion(unionId);
        String status = string(union.get("status"));
        if (!"DRAFT".equals(status) && !"PUBLISH_FAILED".equals(status)) {
            throw badRequest("当前联合版本状态不能发布");
        }
        if (number(union.get("revision")) != revision) throw conflict("联合版本 revision 已变化");
        List<Map<String, Object>> rows = memberRows(unionId);
        if (rows.size() < 2) throw badRequest("联合版本至少包含两个成员");
        List<MemberSnapshot> members = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            long taskId = number(row.get("task_id"));
            int versionNo = integer(row.get("version_no"));
            SqlTask task = taskMapper.selectByIdForUpdate(taskId);
            SqlTaskVersion version = versionMapper.selectVersionForUpdate(taskId, versionNo);
            requireEligible(task, version);
            if (version.getRevision() != number(row.get("version_revision"))
                    || !Objects.equals(version.getSqlChecksum(), string(row.get("version_checksum")))
                    || !Objects.equals(task.getSqlChecksum(), string(row.get("baseline_checksum")))) {
                throw conflict("任务 " + taskId + " 的成员版本或生效基线已变化，需要重新保存并验数");
            }
            Long jobId = longObject(row.get("latest_compare_job_id"));
            if (jobId == null) throw badRequest("任务 " + taskId + " 尚未完成当前版本验数");
            List<Map<String, Object>> jobs = jdbc.queryForList(
                    "SELECT status,baseline_checksum,candidate_checksum,union_ddl_checksum FROM data_compare_job_detail "
                            + "WHERE id=? AND union_id=? AND task_id=? AND candidate_version_no=?",
                    jobId, unionId, taskId, versionNo);
            if (jobs.isEmpty()) throw badRequest("任务 " + taskId + " 的验数报告与联合版本不匹配");
            Map<String, Object> job = jobs.get(0);
            String currentUnionDdlChecksum = blank(string(union.get("union_ddl")))
                    ? null : nullable(union.get("union_ddl_checksum"));
            if (!("PASSED".equals(string(job.get("status"))) || "FORCE_PASSED".equals(string(job.get("status"))))
                    || !Objects.equals(string(job.get("baseline_checksum")), task.getSqlChecksum())
                    || !Objects.equals(string(job.get("candidate_checksum")), version.getSqlChecksum())
                    || !Objects.equals(nullable(job.get("union_ddl_checksum")), currentUnionDdlChecksum)) {
                throw badRequest("任务 " + taskId + " 的最新验数未通过或已经失效");
            }
            members.add(new MemberSnapshot(task, version, jobId));
        }
        jdbc.update("UPDATE sql_task_version_union SET status='PUBLISHING',error_message=NULL,"
                + "revision=revision+1,update_time=NOW() WHERE id=?", unionId);
        return new PublishSnapshot(unionId, normalize(string(union.get("union_ddl"))), members);
    }

    private void executeProductionDdl(String operator, PublishSnapshot snapshot) throws Exception {
        List<HiveDdlService.DdlStatement> statements = new ArrayList<>(ddlService.parse(snapshot.unionDdl));
        snapshot.members.stream().sorted(Comparator.comparing(item -> item.task.getId()))
                .forEach(member -> statements.addAll(ddlService.parse(member.version.getDdlContent())));
        int order = 0;
        for (HiveDdlService.DdlStatement ddl : statements) {
            order++;
            String sql = ddl.toProductionSql();
            String checksum = structureService.checksum(sql, "[]");
            List<String> completed = jdbc.queryForList(
                    "SELECT status FROM sql_task_version_union_publish_log WHERE union_id=? AND statement_checksum=?",
                    String.class, snapshot.unionId, checksum);
            if (!completed.isEmpty()
                    && ("SUCCEEDED".equals(completed.get(0)) || "SKIPPED".equals(completed.get(0)))) continue;
            if (!completed.isEmpty() && ddlService.alreadyApplied(
                    ddl, hive.columns(ddl.getTable(), ignored -> { }))) {
                jdbc.update("UPDATE sql_task_version_union_publish_log SET status='SKIPPED',error_message=NULL,"
                                + "operator_ob_id=?,finished_at=NOW() WHERE union_id=? AND statement_checksum=?",
                        operator, snapshot.unionId, checksum);
                continue;
            }
            jdbc.update("INSERT INTO sql_task_version_union_publish_log(union_id,statement_order,statement_checksum,"
                            + "ddl_statement,status,operator_ob_id,started_at) VALUES(?,?,?,?, 'PENDING',?,NOW()) "
                            + "ON DUPLICATE KEY UPDATE status='PENDING',error_message=NULL,operator_ob_id=VALUES(operator_ob_id),started_at=NOW()",
                    snapshot.unionId, order, checksum, sql, operator);
            try {
                hive.execute(sql, ignored -> { });
                jdbc.update("UPDATE sql_task_version_union_publish_log SET status='SUCCEEDED',finished_at=NOW() "
                        + "WHERE union_id=? AND statement_checksum=?", snapshot.unionId, checksum);
            } catch (Exception error) {
                jdbc.update("UPDATE sql_task_version_union_publish_log SET status='FAILED',error_message=?,finished_at=NOW() "
                        + "WHERE union_id=? AND statement_checksum=?", safe(error), snapshot.unionId, checksum);
                throw error;
            }
        }
    }

    private void activateAll(String operator, PublishSnapshot snapshot) {
        Map<String, Object> union = lockUnion(snapshot.unionId);
        if (!"PUBLISHING".equals(string(union.get("status")))) throw conflict("联合版本发布状态已变化");
        for (MemberSnapshot member : snapshot.members) {
            SqlTask task = taskMapper.selectByIdForUpdate(member.task.getId());
            SqlTaskVersion version = versionMapper.selectVersionForUpdate(
                    member.version.getTaskId(), member.version.getVersionNo());
            requireEligible(task, version);
            if (!Objects.equals(task.getRevision(), member.task.getRevision())
                    || !Objects.equals(version.getRevision(), member.version.getRevision())) {
                throw conflict("DDL 执行期间任务或版本发生变化，SQL 未生效");
            }
            SqlTask effective = new SqlTask();
            effective.setId(task.getId()); effective.setName(version.getName());
            effective.setDescription(version.getDescription()); effective.setSqlContent(version.getSqlContent());
            effective.setDdlContent(version.getDdlContent()); effective.setParameterSchema(version.getParameterSchema());
            effective.setSqlChecksum(version.getSqlChecksum()); effective.setEffectiveVersionNo(version.getVersionNo());
            effective.setUpdatedBy(operator);
            if (taskMapper.activateVersionOptimistically(effective, task.getRevision()) != 1) {
                throw conflict("任务 " + task.getId() + " 生效冲突");
            }
            versionMapper.markPreviousEffectiveHistorical(task.getId(), version.getVersionNo(), operator);
            versionMapper.markOtherDraftsStale(task.getId(), version.getVersionNo(), operator);
            if (versionMapper.markEffective(task.getId(), version.getVersionNo(), version.getRevision(), operator) != 1) {
                throw conflict("任务 " + task.getId() + " 版本生效冲突");
            }
        }
        jdbc.update("UPDATE sql_task_version_union SET status='PUBLISHED',published_by=?,published_time=NOW(),"
                        + "updated_by=?,revision=revision+1,error_message=NULL,update_time=NOW() WHERE id=?",
                operator, operator, snapshot.unionId);
    }

    @Override
    public boolean isActiveMember(long taskId, int versionNo) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM sql_task_version_union_member m "
                        + "JOIN sql_task_version_union u ON u.id=m.union_id WHERE m.task_id=? AND m.version_no=? "
                        + "AND u.status IN ('DRAFT','PUBLISHING','PUBLISH_FAILED')",
                Long.class, taskId, versionNo);
        return count != null && count > 0;
    }

    @Override
    public void invalidateMemberCompare(long taskId, int versionNo) {
        jdbc.update("UPDATE sql_task_version_union_member SET latest_compare_job_id=NULL,update_time=NOW() "
                + "WHERE task_id=? AND version_no=?", taskId, versionNo);
    }

    private Map<String, Object> detail(long unionId, Map<String, Object> supplied) {
        Map<String, Object> union = supplied;
        if (union == null) {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM sql_task_version_union WHERE id=?", unionId);
            if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "联合版本不存在");
            union = rows.get(0);
        }
        Map<String, Object> result = new LinkedHashMap<>(union);
        List<Map<String, Object>> members = jdbc.queryForList(
                "SELECT m.*,t.name task_name,t.sql_checksum current_baseline_checksum,t.revision task_revision,"
                        + "v.status version_status,v.revision current_version_revision,v.ddl_content ddl,v.version_note,"
                        + "v.sql_checksum current_version_checksum,j.status compare_status,"
                        + "j.baseline_checksum compare_baseline_checksum,j.candidate_checksum compare_candidate_checksum,"
                        + "j.union_ddl_checksum compare_union_ddl_checksum "
                        + "FROM sql_task_version_union_member m JOIN sql_task t ON t.id=m.task_id "
                        + "JOIN sql_task_version v ON v.task_id=m.task_id AND v.version_no=m.version_no "
                        + "LEFT JOIN data_compare_job_detail j ON j.id=m.latest_compare_job_id "
                        + "WHERE m.union_id=? ORDER BY m.task_id", unionId);
        for (Map<String, Object> member : members) {
            member.put("union_ddl", union.get("union_ddl"));
            member.put("union_ddl_checksum", union.get("union_ddl_checksum"));
        }
        result.put("members", members);
        result.put("member_count", members.size());
        String storedStatus = string(union.get("status"));
        boolean stale = members.stream().anyMatch(this::memberSnapshotStale);
        if (stale && ("DRAFT".equals(storedStatus) || "PUBLISH_FAILED".equals(storedStatus))) {
            // 外部基线或成员版本变化不直接改历史主表，读取时派生 STALE，保存后可重新建立快照。
            result.put("status", "STALE");
        }
        result.put("can_publish", !members.isEmpty() && members.stream().allMatch(this::memberCanPublish));
        return result;
    }

    private boolean memberSnapshotStale(Map<String, Object> row) {
        return !"DRAFT".equals(string(row.get("version_status")))
                || !Objects.equals(string(row.get("version_checksum")), string(row.get("current_version_checksum")))
                || !Objects.equals(string(row.get("baseline_checksum")), string(row.get("current_baseline_checksum")));
    }

    private boolean memberCanPublish(Map<String, Object> row) {
        String currentUnionDdlChecksum = blank(string(row.get("union_ddl")))
                ? null : nullable(row.get("union_ddl_checksum"));
        return !memberSnapshotStale(row)
                && ("PASSED".equals(string(row.get("compare_status")))
                || "FORCE_PASSED".equals(string(row.get("compare_status"))))
                && Objects.equals(string(row.get("compare_baseline_checksum")),
                        string(row.get("current_baseline_checksum")))
                && Objects.equals(string(row.get("compare_candidate_checksum")),
                        string(row.get("current_version_checksum")))
                && Objects.equals(nullable(row.get("compare_union_ddl_checksum")), currentUnionDdlChecksum);
    }

    private List<Map<String, Object>> memberRows(long unionId) {
        return jdbc.queryForList("SELECT * FROM sql_task_version_union_member WHERE union_id=? ORDER BY task_id", unionId);
    }

    private Map<String, Object> lockUnion(long unionId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM sql_task_version_union WHERE id=? FOR UPDATE", unionId);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "联合版本不存在");
        return rows.get(0);
    }

    private long insertUnion(String operator, String ddl, String checksum) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO sql_task_version_union(union_ddl,union_ddl_checksum,status,revision,created_by,updated_by) "
                            + "VALUES(?,?,'DRAFT',1,?,?)", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, ddl); statement.setString(2, checksum);
            statement.setString(3, operator); statement.setString(4, operator);
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    private void requireDistinctMembers(List<TaskVersionUnionMemberDTO> members) {
        if (members == null || members.size() < 2) throw badRequest("联合版本至少包含两个任务版本");
        Set<Long> tasks = new HashSet<>();
        for (TaskVersionUnionMemberDTO member : members) {
            if (!tasks.add(member.getTaskId())) throw badRequest("同一任务只能选择一个版本");
        }
    }

    private void requireEligible(SqlTask task, SqlTaskVersion version) {
        if (task == null || version == null) throw badRequest("联合版本成员不存在");
        if (Boolean.TRUE.equals(task.getArchived())) throw badRequest("归档任务不能加入或发布联合版本");
        if (!"DRAFT".equals(version.getStatus())) throw badRequest("联合版本成员必须处于开发中状态");
        if (!Objects.equals(version.getBaseEffectiveVersionNo(), task.getEffectiveVersionNo())
                || !Objects.equals(version.getBaseEffectiveChecksum(), task.getSqlChecksum())) {
            throw conflict("任务 " + task.getId() + " 的成员版本基线已过期");
        }
    }

    private String contentChecksum(SqlTaskVersion version) {
        String ddl = normalize(version.getDdlContent());
        return structureService.checksum(version.getSqlContent() + "\n-- DDL --\n" + (ddl == null ? "" : ddl),
                version.getParameterSchema());
    }

    private String ddlChecksum(String ddl) {
        String normalized = normalize(ddl);
        // 数据库列保持非空；空 DDL 的验数快照仍使用 NULL，门禁比较时按空语义归一化。
        return structureService.checksum(normalized == null ? "" : normalized, "[]");
    }

    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
    private String memberKey(Map<String, Object> row) { return row.get("task_id") + ":" + row.get("version_no"); }
    private void validatePage(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) throw badRequest("分页参数非法");
    }
    private Map<String, Object> page(List<Map<String, Object>> items, int page, int size, long total) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items); result.put("page", page); result.put("pageSize", size); result.put("total", total);
        return result;
    }
    private String normalize(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        return value.trim();
    }
    private static long number(Object value) { return Long.parseLong(value.toString()); }
    private static int integer(Object value) { return Integer.parseInt(value.toString()); }
    private static Long longObject(Object value) { return value == null ? null : Long.valueOf(value.toString()); }
    private static String string(Object value) { return value == null ? "" : value.toString(); }
    private static String nullable(Object value) { return value == null ? null : value.toString(); }
    private BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST.getCode(), message);
    }
    private BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT.getCode(), message);
    }
    private static String safe(Throwable error) {
        String value = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return value.substring(0, Math.min(4000, value.length()));
    }

    private static final class MemberSnapshot {
        private final SqlTask task;
        private final SqlTaskVersion version;
        private final Long latestCompareJobId;
        private MemberSnapshot(SqlTask task, SqlTaskVersion version, Long latestCompareJobId) {
            this.task = task; this.version = version; this.latestCompareJobId = latestCompareJobId;
        }
    }
    private static final class PublishSnapshot {
        private final long unionId;
        private final String unionDdl;
        private final List<MemberSnapshot> members;
        private PublishSnapshot(long unionId, String unionDdl, List<MemberSnapshot> members) {
            this.unionId = unionId; this.unionDdl = unionDdl; this.members = members;
        }
    }
}
