package com.yjn.sqlagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yjn.sqlagent.model.entity.ChatSession;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSession> {

    /** 未归档会话，按最后活跃时间倒序。SQL 见 ChatSessionMapper.xml */
    List<ChatSession> listActive(@Param("obId") String obId, @Param("limit") Integer limit);

    long countManaged(@Param("obId") String obId,
                      @Param("status") String status,
                      @Param("keyword") String keyword);

    List<ChatSession> listManaged(@Param("obId") String obId,
                                  @Param("status") String status,
                                  @Param("keyword") String keyword,
                                  @Param("offset") long offset,
                                  @Param("pageSize") int pageSize,
                                  @Param("sortBy") String sortBy,
                                  @Param("sortOrder") String sortOrder);
}
