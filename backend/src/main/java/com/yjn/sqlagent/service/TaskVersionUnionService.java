package com.yjn.sqlagent.service;

import com.yjn.sqlagent.model.dto.TaskVersionUnionSaveDTO;
import java.util.Map;

public interface TaskVersionUnionService {
    Map<String, Object> list(int page, int pageSize, String keyword);
    Map<String, Object> candidates(int page, int pageSize, String keyword);
    Map<String, Object> get(long unionId);
    Map<String, Object> create(String operator, TaskVersionUnionSaveDTO request);
    Map<String, Object> update(String operator, long unionId, TaskVersionUnionSaveDTO request);
    Map<String, Object> publish(String operator, long unionId, long revision);
    boolean isActiveMember(long taskId, int versionNo);
    void invalidateMemberCompare(long taskId, int versionNo);
}
