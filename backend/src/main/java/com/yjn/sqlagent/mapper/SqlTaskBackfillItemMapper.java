package com.yjn.sqlagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yjn.sqlagent.model.entity.SqlTaskBackfillItem;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SqlTaskBackfillItemMapper extends BaseMapper<SqlTaskBackfillItem> {
    List<SqlTaskBackfillItem> listByBatch(@Param("batchId") long batchId);
    List<SqlTaskBackfillItem> listPending(@Param("batchId") long batchId, @Param("limit") int limit);
    int countActive(@Param("batchId") long batchId);
    int claim(@Param("id") long id);
    int markSubmitted(@Param("id") long id, @Param("executionId") long executionId);
    int markFailed(@Param("id") long id, @Param("message") String message);
    int retryFailed(@Param("batchId") long batchId);
    void reconcileStatuses();
}
