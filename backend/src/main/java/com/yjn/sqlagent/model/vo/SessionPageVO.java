package com.yjn.sqlagent.model.vo;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SessionPageVO {

    private List<SessionVO> items;
    private int page;
    private int pageSize;
    private long total;
}
