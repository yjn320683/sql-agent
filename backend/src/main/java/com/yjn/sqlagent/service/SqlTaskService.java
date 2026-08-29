package com.yjn.sqlagent.service;

import com.yjn.sqlagent.model.dto.SqlTaskQueryDTO;
import com.yjn.sqlagent.model.dto.SqlTaskSaveDTO;
import com.yjn.sqlagent.model.entity.SqlTask;
import com.yjn.sqlagent.model.vo.SqlTaskPageVO;
import com.yjn.sqlagent.model.vo.SqlTaskVO;

public interface SqlTaskService {
    SqlTaskPageVO list(SqlTaskQueryDTO query);
    SqlTaskVO get(long taskId);
    SqlTask require(long taskId);
    SqlTaskVO create(String obId, SqlTaskSaveDTO request);
    SqlTaskVO update(String obId, long taskId, SqlTaskSaveDTO request);
    SqlTaskVO setEnabled(String obId, long taskId, long revision, boolean enabled);
    SqlTaskVO setArchived(String obId, long taskId, long revision, boolean archived);
    SqlTaskVO cloneTask(String obId, long taskId);
}
