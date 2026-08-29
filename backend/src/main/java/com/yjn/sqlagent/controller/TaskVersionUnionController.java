package com.yjn.sqlagent.controller;

import com.yjn.sqlagent.common.BaseResponse;
import com.yjn.sqlagent.model.dto.TaskRevisionDTO;
import com.yjn.sqlagent.model.dto.TaskVersionUnionSaveDTO;
import com.yjn.sqlagent.service.CurrentUserService;
import com.yjn.sqlagent.service.TaskVersionUnionService;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/task-version-unions")
public class TaskVersionUnionController {
    private final TaskVersionUnionService service;
    private final CurrentUserService currentUserService;

    public TaskVersionUnionController(TaskVersionUnionService service, CurrentUserService currentUserService) {
        this.service = service;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    public BaseResponse<Map<String, Object>> list(@RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "20") int pageSize,
                                                  @RequestParam(defaultValue = "") String keyword,
                                                  HttpServletRequest request) {
        currentUserService.requireObId(request);
        return BaseResponse.success(service.list(page, pageSize, keyword));
    }

    @GetMapping("/candidates")
    public BaseResponse<Map<String, Object>> candidates(@RequestParam(defaultValue = "1") int page,
                                                        @RequestParam(defaultValue = "20") int pageSize,
                                                        @RequestParam(defaultValue = "") String keyword,
                                                        HttpServletRequest request) {
        currentUserService.requireObId(request);
        return BaseResponse.success(service.candidates(page, pageSize, keyword));
    }

    @GetMapping("/{unionId}")
    public BaseResponse<Map<String, Object>> get(@PathVariable long unionId, HttpServletRequest request) {
        currentUserService.requireObId(request);
        return BaseResponse.success(service.get(unionId));
    }

    @PostMapping
    public BaseResponse<Map<String, Object>> create(@Valid @RequestBody TaskVersionUnionSaveDTO body,
                                                    HttpServletRequest request) {
        return BaseResponse.success(service.create(currentUserService.requireObId(request), body));
    }

    @PutMapping("/{unionId}")
    public BaseResponse<Map<String, Object>> update(@PathVariable long unionId,
                                                    @Valid @RequestBody TaskVersionUnionSaveDTO body,
                                                    HttpServletRequest request) {
        return BaseResponse.success(service.update(currentUserService.requireObId(request), unionId, body));
    }

    @PostMapping("/{unionId}/publish")
    public BaseResponse<Map<String, Object>> publish(@PathVariable long unionId,
                                                     @Valid @RequestBody TaskRevisionDTO body,
                                                     HttpServletRequest request) {
        return BaseResponse.success(service.publish(
                currentUserService.requireObId(request), unionId, body.getRevision()));
    }
}
