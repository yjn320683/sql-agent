package com.yjn.sqlagent.service;

import com.yjn.sqlagent.model.dto.SqlTaskVersionActivateDTO;
import com.yjn.sqlagent.model.dto.SqlTaskVersionSaveDTO;
import com.yjn.sqlagent.model.vo.SqlTaskVersionPageVO;
import com.yjn.sqlagent.model.vo.SqlTaskVersionVO;

public interface SqlTaskVersionService {
    SqlTaskVersionVO create(String operatorObId, long taskId, String note, long revision);

    SqlTaskVersionPageVO list(long taskId, int page, int pageSize, String keyword);

    SqlTaskVersionVO get(long taskId, int versionNo);

    SqlTaskVersionVO save(String obId, long taskId, int versionNo, SqlTaskVersionSaveDTO request);

    SqlTaskVersionVO activate(String obId, long taskId, int versionNo, SqlTaskVersionActivateDTO request);
}
