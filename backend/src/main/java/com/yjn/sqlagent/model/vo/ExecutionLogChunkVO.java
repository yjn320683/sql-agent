package com.yjn.sqlagent.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ExecutionLogChunkVO {
    private String content;
    private long nextOffset;
    private boolean eof;
    private boolean truncated;
}
