package com.yjn.sqlagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yjn.sqlagent.model.entity.SqlTaskScheduleRun;
import java.util.List;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SqlTaskScheduleRunMapper extends BaseMapper<SqlTaskScheduleRun> {
    List<SqlTaskScheduleRun> listByTask(@Param("taskId") long taskId,
                                        @Param("offset") long offset,
                                        @Param("pageSize") int pageSize);
    long countByTask(@Param("taskId") long taskId);
    int countActiveExecutions(@Param("taskId") long taskId);
    void reconcileExecutionStatuses();
    void syncScheduleLastRunStatuses();
    void recoverStaleRetryClaims(@Param("before") LocalDateTime before);
    List<SqlTaskScheduleRun> listRetryable(@Param("now") LocalDateTime now, @Param("limit") int limit);
    int claimRetry(@Param("id") long id);
    void markRetried(@Param("id") long id);
}
