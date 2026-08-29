package com.yjn.sqlagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yjn.sqlagent.model.entity.SqlTaskVersionStep;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SqlTaskVersionStepMapper extends BaseMapper<SqlTaskVersionStep> {
    List<SqlTaskVersionStep> listByVersion(@Param("taskId") long taskId,
                                           @Param("versionNo") int versionNo);

    int deleteByVersion(@Param("taskId") long taskId,
                        @Param("versionNo") int versionNo);
}
