package com.yjn.sqlagent.datacompare.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class TableColumn {
    private String name;
    private String type;
    private String comment;
    private boolean partitionKey;
}
