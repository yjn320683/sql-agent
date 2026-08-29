package com.yjn.sqlagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yjn.sqlagent.model.entity.SqlTaskExecution;
import com.yjn.sqlagent.model.vo.ExecutionSummaryVO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SqlTaskExecutionMapper extends BaseMapper<SqlTaskExecution> {
    long countByTask(@Param("taskId") long taskId,
                     @Param("status") String status,
                     @Param("keyword") String keyword);

    List<SqlTaskExecution> listByTask(@Param("taskId") long taskId,
                                      @Param("status") String status,
                                      @Param("keyword") String keyword,
                                      @Param("offset") long offset,
                                      @Param("pageSize") int pageSize);

    long countAll(@Param("status") String status, @Param("keyword") String keyword);

    List<SqlTaskExecution> listAll(@Param("status") String status,
                                   @Param("keyword") String keyword,
                                   @Param("offset") long offset,
                                   @Param("pageSize") int pageSize);

    ExecutionSummaryVO summarize();

    int failPending(@Param("id") long id, @Param("message") String message);
}
