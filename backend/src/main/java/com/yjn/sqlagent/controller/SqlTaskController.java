package com.yjn.sqlagent.controller;

import com.yjn.sqlagent.common.BaseResponse;
import com.yjn.sqlagent.model.dto.ExecutionQueryDTO;
import com.yjn.sqlagent.model.dto.SqlTaskQueryDTO;
import com.yjn.sqlagent.model.dto.SqlTaskSaveDTO;
import com.yjn.sqlagent.model.dto.SqlTaskVersionCreateDTO;
import com.yjn.sqlagent.model.dto.SqlTaskVersionActivateDTO;
import com.yjn.sqlagent.model.dto.SqlTaskVersionSaveDTO;
import com.yjn.sqlagent.model.dto.TaskExecutionCreateDTO;
import com.yjn.sqlagent.model.dto.TaskRevisionDTO;
import com.yjn.sqlagent.model.vo.SqlTaskPageVO;
import com.yjn.sqlagent.model.vo.SqlTaskVO;
import com.yjn.sqlagent.model.vo.SqlTaskVersionPageVO;
import com.yjn.sqlagent.model.vo.SqlTaskVersionVO;
import com.yjn.sqlagent.model.vo.TaskExecutionPageVO;
import com.yjn.sqlagent.model.vo.TaskExecutionVO;
import com.yjn.sqlagent.service.CurrentUserService;
import com.yjn.sqlagent.service.SqlTaskService;
import com.yjn.sqlagent.service.SqlTaskVersionService;
import com.yjn.sqlagent.service.TaskExecutionService;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/tasks")
public class SqlTaskController {

    private final SqlTaskService taskService;
    private final TaskExecutionService executionService;
    private final SqlTaskVersionService versionService;
    private final CurrentUserService currentUserService;

    public SqlTaskController(SqlTaskService taskService,
                             TaskExecutionService executionService,
                             SqlTaskVersionService versionService,
                             CurrentUserService currentUserService) {
        this.taskService = taskService;
        this.executionService = executionService;
        this.versionService = versionService;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    public BaseResponse<SqlTaskPageVO> list(@ModelAttribute SqlTaskQueryDTO query,
                                            HttpServletRequest request) {
        currentUserService.requireObId(request);
        return BaseResponse.success(taskService.list(query));
    }

    @PostMapping
    public BaseResponse<SqlTaskVO> create(@Valid @RequestBody SqlTaskSaveDTO body,
                                          HttpServletRequest request) {
        return BaseResponse.success(taskService.create(currentUserService.requireObId(request), body));
    }

    @GetMapping("/{taskId}")
    public BaseResponse<SqlTaskVO> get(@PathVariable long taskId, HttpServletRequest request) {
        currentUserService.requireObId(request);
        return BaseResponse.success(taskService.get(taskId));
    }

    @PatchMapping("/{taskId}")
    public BaseResponse<SqlTaskVO> update(@PathVariable long taskId,
                                          @Valid @RequestBody SqlTaskSaveDTO body,
                                          HttpServletRequest request) {
        return BaseResponse.success(taskService.update(currentUserService.requireObId(request), taskId, body));
    }

    @PostMapping("/{taskId}/enable")
    public BaseResponse<SqlTaskVO> enable(@PathVariable long taskId,
                                          @Valid @RequestBody TaskRevisionDTO body,
                                          HttpServletRequest request) {
        return BaseResponse.success(taskService.setEnabled(
                currentUserService.requireObId(request), taskId, body.getRevision(), true));
    }

    @PostMapping("/{taskId}/disable")
    public BaseResponse<SqlTaskVO> disable(@PathVariable long taskId,
                                           @Valid @RequestBody TaskRevisionDTO body,
                                           HttpServletRequest request) {
        return BaseResponse.success(taskService.setEnabled(
                currentUserService.requireObId(request), taskId, body.getRevision(), false));
    }

    @PostMapping("/{taskId}/executions")
    public BaseResponse<TaskExecutionVO> execute(@PathVariable long taskId,
                                                  @RequestBody(required = false) TaskExecutionCreateDTO body,
                                                  HttpServletRequest request) {
        return BaseResponse.success(executionService.create(
                currentUserService.requireObId(request), taskId, body));
    }

    @PostMapping("/{taskId}/versions")
    public BaseResponse<SqlTaskVersionVO> createVersion(
            @PathVariable long taskId,
            @Valid @RequestBody SqlTaskVersionCreateDTO body,
            HttpServletRequest request) {
        return BaseResponse.success(versionService.create(
                currentUserService.requireObId(request), taskId, body.getNote(), body.getRevision()));
    }

    @GetMapping("/{taskId}/executions")
    public BaseResponse<TaskExecutionPageVO> executions(@PathVariable long taskId,
                                                         @ModelAttribute ExecutionQueryDTO query,
                                                         HttpServletRequest request) {
        currentUserService.requireObId(request);
        return BaseResponse.success(executionService.list(taskId, query));
    }

    @GetMapping("/{taskId}/versions")
    public BaseResponse<SqlTaskVersionPageVO> versions(
            @PathVariable long taskId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "") String keyword,
            HttpServletRequest request) {
        currentUserService.requireObId(request);
        return BaseResponse.success(versionService.list(taskId, page, pageSize, keyword));
    }

    @GetMapping("/{taskId}/versions/{versionNo}")
    public BaseResponse<SqlTaskVersionVO> version(
            @PathVariable long taskId,
            @PathVariable int versionNo,
            HttpServletRequest request) {
        currentUserService.requireObId(request);
        return BaseResponse.success(versionService.get(taskId, versionNo));
    }

    @PatchMapping("/{taskId}/versions/{versionNo}")
    public BaseResponse<SqlTaskVersionVO> saveVersion(
            @PathVariable long taskId,
            @PathVariable int versionNo,
            @Valid @RequestBody SqlTaskVersionSaveDTO body,
            HttpServletRequest request) {
        return BaseResponse.success(versionService.save(
                currentUserService.requireObId(request), taskId, versionNo, body));
    }

    @PostMapping("/{taskId}/versions/{versionNo}/activate")
    public BaseResponse<SqlTaskVersionVO> activateVersion(
            @PathVariable long taskId,
            @PathVariable int versionNo,
            @Valid @RequestBody SqlTaskVersionActivateDTO body,
            HttpServletRequest request) {
        return BaseResponse.success(versionService.activate(
                currentUserService.requireObId(request), taskId, versionNo, body));
    }

    @PostMapping("/{taskId}/archive")
    public BaseResponse<SqlTaskVO> archive(@PathVariable long taskId,
                                           @Valid @RequestBody TaskRevisionDTO body,
                                           HttpServletRequest request) {
        return BaseResponse.success(taskService.setArchived(
                currentUserService.requireObId(request), taskId, body.getRevision(), true));
    }

    @PostMapping("/{taskId}/restore")
    public BaseResponse<SqlTaskVO> restoreTask(@PathVariable long taskId,
                                               @Valid @RequestBody TaskRevisionDTO body,
                                               HttpServletRequest request) {
        return BaseResponse.success(taskService.setArchived(
                currentUserService.requireObId(request), taskId, body.getRevision(), false));
    }

    @PostMapping("/{taskId}/clone")
    public BaseResponse<SqlTaskVO> cloneTask(@PathVariable long taskId, HttpServletRequest request) {
        return BaseResponse.success(taskService.cloneTask(currentUserService.requireObId(request), taskId));
    }
}
