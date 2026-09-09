package com.yjn.sqlagent.model.dto;

import java.util.Map;
import javax.validation.constraints.Size;
import lombok.Data;

/** 页面内 AI 助手携带的业务上下文。 */
@Data
public class AiContextDTO {

    @Size(max = 64)
    private String contextType;

    @Size(max = 128)
    private String entityId;

    @Size(max = 128)
    private String parentId;

    @Size(max = 255)
    private String title;

    private Integer versionNo;

    /** 离线使用数字 revision，实时任务使用 updateTime 作为乐观锁 token。 */
    private String revision;

    private Map<String, Object> draft;
}
