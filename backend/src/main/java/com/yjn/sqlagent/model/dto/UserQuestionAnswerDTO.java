package com.yjn.sqlagent.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserQuestionAnswerDTO {

    private List<Map<String, Object>> answers;

    private Boolean cancelled;

    private String reason;
}
