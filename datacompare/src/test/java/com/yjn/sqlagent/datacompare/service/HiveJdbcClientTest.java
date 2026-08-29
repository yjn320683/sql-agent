package com.yjn.sqlagent.datacompare.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.yjn.sqlagent.datacompare.config.DataCompareProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class HiveJdbcClientTest {
    @Test
    void packagesGuavaRequiredForHiveJdbcNumericResults() throws Exception {
        assertEquals("com.google.common.primitives.Longs",
                Class.forName("com.google.common.primitives.Longs").getName());
    }

    @Test
    void usesConfiguredHiveUserInsteadOfAnonymousConnection() {
        DataCompareProperties properties = new DataCompareProperties();
        properties.setHiveJdbcUrl("jdbc:hive2://hive:10000/default");
        properties.setHiveJdbcUser("test-user");

        assertEquals("test-user", new HiveJdbcClient(properties).jdbcUser());
    }

    @Test
    void createsUniqueCompareTableWithoutDroppingExistingTables() {
        assertEquals("CREATE TABLE `verify`.`dc_1_b_1` LIKE `test`.`source`",
                HiveJdbcClient.createLikeSql("verify.dc_1_b_1", "test.source"));
    }

    @Test
    void splitsStatementsWithoutBreakingQuotedSemicolon() {
        List<String> statements = HiveJdbcClient.splitStatements(
                "SET x='a;b'; INSERT OVERWRITE TABLE dw.t SELECT 'x;y';");

        assertEquals(2, statements.size());
        assertEquals("SET x='a;b'", statements.get(0).trim());
    }
}
