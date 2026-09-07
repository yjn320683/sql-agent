package com.yjn.sqlagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yjn.sqlagent.model.entity.SqlTaskDependency;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SqlTaskDependencyMapper extends BaseMapper<SqlTaskDependency> {
    List<SqlTaskDependency> listByTask(@Param("taskId") long taskId);
    List<SqlTaskDependency> listAll();
    void deleteByTask(@Param("taskId") long taskId);
    String selectLatestExecutionStatus(@Param("taskId") long taskId);
}
