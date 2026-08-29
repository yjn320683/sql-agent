package com.yjn.sqlagent.datacompare.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjn.sqlagent.datacompare.model.CreateCompareRequest;
import com.yjn.sqlagent.datacompare.model.TaskVersionContent;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class DataCompareRepositoryTest {
    private JdbcTemplate jdbc;
    private DataCompareRepository repository;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        repository = new DataCompareRepository(jdbc, new ObjectMapper());
        jdbc.execute("CREATE TABLE sql_task (id BIGINT PRIMARY KEY,name VARCHAR(128),description VARCHAR(1024),"
                + "sql_content CLOB,ddl_content CLOB,parameter_schema CLOB,sql_checksum VARCHAR(64),revision BIGINT,"
                + "effective_version_no INT,archived BOOLEAN)");
        jdbc.execute("CREATE TABLE sql_task_version (task_id BIGINT NOT NULL,version_no INT NOT NULL,"
                + "status VARCHAR(16) NOT NULL,name VARCHAR(128),description VARCHAR(1024),version_note VARCHAR(512),"
                + "sql_content CLOB NOT NULL,ddl_content CLOB,parameter_schema CLOB,sql_checksum VARCHAR(64),"
                + "revision BIGINT NOT NULL,base_effective_version_no INT,base_effective_checksum VARCHAR(64),"
                + "PRIMARY KEY(task_id,version_no))");
        jdbc.execute("CREATE TABLE data_compare_job_detail (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "compare_type VARCHAR(16),plan_token VARCHAR(64),task_id BIGINT,baseline_version_no INT,"
                + "candidate_version_no INT,baseline_checksum VARCHAR(64),candidate_checksum VARCHAR(64),"
                + "union_id BIGINT,union_ddl_checksum VARCHAR(64),baseline_table VARCHAR(512),"
                + "candidate_table VARCHAR(512),original_baseline_sql CLOB,original_candidate_sql CLOB,"
                + "generated_baseline_sql CLOB,generated_candidate_sql CLOB,temporary_tables CLOB,"
                + "baseline_steps CLOB,candidate_steps CLOB,only_compare_same_column BOOLEAN,"
                + "status VARCHAR(16),operator_ob_id VARCHAR(128))");
        jdbc.update("INSERT INTO sql_task VALUES(42,'task','description','INSERT OVERWRITE TABLE dw.target SELECT 1',"
                + "NULL,'[]','task-checksum',7,NULL,FALSE)");
        insertVersion(1, "DRAFT", "draft_target", 5, null);
        insertVersion(2, "EFFECTIVE", "effective_target", 8, 1);
        insertVersion(3, "HISTORICAL", "historical_target", 3, 2);
        insertVersion(4, "STALE", "stale_target", 6, 2);
    }

    @Test
    void readsEveryVersionLifecycleAndDdl() {
        assertEquals(Arrays.asList("DRAFT", "EFFECTIVE", "HISTORICAL", "STALE"),
                Arrays.asList(1, 2, 3, 4).stream()
                        .map(versionNo -> repository.version(42, versionNo).getStatus())
                        .collect(Collectors.toList()));
        TaskVersionContent stale = repository.version(42, 4);
        assertEquals("ALTER TABLE dw.stale_target ADD COLUMNS (remark STRING)", stale.getDdl());
        assertEquals(6L, stale.getRevision());
        assertEquals(Integer.valueOf(2), stale.getBaseEffectiveVersionNo());
        assertNull(repository.version(42, 99));
    }

    @Test
    void initialTaskCodeIsTheEffectiveBaseline() {
        TaskVersionContent effective = repository.effective(42);
        assertEquals(0, effective.getVersionNo());
        assertEquals("EFFECTIVE", effective.getStatus());
        assertEquals("task-checksum", effective.getChecksum());
        assertNull(effective.getBaseEffectiveVersionNo());
    }

    @Test
    void directTableComparePersistsNullVersionNumbers() {
        CreateCompareRequest request = new CreateCompareRequest();
        request.setCompareType("TABLE");
        request.setBaselineTable("dw.left_table");
        request.setCandidateTable("dw.right_table");
        request.setOnlyCompareSameColumn(true);

        long id = repository.createJob(request, "tester", null, null, null);

        assertEquals(1L, id);
        assertNull(jdbc.queryForObject(
                "SELECT baseline_version_no FROM data_compare_job_detail WHERE id=?", Integer.class, id));
        assertNull(jdbc.queryForObject(
                "SELECT candidate_version_no FROM data_compare_job_detail WHERE id=?", Integer.class, id));
    }

    private void insertVersion(int versionNo, String status, String target, long revision,
                               Integer baseVersionNo) {
        jdbc.update("INSERT INTO sql_task_version(task_id,version_no,status,name,description,version_note,"
                        + "sql_content,ddl_content,parameter_schema,sql_checksum,revision,base_effective_version_no,"
                        + "base_effective_checksum) VALUES(42,?,?,?,?,?,?,?,?,?,?,?,?)",
                versionNo, status, "task-v" + versionNo, status.toLowerCase(), "note-v" + versionNo,
                "INSERT OVERWRITE TABLE dw." + target + " SELECT id FROM ods.source;",
                "ALTER TABLE dw." + target + " ADD COLUMNS (remark STRING)", "[]",
                "checksum-v" + versionNo, revision, baseVersionNo,
                baseVersionNo == null ? null : "checksum-v" + baseVersionNo);
    }
}
