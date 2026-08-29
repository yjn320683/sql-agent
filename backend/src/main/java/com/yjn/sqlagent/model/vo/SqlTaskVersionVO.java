package com.yjn.sqlagent.model.vo;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import com.yjn.sqlagent.model.dto.SqlTaskParameterDTO;
import lombok.Data;

@Data
public class SqlTaskVersionVO {
    private Long id;
    private Long taskId;
    private Integer versionNo;
    private Integer baseEffectiveVersionNo;
    private String baseEffectiveChecksum;
    private String name;
    private String description;
    private String sql;
    private String ddl;
    private List<SqlTaskParameterDTO> parameters = new ArrayList<>();
    private String sqlChecksum;
    private String versionNote;
    private String status;
    private Long revision;
    private Boolean canEdit;
    private Boolean inUnion;
    private Boolean canActivate;
    private String createdBy;
    private String updatedBy;
    private String effectiveBy;
    private LocalDateTime effectiveAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<SqlTaskStepVO> steps = new ArrayList<>();
}
