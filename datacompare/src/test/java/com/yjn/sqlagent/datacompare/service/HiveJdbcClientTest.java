package com.yjn.sqlagent.datacompare.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.yjn.sqlagent.datacompare.config.DataCompareProperties;
import com.yjn.sqlagent.parsesql.SqlDialect;
import com.yjn.sqlagent.parsesql.SqlLineageParser;
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
        List<String> statements = new SqlLineageParser().splitStatements(
                "SET x='a;b'; INSERT OVERWRITE TABLE dw.t SELECT 'x;y';", SqlDialect.HIVE);

        assertEquals(2, statements.size());
        assertEquals("SET x='a;b'", statements.get(0).trim());
    }
}
