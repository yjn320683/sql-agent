package com.yjn.sqlagent.model.vo;

import java.util.List;
import lombok.Data;

@Data
public class MessageVO {

    /** "user" 或 "assistant" */
    private String role;

    private String content;

    /** 仅 assistant 可能有；无思考内容时为 null */
    private String thinking;

    /** assistant 的有序展示步骤；兼容旧数据时可为空 */
    private List<StepVO> steps;
}
