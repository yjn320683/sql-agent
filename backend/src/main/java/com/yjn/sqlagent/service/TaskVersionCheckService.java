package com.yjn.sqlagent.service;

import com.yjn.sqlagent.model.vo.TaskVersionCheckSummaryVO;
import java.util.Map;

public interface TaskVersionCheckService {
    void record(long taskId, Integer versionNo, String type, Map<String, Object> result, String operator);

    TaskVersionCheckSummaryVO summary(long taskId, int versionNo);
}
