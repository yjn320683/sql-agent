package com.yjn.sqlagent.service;

import com.yjn.sqlagent.model.vo.MessageVO;
import java.util.List;

public interface HistoryService {

    /** 读取并解析某会话的 jsonl 历史；文件不存在返回空列表。 */
    List<MessageVO> loadMessages(String sessionId);
}
