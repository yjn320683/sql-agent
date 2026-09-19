package com.yjn.sqlagent.model.dto;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class SqlQueryPreviewDTO extends SqlStructurePreviewDTO {
    /** 无显式 Step 标记的单语句脚本由执行器统一编号为 Step 0。 */
    @NotNull @Min(0) private Integer stepNo = 0;
    @NotNull @Min(1) @Max(200) private Integer limit = 100;
    @Size(max = 256) private String defaultDb;
}
