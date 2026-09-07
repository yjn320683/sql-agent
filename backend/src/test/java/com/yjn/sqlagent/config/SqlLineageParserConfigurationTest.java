package com.yjn.sqlagent.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.yjn.sqlagent.datacompare.config.SqlParserConfig;
import com.yjn.sqlagent.parsesql.SqlLineageParser;
import com.yjn.sqlagent.realtime.config.SqlLineageConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SqlLineageParserConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner();

    @Test
    void combinedModulesShareOneParserBean() {
        contextRunner.withUserConfiguration(SqlParserConfig.class, SqlLineageConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SqlLineageParser.class);
                    assertThat(context).hasBean("sqlLineageParser");
                });
    }

    @Test
    void dataCompareModuleProvidesParserWhenLoadedAlone() {
        contextRunner.withUserConfiguration(SqlParserConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SqlLineageParser.class);
                });
    }

    @Test
    void realtimeModuleProvidesParserWhenLoadedAlone() {
        contextRunner.withUserConfiguration(SqlLineageConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SqlLineageParser.class);
                });
    }
}
