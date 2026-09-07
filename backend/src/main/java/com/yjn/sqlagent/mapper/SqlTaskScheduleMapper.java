package com.yjn.sqlagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yjn.sqlagent.model.entity.SqlTaskSchedule;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SqlTaskScheduleMapper extends BaseMapper<SqlTaskSchedule> {
    SqlTaskSchedule selectByTaskId(@Param("taskId") long taskId);
    SqlTaskSchedule selectByTaskIdForUpdate(@Param("taskId") long taskId);
    List<SqlTaskSchedule> listDue(@Param("now") LocalDateTime now, @Param("limit") int limit);
    int updateOptimistically(@Param("schedule") SqlTaskSchedule schedule,
                             @Param("expectedRevision") long expectedRevision);
    int claim(@Param("id") long id, @Param("expectedTrigger") LocalDateTime expectedTrigger,
              @Param("nextTrigger") LocalDateTime nextTrigger);
    void updateLastRunStatus(@Param("id") long id, @Param("status") String status);
}
