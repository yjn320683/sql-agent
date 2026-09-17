package com.yjn.sqlagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yjn.sqlagent.model.entity.TaskLineageSnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TaskLineageSnapshotMapper extends BaseMapper<TaskLineageSnapshot> {
    @Select("SELECT * FROM task_lineage_snapshot "
            + "WHERE task_scope=#{taskScope} AND task_id=#{taskId} AND version_no=#{versionNo} "
            + "AND sql_checksum=#{sqlChecksum} AND default_database=#{defaultDatabase} "
            + "ORDER BY id DESC LIMIT 1")
    TaskLineageSnapshot selectExact(@Param("taskScope") String taskScope,
                                    @Param("taskId") long taskId,
                                    @Param("versionNo") int versionNo,
                                    @Param("sqlChecksum") String sqlChecksum,
                                    @Param("defaultDatabase") String defaultDatabase);
}
