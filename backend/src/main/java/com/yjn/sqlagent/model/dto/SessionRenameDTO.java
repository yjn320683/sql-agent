package com.yjn.sqlagent.model.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

public class SessionRenameDTO {

    @NotBlank(message = "会话标题不能为空")
    @Size(max = 64, message = "会话标题不能超过64个字符")
    private String title;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title == null ? null : title.trim();
    }
}
