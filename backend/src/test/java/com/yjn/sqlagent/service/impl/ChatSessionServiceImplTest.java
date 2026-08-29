package com.yjn.sqlagent.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yjn.sqlagent.common.ErrorCode;
import com.yjn.sqlagent.exception.BusinessException;
import com.yjn.sqlagent.mapper.ChatSessionMapper;
import com.yjn.sqlagent.model.dto.SessionManageQueryDTO;
import com.yjn.sqlagent.model.entity.ChatSession;
import com.yjn.sqlagent.model.vo.SessionPageVO;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatSessionServiceImplTest {

    @Mock
    private ChatSessionMapper mapper;

    @Test
    void touchCreatesSessionWithCurrentObId() {
        when(mapper.selectById("session-1")).thenReturn(null);
        ChatSessionServiceImpl service = new ChatSessionServiceImpl(mapper);

        service.touch("138284", "session-1", "查询订单数据");

        ArgumentCaptor<ChatSession> captor = ArgumentCaptor.forClass(ChatSession.class);
        verify(mapper).insert(captor.capture());
        assertEquals("138284", captor.getValue().getObId());
        assertEquals("session-1", captor.getValue().getSessionId());
    }

    @Test
    void listActiveFiltersByCurrentObId() {
        when(mapper.listActive("138284", 20)).thenReturn(Collections.emptyList());
        ChatSessionServiceImpl service = new ChatSessionServiceImpl(mapper);

        service.listActive("138284", 20);

        verify(mapper).listActive("138284", 20);
    }

    @Test
    void manageUsesOwnedFilterPaginationAndWhitelistedSort() {
        ChatSession row = session("session-1", "138284");
        row.setArchived(0);
        when(mapper.countManaged("138284", "all", "orders")).thenReturn(1L);
        when(mapper.listManaged("138284", "all", "orders", 20L, 20, "createdAt", "asc"))
                .thenReturn(Collections.singletonList(row));
        SessionManageQueryDTO query = new SessionManageQueryDTO();
        query.setStatus("all");
        query.setKeyword(" orders ");
        query.setPage(2);
        query.setSortBy("createdAt");
        query.setSortOrder("asc");
        ChatSessionServiceImpl service = new ChatSessionServiceImpl(mapper);

        SessionPageVO result = service.manage("138284", query);

        assertEquals(1L, result.getTotal());
        assertEquals(2, result.getPage());
        assertEquals(1, result.getItems().size());
        assertFalse(result.getItems().get(0).getArchived());
        verify(mapper).listManaged("138284", "all", "orders", 20L, 20, "createdAt", "asc");
    }

    @Test
    void manageRejectsUnknownSortBeforeQueryingDatabase() {
        SessionManageQueryDTO query = new SessionManageQueryDTO();
        query.setSortBy("title desc; drop table chat_session");
        ChatSessionServiceImpl service = new ChatSessionServiceImpl(mapper);

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.manage("138284", query)
        );

        assertEquals(ErrorCode.BAD_REQUEST.getCode(), error.getCode());
        verify(mapper, never()).countManaged("138284", "active", null);
    }

    @Test
    void renameTrimsTitleAndKeepsOwnershipCheck() {
        ChatSession row = session("session-1", "138284");
        when(mapper.selectById("session-1")).thenReturn(row);
        ChatSessionServiceImpl service = new ChatSessionServiceImpl(mapper);

        service.rename("138284", "session-1", "  订单优化  ");

        assertEquals("订单优化", row.getTitle());
        verify(mapper).updateById(row);
    }

    @Test
    void archiveAndRestoreOnlyChangeArchivedFlag() {
        ChatSession row = session("session-1", "138284");
        when(mapper.selectById("session-1")).thenReturn(row);
        ChatSessionServiceImpl service = new ChatSessionServiceImpl(mapper);

        service.archive("138284", "session-1");
        assertEquals(1, row.getArchived());

        service.restore("138284", "session-1");
        assertEquals(0, row.getArchived());
        verify(mapper, org.mockito.Mockito.times(2)).updateById(row);
    }

    @Test
    void requireOwnedRejectsOtherUserAndLegacySession() {
        ChatSession row = session("session-1", "100001");
        when(mapper.selectById("session-1")).thenReturn(row);
        ChatSessionServiceImpl service = new ChatSessionServiceImpl(mapper);

        BusinessException otherUser = assertThrows(
                BusinessException.class,
                () -> service.requireOwned("138284", "session-1")
        );
        assertEquals(ErrorCode.FORBIDDEN.getCode(), otherUser.getCode());

        row.setObId(null);
        BusinessException legacy = assertThrows(
                BusinessException.class,
                () -> service.requireOwned("138284", "session-1")
        );
        assertEquals(ErrorCode.FORBIDDEN.getCode(), legacy.getCode());
    }

    @Test
    void missingSessionReturnsNotFoundForMutation() {
        when(mapper.selectById("missing")).thenReturn(null);
        ChatSessionServiceImpl service = new ChatSessionServiceImpl(mapper);

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.restore("138284", "missing")
        );

        assertEquals(ErrorCode.NOT_FOUND.getCode(), error.getCode());
    }

    private ChatSession session(String sessionId, String obId) {
        ChatSession row = new ChatSession();
        row.setSessionId(sessionId);
        row.setObId(obId);
        return row;
    }
}
