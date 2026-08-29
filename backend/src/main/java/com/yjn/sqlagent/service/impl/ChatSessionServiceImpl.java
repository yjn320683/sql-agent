package com.yjn.sqlagent.service.impl;

import com.yjn.sqlagent.mapper.ChatSessionMapper;
import com.yjn.sqlagent.common.ErrorCode;
import com.yjn.sqlagent.exception.BusinessException;
import com.yjn.sqlagent.model.dto.SessionManageQueryDTO;
import com.yjn.sqlagent.model.entity.ChatSession;
import com.yjn.sqlagent.model.vo.SessionPageVO;
import com.yjn.sqlagent.model.vo.SessionVO;
import com.yjn.sqlagent.service.ChatSessionService;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatSessionServiceImpl implements ChatSessionService {

    private final ChatSessionMapper mapper;

    public ChatSessionServiceImpl(ChatSessionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void touch(String obId, String sessionId, String message) {
        ChatSession existing = mapper.selectById(sessionId);
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            ChatSession row = new ChatSession();
            row.setSessionId(sessionId);
            row.setObId(obId);
            row.setTitle(buildTitle(message));
            row.setCreatedAt(now);
            row.setLastActiveAt(now);
            row.setArchived(0);
            try {
                mapper.insert(row);
            } catch (DuplicateKeyException concurrentInsert) {
                ChatSession winner = mapper.selectById(sessionId);
                if (winner == null) {
                    throw concurrentInsert;
                }
                assertOwner(winner, obId);
                winner.setLastActiveAt(now);
                mapper.updateById(winner);
            }
        } else {
            assertOwner(existing, obId);
            existing.setLastActiveAt(now);
            mapper.updateById(existing);
        }
    }

    @Override
    public List<SessionVO> listActive(String obId, Integer limit) {
        if (limit != null && (limit < 1 || limit > 50)) {
            throw badRequest("limit 必须在1到50之间");
        }
        return mapper.listActive(obId, limit).stream()
                .map(this::toVO)
                .collect(Collectors.toList());
    }

    @Override
    public SessionPageVO manage(String obId, SessionManageQueryDTO query) {
        validateQuery(query);
        String keyword = query.getKeyword() == null ? null : query.getKeyword().trim();
        long total = mapper.countManaged(obId, query.getStatus(), keyword);
        if (total == 0) {
            return new SessionPageVO(Collections.emptyList(), query.getPage(), query.getPageSize(), 0);
        }
        long offset = (long) (query.getPage() - 1) * query.getPageSize();
        List<SessionVO> items = mapper.listManaged(
                        obId,
                        query.getStatus(),
                        keyword,
                        offset,
                        query.getPageSize(),
                        query.getSortBy(),
                        query.getSortOrder())
                .stream()
                .map(this::toVO)
                .collect(Collectors.toList());
        return new SessionPageVO(items, query.getPage(), query.getPageSize(), total);
    }

    @Override
    public void rename(String obId, String sessionId, String title) {
        ChatSession existing = requireOwnedSession(obId, sessionId);
        String normalized = title == null ? "" : title.trim();
        if (normalized.isEmpty() || normalized.length() > 64) {
            throw badRequest("会话标题长度必须在1到64个字符之间");
        }
        existing.setTitle(normalized);
        mapper.updateById(existing);
    }

    @Override
    public void archive(String obId, String sessionId) {
        ChatSession existing = requireOwnedSession(obId, sessionId);
        existing.setArchived(1);
        mapper.updateById(existing);
    }

    @Override
    public void restore(String obId, String sessionId) {
        ChatSession existing = requireOwnedSession(obId, sessionId);
        existing.setArchived(0);
        mapper.updateById(existing);
    }

    @Override
    public void requireOwned(String obId, String sessionId) {
        requireOwnedSession(obId, sessionId);
    }

    private ChatSession requireOwnedSession(String obId, String sessionId) {
        ChatSession existing = mapper.selectById(sessionId);
        if (existing == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "会话不存在");
        }
        assertOwner(existing, obId);
        return existing;
    }

    private void assertOwner(ChatSession session, String obId) {
        if (session.getObId() == null || !session.getObId().equals(obId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN.getCode(), "无权访问该会话");
        }
    }

    private String buildTitle(String message) {
        if (message == null) {
            return "";
        }
        String trimmed = message.trim();
        return trimmed.length() > 30 ? trimmed.substring(0, 30) : trimmed;
    }

    private SessionVO toVO(ChatSession session) {
        SessionVO vo = new SessionVO();
        vo.setSessionId(session.getSessionId());
        vo.setTitle(session.getTitle());
        vo.setCreatedAt(session.getCreatedAt());
        vo.setLastActiveAt(session.getLastActiveAt());
        vo.setArchived(Integer.valueOf(1).equals(session.getArchived()));
        return vo;
    }

    private void validateQuery(SessionManageQueryDTO query) {
        if (query == null) {
            throw badRequest("查询参数不能为空");
        }
        if (!"active".equals(query.getStatus())
                && !"archived".equals(query.getStatus())
                && !"all".equals(query.getStatus())) {
            throw badRequest("status 仅支持 active、archived 或 all");
        }
        if (query.getPage() == null || query.getPage() < 1) {
            throw badRequest("page 必须大于0");
        }
        if (query.getPageSize() == null || query.getPageSize() < 1 || query.getPageSize() > 100) {
            throw badRequest("pageSize 必须在1到100之间");
        }
        if (!"createdAt".equals(query.getSortBy()) && !"lastActiveAt".equals(query.getSortBy())) {
            throw badRequest("sortBy 仅支持 createdAt 或 lastActiveAt");
        }
        if (!"asc".equals(query.getSortOrder()) && !"desc".equals(query.getSortOrder())) {
            throw badRequest("sortOrder 仅支持 asc 或 desc");
        }
    }

    private BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST.getCode(), message);
    }
}
