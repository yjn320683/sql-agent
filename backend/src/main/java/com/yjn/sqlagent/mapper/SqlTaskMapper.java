package com.yjn.sqlagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yjn.sqlagent.model.entity.SqlTask;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SqlTaskMapper extends BaseMapper<SqlTask> {
    long countTasks(@Param("keyword") String keyword,
                    @Param("status") String status,
                    @Param("updatedBy") String updatedBy);

    List<SqlTask> listTasks(@Param("keyword") String keyword,
                            @Param("status") String status,
                            @Param("updatedBy") String updatedBy,
                            @Param("offset") long offset,
                            @Param("pageSize") int pageSize);

    SqlTask selectByIdForUpdate(@Param("taskId") long taskId);

    int updateMetadataOptimistically(@Param("task") SqlTask task,
                                     @Param("expectedRevision") long expectedRevision);

    int updateEnabledOptimistically(@Param("taskId") long taskId,
                                    @Param("expectedRevision") long expectedRevision,
                                    @Param("enabled") boolean enabled,
                                    @Param("operator") String operator);

    int updateArchiveOptimistically(@Param("taskId") long taskId,
                                    @Param("expectedRevision") long expectedRevision,
                                    @Param("archived") boolean archived,
                                    @Param("operator") String operator);

    int activateVersionOptimistically(@Param("task") SqlTask task,
                                      @Param("expectedRevision") long expectedRevision);
    List<Map<String, Object>> listScheduleDagNodes();
    List<Map<String, Object>> listRecentSuccessfulDurations();
}
