package com.yjn.sqlagent.realtime.submit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yjn.sqlagent.realtime.common.PaimonSyncCommandBuilder;
import com.yjn.sqlagent.realtime.common.PaimonSyncOptionValidator;
import com.yjn.sqlagent.realtime.common.SubmissionSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PaimonSyncCommandBuilderTest {

    @Test
    void buildsCompleteMysqlCdcCommandAndMasksPassword() {
        SubmissionSpec spec = validSpec();
        PaimonSyncCommandBuilder.Command command = new PaimonSyncCommandBuilder().build(spec);

        assertEquals("/data/paimon-action.jar", command.getJarPath());
        assertOption(command.getArguments(), "--including_tables", "orders|order_item");
        assertOption(command.getArguments(), "--mode", "combined");
        assertOption(command.getArguments(), "--multiple_table_primary_keys", "orders=id");
        assertOption(command.getArguments(), "--multiple_table_partition_keys", "orders=dt");
        assertOption(command.getArguments(), "--multiple_table_computed_column", "orders=dt=date_format(created_at,yyyy-MM-dd)");
        assertTrue(command.getArguments().contains("metastore.partitioned-table=true"));
        assertTrue(command.getArguments().contains("password=中文密码"));
        assertFalse(command.maskedArguments().contains("password=中文密码"));
        assertTrue(command.maskedArguments().contains("password=******"));
    }

    @Test
    void rejectsInvalidBusinessDomain() {
        SubmissionSpec spec = validSpec();
        Map<String, Object> config = spec.getTask().getTaskConfig();
        Map<String, Object> cdc = map(config.get("cdcConfig"));
        cdc.put("domainPrefix", "bad-domain");
        config.put("cdcConfig", cdc);
        spec.getTask().setTaskConfig(config);

        assertThrows(IllegalArgumentException.class, () -> new PaimonSyncCommandBuilder().build(spec));
    }

    @Test
    void rejectsUnsafePaimonOptionCombinations() {
        assertThrows(IllegalArgumentException.class, () -> PaimonSyncOptionValidator.validateTableConf(
                Map.of("changelog-producer", "input", "changelog-producer.row-deduplicate", "true")));
        assertThrows(IllegalArgumentException.class, () -> PaimonSyncOptionValidator.validateTableConf(
                Map.of("changelog-producer", "lookup",
                        "changelog-producer.row-deduplicate-ignore-fields", "updated_at")));
        for (String mergeEngine : List.of("first-row", "partial-update", "aggregation")) {
            assertThrows(IllegalArgumentException.class, () -> PaimonSyncOptionValidator.validateTableConf(
                    Map.of("merge-engine", mergeEngine)));
        }
    }

    private SubmissionSpec validSpec() {
        SubmissionSpec spec = new SubmissionSpec();
        spec.setTaskId(12L);
        spec.setTaskInstanceId(34L);
        SubmissionSpec.TaskSpec task = new SubmissionSpec.TaskSpec();
        Map<String, Object> orderConfig = new LinkedHashMap<>();
        orderConfig.put("primaryKeys", List.of("id"));
        orderConfig.put("partitionKeys", List.of("dt"));
        orderConfig.put("computedColumns", List.of("dt=date_format(created_at,yyyy-MM-dd)"));
        Map<String, Object> cdc = new LinkedHashMap<>();
        cdc.put("targetDatabase", "ods");
        cdc.put("databaseName", "sales");
        cdc.put("domainPrefix", "trade");
        cdc.put("selectedTables", List.of("orders", "order_item"));
        cdc.put("tableConfigs", Map.of("orders", orderConfig));
        task.setTaskConfig(Map.of("sourceServerId", 7L, "cdcConfig", cdc));
        spec.setTask(task);
        SubmissionSpec.RuntimeConfig runtime = new SubmissionSpec.RuntimeConfig();
        runtime.setPaimonActionJarPath("/data/paimon-action.jar");
        runtime.setPaimonWarehouse("hdfs:///warehouse");
        runtime.setCatalogConf(Map.of("metastore", "hive"));
        spec.setRuntimeConfig(runtime);
        SubmissionSpec.ServerSnapshot server = new SubmissionSpec.ServerSnapshot();
        server.setId(7L);
        server.setAddress("jdbc:mysql://mysql.example:3307/sales?useUnicode=true");
        server.setDatabaseName("sales");
        server.setDatabasePrefix("sale");
        server.setAccount("cdc_user");
        server.setPassword("中文密码");
        spec.setServers(List.of(server));
        return spec;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return new LinkedHashMap<>((Map<String, Object>) value);
    }

    private void assertOption(List<String> args, String option, String value) {
        int index = args.indexOf(option);
        assertTrue(index >= 0, "missing option " + option);
        assertEquals(value, args.get(index + 1));
    }
}
