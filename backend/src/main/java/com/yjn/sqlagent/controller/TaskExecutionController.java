package com.yjn.sqlagent.controller;

import com.yjn.sqlagent.common.BaseResponse;
import com.yjn.sqlagent.common.ErrorCode;
import com.yjn.sqlagent.exception.BusinessException;
import com.yjn.sqlagent.model.dto.ExecutionCenterQueryDTO;
import com.yjn.sqlagent.model.vo.ExecutionLogChunkVO;
import com.yjn.sqlagent.model.vo.ExecutionSummaryVO;
import com.yjn.sqlagent.model.vo.TaskExecutionPageVO;
import com.yjn.sqlagent.model.vo.TaskExecutionVO;
import com.yjn.sqlagent.service.CurrentUserService;
import com.yjn.sqlagent.service.TaskExecutionLogService;
import com.yjn.sqlagent.service.TaskExecutionService;
import java.nio.file.Path;
import javax.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/task-executions")
public class TaskExecutionController {

    private final TaskExecutionService executionService;
    private final TaskExecutionLogService logService;
    private final CurrentUserService currentUserService;

    public TaskExecutionController(TaskExecutionService executionService,
                                   TaskExecutionLogService logService,
                                   CurrentUserService currentUserService) {
        this.executionService = executionService;
        this.logService = logService;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    public BaseResponse<TaskExecutionPageVO> list(@ModelAttribute ExecutionCenterQueryDTO query,
                                                   HttpServletRequest request) {
        currentUserService.requireObId(request);
        return BaseResponse.success(executionService.listAll(query));
    }

    @GetMapping("/summary")
    public BaseResponse<ExecutionSummaryVO> summary(HttpServletRequest request) {
        currentUserService.requireObId(request);
        return BaseResponse.success(executionService.summary());
    }

    @GetMapping("/{executionId}")
    public BaseResponse<TaskExecutionVO> get(@PathVariable long executionId,
                                              HttpServletRequest request) {
        currentUserService.requireObId(request);
        return BaseResponse.success(executionService.get(executionId));
    }

    @PostMapping("/{executionId}/cancel")
    public BaseResponse<Void> cancel(@PathVariable long executionId, HttpServletRequest request) {
        currentUserService.requireObId(request);
        executionService.cancel(executionId);
        return BaseResponse.success(null);
    }

    @GetMapping("/{executionId}/logs")
    public BaseResponse<ExecutionLogChunkVO> logs(@PathVariable long executionId,
                                                   @RequestParam(defaultValue = "0") long offset,
                                                   @RequestParam(defaultValue = "65536") int limit,
                                                   HttpServletRequest request) {
        currentUserService.requireObId(request);
        executionService.get(executionId);
        return BaseResponse.success(logService.read(executionId, offset, limit));
    }

    @GetMapping("/{executionId}/logs/download")
    public ResponseEntity<FileSystemResource> download(@PathVariable long executionId,
                                                        HttpServletRequest request) {
        currentUserService.requireObId(request);
        executionService.get(executionId);
        Path path = logService.resolveExisting(executionId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename("execution-" + executionId + ".log")
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.TEXT_PLAIN)
                .body(new FileSystemResource(path));
    }

    @GetMapping("/{executionId}/steps/{stepNo}/logs")
    public BaseResponse<ExecutionLogChunkVO> stepLogs(@PathVariable long executionId,
                                                       @PathVariable int stepNo,
                                                       @RequestParam(defaultValue = "0") long offset,
                                                       @RequestParam(defaultValue = "65536") int limit,
                                                       HttpServletRequest request) {
        currentUserService.requireObId(request);
        TaskExecutionVO execution = executionService.get(executionId);
        boolean exists = execution.getSteps().stream().anyMatch(step -> Integer.valueOf(stepNo).equals(step.getStepNo()));
        if (!exists) {
            throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "执行Step不存在");
        }
        return BaseResponse.success(logService.readStep(executionId, stepNo, offset, limit));
    }

    @GetMapping("/{executionId}/steps/{stepNo}/logs/download")
    public ResponseEntity<FileSystemResource> downloadStep(@PathVariable long executionId,
                                                            @PathVariable int stepNo,
                                                            HttpServletRequest request) {
        currentUserService.requireObId(request);
        TaskExecutionVO execution = executionService.get(executionId);
        boolean exists = execution.getSteps().stream().anyMatch(step -> Integer.valueOf(stepNo).equals(step.getStepNo()));
        if (!exists) {
            throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "执行Step不存在");
        }
        Path path = logService.resolveStepExisting(executionId, stepNo);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename("execution-" + executionId + "-step-" + stepNo + ".log")
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.TEXT_PLAIN)
                .body(new FileSystemResource(path));
    }
}
