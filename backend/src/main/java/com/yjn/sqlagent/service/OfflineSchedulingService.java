package com.yjn.sqlagent.service;

import com.yjn.sqlagent.model.dto.SqlTaskBackfillCreateDTO;
import com.yjn.sqlagent.model.dto.SqlTaskDependencySaveDTO;
import com.yjn.sqlagent.model.dto.SqlTaskScheduleSaveDTO;
import com.yjn.sqlagent.model.entity.SqlTaskBackfillBatch;
import com.yjn.sqlagent.model.entity.SqlTaskDependency;
import com.yjn.sqlagent.model.entity.SqlTaskScheduleRun;
import com.yjn.sqlagent.model.vo.SqlTaskScheduleVO;
import java.util.List;
import java.util.Map;

public interface OfflineSchedulingService {
    SqlTaskScheduleVO getSchedule(long taskId);
    SqlTaskScheduleVO saveSchedule(String operator, long taskId, SqlTaskScheduleSaveDTO request);
    List<SqlTaskDependency> getDependencies(long taskId);
    List<SqlTaskDependency> saveDependencies(String operator, long taskId, SqlTaskDependencySaveDTO request);
    Map<String, Object> dag();
    SqlTaskBackfillBatch createBackfill(String operator, long taskId, SqlTaskBackfillCreateDTO request);
    Map<String, Object> listBackfills(long taskId, int page, int pageSize);
    Map<String, Object> listRuns(long taskId, int page, int pageSize);
    void processDueSchedules();
    void reconcileAndRetry();
}
