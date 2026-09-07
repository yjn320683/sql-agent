package com.yjn.sqlagent.model.dto;

import java.util.ArrayList;
import java.util.List;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SqlTaskDependencySaveDTO {
    @Valid @NotNull private List<Item> items = new ArrayList<>();

    @Data
    public static class Item {
        @NotNull private Long upstreamTaskId;
        private String dependencyType = "SUCCESS";
    }
}
