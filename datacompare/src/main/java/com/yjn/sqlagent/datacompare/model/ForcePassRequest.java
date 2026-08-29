package com.yjn.sqlagent.datacompare.model;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import lombok.Data;

@Data
public class ForcePassRequest {
    @NotBlank @Size(max = 1000) private String reason;
}
