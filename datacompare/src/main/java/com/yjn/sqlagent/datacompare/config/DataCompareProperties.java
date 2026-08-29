package com.yjn.sqlagent.datacompare.config;

import javax.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "data-compare")
public class DataCompareProperties {
    @NotBlank private String hiveJdbcUrl;
    @NotBlank private String hiveJdbcUser = System.getProperty("user.name", "anonymous");
    @NotBlank private String hiveTempDatabase;
    @NotBlank private String logDir;
}
