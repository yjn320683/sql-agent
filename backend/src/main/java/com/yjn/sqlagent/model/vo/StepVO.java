package com.yjn.sqlagent.model.vo;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class StepVO {

    /** thinking / text / tool / user_question */
    private String kind;

    private String text;

    private String id;

    private String name;

    private Object input;

    private String result;

    private Boolean isError;

    private String semanticType;

    private String requestId;

    private JsonNode questions;

    private JsonNode rawInput;

    private String status;

    private JsonNode answers;
}
