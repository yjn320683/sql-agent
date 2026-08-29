package com.yjn.sqlagent.model.vo;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class TaskExecutionPageVO {
    private List<TaskExecutionVO> items;
    private int page;
    private int pageSize;
    private long total;
}
