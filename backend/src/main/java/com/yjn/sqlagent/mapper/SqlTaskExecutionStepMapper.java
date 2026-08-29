package com.yjn.sqlagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yjn.sqlagent.model.entity.SqlTaskExecutionStep;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SqlTaskExecutionStepMapper extends BaseMapper<SqlTaskExecutionStep> {
    List<SqlTaskExecutionStep> listByExecution(@Param("executionId") long executionId);
}
