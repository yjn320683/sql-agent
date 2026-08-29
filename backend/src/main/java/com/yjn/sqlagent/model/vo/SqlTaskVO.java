package com.yjn.sqlagent.model.vo;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import com.yjn.sqlagent.model.dto.SqlTaskParameterDTO;
import lombok.Data;

@Data
public class SqlTaskVO {
    private Long id;
    private String name;
    private String description;
    private String taskType;
    private String executionFrequency;
    private String owner;
    private Boolean enabled;
    private String sql;
    private String ddl;
    private List<SqlTaskParameterDTO> parameters = new ArrayList<>();
    private String sqlChecksum;
    private Long revision;
    private Integer effectiveVersionNo;
    private Boolean archived;
    private String archivedBy;
    private LocalDateTime archivedAt;
    private String createdBy;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String lastExecutionStatus;
    private LocalDateTime lastExecutionAt;
    private Integer latestVersionNo;
    private Integer stepCount;
}
