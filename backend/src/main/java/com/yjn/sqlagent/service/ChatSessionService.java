package com.yjn.sqlagent.service;

import com.yjn.sqlagent.model.dto.SessionManageQueryDTO;
import com.yjn.sqlagent.model.vo.SessionPageVO;
import com.yjn.sqlagent.model.vo.SessionVO;
import java.util.List;

public interface ChatSessionService {

    /** 发消息前调用：首次创建元数据，否则刷新活跃时间。 */
    void touch(String obId, String sessionId, String message);

    /** 未归档会话列表，按最后活跃时间倒序。 */
    List<SessionVO> listActive(String obId, Integer limit);

    default List<SessionVO> listActive(String obId) {
        return listActive(obId, null);
    }

    /** 当前用户会话管理分页。 */
    SessionPageVO manage(String obId, SessionManageQueryDTO query);

    /** 修改会话标题。 */
    void rename(String obId, String sessionId, String title);

    /** 软删除。 */
    void archive(String obId, String sessionId);

    /** 恢复归档会话。 */
    void restore(String obId, String sessionId);

    /** 校验会话属于当前登录 obId。 */
    void requireOwned(String obId, String sessionId);
}
