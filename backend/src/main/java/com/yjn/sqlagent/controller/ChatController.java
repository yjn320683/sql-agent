package com.yjn.sqlagent.controller;

import com.yjn.sqlagent.common.BaseResponse;
import com.yjn.sqlagent.model.dto.ChatRequestDTO;
import com.yjn.sqlagent.model.dto.PermissionDecisionDTO;
import com.yjn.sqlagent.model.dto.SessionManageQueryDTO;
import com.yjn.sqlagent.model.dto.SessionRenameDTO;
import com.yjn.sqlagent.model.dto.UserQuestionAnswerDTO;
import com.yjn.sqlagent.model.vo.MessageVO;
import com.yjn.sqlagent.model.vo.SessionPageVO;
import com.yjn.sqlagent.model.vo.SessionVO;
import com.yjn.sqlagent.service.AgentProxyService;
import com.yjn.sqlagent.service.ChatSessionService;
import com.yjn.sqlagent.service.CurrentUserService;
import com.yjn.sqlagent.service.HistoryService;
import com.yjn.sqlagent.service.SqlTaskService;
import com.yjn.sqlagent.service.SqlTaskVersionService;
import com.yjn.sqlagent.service.TaskExecutionService;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final AgentProxyService agentProxyService;
    private final ChatSessionService chatSessionService;
    private final HistoryService historyService;
    private final CurrentUserService currentUserService;
    private final SqlTaskService sqlTaskService;
    private final TaskExecutionService taskExecutionService;
    private final SqlTaskVersionService sqlTaskVersionService;

    public ChatController(AgentProxyService agentProxyService,
                          ChatSessionService chatSessionService,
                          HistoryService historyService,
                          CurrentUserService currentUserService,
                          SqlTaskService sqlTaskService,
                          TaskExecutionService taskExecutionService,
                          SqlTaskVersionService sqlTaskVersionService) {
        this.agentProxyService = agentProxyService;
        this.chatSessionService = chatSessionService;
        this.historyService = historyService;
        this.currentUserService = currentUserService;
        this.sqlTaskService = sqlTaskService;
        this.taskExecutionService = taskExecutionService;
        this.sqlTaskVersionService = sqlTaskVersionService;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@Valid @RequestBody ChatRequestDTO request,
                                                HttpServletRequest servletRequest) {
        String obId = currentUserService.requireObId(servletRequest);
        sqlTaskService.require(request.getTaskId());
        if (request.getExecutionId() != null) {
            taskExecutionService.requireBelongsToTask(request.getExecutionId(), request.getTaskId());
        }
        if (request.getVersionNo() != null) {
            sqlTaskVersionService.get(request.getTaskId(), request.getVersionNo());
        }
        chatSessionService.touch(obId, request.getSessionId(), request.getMessage());
        return agentProxyService.streamChat(
                request.getSessionId(),
                obId,
                request.getTaskId(),
                request.getExecutionId(),
                request.getVersionNo(),
                request.getCommand(),
                request.getMessage());
    }

    @PostMapping("/tool-permissions/{requestId}")
    public BaseResponse<Void> decideToolPermission(@PathVariable String requestId,
                                                   @Valid @RequestBody PermissionDecisionDTO request,
                                                   HttpServletRequest servletRequest) {
        String obId = currentUserService.requireObId(servletRequest);
        agentProxyService.decideToolPermission(obId, requestId, request.getDecision());
        return BaseResponse.success(null);
    }

    @PostMapping("/user-questions/{requestId}/answer")
    public BaseResponse<Void> answerUserQuestion(@PathVariable String requestId,
                                                 @RequestBody UserQuestionAnswerDTO request,
                                                 HttpServletRequest servletRequest) {
        String obId = currentUserService.requireObId(servletRequest);
        agentProxyService.answerUserQuestion(obId, requestId, request);
        return BaseResponse.success(null);
    }

    @GetMapping("/sessions")
    public BaseResponse<List<SessionVO>> listSessions(
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        String obId = currentUserService.requireObId(request);
        return BaseResponse.success(chatSessionService.listActive(obId, limit));
    }

    @GetMapping("/sessions/manage")
    public BaseResponse<SessionPageVO> manageSessions(
            @ModelAttribute SessionManageQueryDTO query,
            HttpServletRequest request) {
        String obId = currentUserService.requireObId(request);
        return BaseResponse.success(chatSessionService.manage(obId, query));
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public BaseResponse<List<MessageVO>> messages(@PathVariable String sessionId,
                                                   HttpServletRequest request) {
        String obId = currentUserService.requireObId(request);
        chatSessionService.requireOwned(obId, sessionId);
        return BaseResponse.success(historyService.loadMessages(sessionId));
    }

    @PostMapping("/sessions/{sessionId}/archive")
    public BaseResponse<Void> archive(@PathVariable String sessionId,
                                      HttpServletRequest request) {
        String obId = currentUserService.requireObId(request);
        chatSessionService.archive(obId, sessionId);
        return BaseResponse.success(null);
    }

    @PostMapping("/sessions/{sessionId}/restore")
    public BaseResponse<Void> restore(@PathVariable String sessionId,
                                      HttpServletRequest request) {
        String obId = currentUserService.requireObId(request);
        chatSessionService.restore(obId, sessionId);
        return BaseResponse.success(null);
    }

    @PatchMapping("/sessions/{sessionId}")
    public BaseResponse<Void> rename(@PathVariable String sessionId,
                                     @Valid @RequestBody SessionRenameDTO body,
                                     HttpServletRequest request) {
        String obId = currentUserService.requireObId(request);
        chatSessionService.rename(obId, sessionId, body.getTitle());
        return BaseResponse.success(null);
    }

    @PostMapping("/sessions/{sessionId}/cancel")
    public BaseResponse<Void> cancel(@PathVariable String sessionId,
                                     HttpServletRequest request) {
        String obId = currentUserService.requireObId(request);
        chatSessionService.requireOwned(obId, sessionId);
        agentProxyService.cancelChat(sessionId);
        return BaseResponse.success(null);
    }
}
