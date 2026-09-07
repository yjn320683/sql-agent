package com.yjn.sqlagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yjn.sqlagent.model.entity.SqlTaskVersionCheck;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SqlTaskVersionCheckMapper extends BaseMapper<SqlTaskVersionCheck> {
    int upsert(@Param("check") SqlTaskVersionCheck check);

    List<SqlTaskVersionCheck> listLatest(@Param("taskId") long taskId,
                                         @Param("versionNo") int versionNo);

    String selectLatestCompareStatus(@Param("taskId") long taskId,
                                     @Param("versionNo") int versionNo,
                                     @Param("versionChecksum") String versionChecksum);
}
