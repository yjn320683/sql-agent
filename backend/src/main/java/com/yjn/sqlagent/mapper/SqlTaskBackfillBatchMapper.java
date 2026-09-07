package com.yjn.sqlagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yjn.sqlagent.model.entity.SqlTaskBackfillBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface SqlTaskBackfillBatchMapper extends BaseMapper<SqlTaskBackfillBatch> {
    void updateProgress(@Param("id") long id);
    List<SqlTaskBackfillBatch> listActive();
    List<SqlTaskBackfillBatch> listByTask(@Param("taskId") long taskId,
                                          @Param("offset") long offset,
                                          @Param("pageSize") int pageSize);
    long countByTask(@Param("taskId") long taskId);
}
