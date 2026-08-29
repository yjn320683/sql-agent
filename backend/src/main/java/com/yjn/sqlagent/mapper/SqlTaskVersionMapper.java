package com.yjn.sqlagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yjn.sqlagent.model.entity.SqlTaskVersion;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SqlTaskVersionMapper extends BaseMapper<SqlTaskVersion> {
    int nextVersionNo(@Param("taskId") long taskId);

    long countVersions(@Param("taskId") long taskId,
                       @Param("keyword") String keyword);

    List<SqlTaskVersion> listVersions(@Param("taskId") long taskId,
                                      @Param("keyword") String keyword,
                                      @Param("offset") long offset,
                                      @Param("pageSize") int pageSize);

    SqlTaskVersion selectVersion(@Param("taskId") long taskId,
                                 @Param("versionNo") int versionNo);

    SqlTaskVersion selectVersionForUpdate(@Param("taskId") long taskId,
                                          @Param("versionNo") int versionNo);

    SqlTaskVersion selectLatest(@Param("taskId") long taskId);

    int updateDraftOptimistically(@Param("version") SqlTaskVersion version,
                                  @Param("expectedRevision") long expectedRevision);

    int markPreviousEffectiveHistorical(@Param("taskId") long taskId,
                                        @Param("effectiveVersionNo") int effectiveVersionNo,
                                        @Param("operator") String operator);

    int markOtherDraftsStale(@Param("taskId") long taskId,
                             @Param("effectiveVersionNo") int effectiveVersionNo,
                             @Param("operator") String operator);

    int markEffective(@Param("taskId") long taskId,
                      @Param("versionNo") int versionNo,
                      @Param("expectedRevision") long expectedRevision,
                      @Param("operator") String operator);
}
